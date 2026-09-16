import Foundation

/// Why the bounded driving session stopped
/// (docs/04_IOS_IMPLEMENTATION.md §3 "Stop aggressive tracking").
///
/// Exported in diagnostics: a session that only ever ends on a timeout means the motion
/// signals that are supposed to end it are not arriving on device.
enum DrivingSessionEndReason: String, Sendable, Equatable, Codable {
    /// Core Motion stopped reporting `automotive` and started reporting `walking` —
    /// the parking transition the product is built around.
    case walkingDetected
    /// No further vehicle evidence within `DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout`.
    case vehicleEvidenceExpired
    /// The hard ceiling. A session that reaches this is a bug somewhere upstream; the
    /// ceiling exists so the bug costs one capped session instead of a day of GPS.
    case maximumDurationReached
    case authorizationLost
    case captureFailed
    case smartDetectionDisabled
    /// DEV-only: closed by the field-test launch hook rather than by any signal.
    case fieldTestStopped
}

/// What the current bounded session has observed so far.
///
/// A value type with no clock of its own: every time-dependent answer takes `now`, so the
/// duration and distance boundaries are unit-testable without sleeping
/// (docs/16_CODING_STANDARDS.md §8).
struct DrivingEvidence: Sendable, Equatable {
    let startedAt: Date
    /// Newest Core Motion observation that said `automotive`.
    private(set) var lastVehicleEvidenceAt: Date?
    /// Metres accumulated across plausible steps only; outliers never land here.
    private(set) var distanceMeters: Double = 0
    /// Fixes that showed the device actually travelling. docs/05 §7 requires movement
    /// evidence *and* forbids confirming on one event, so this is a count, not a flag.
    private(set) var movingSampleCount: Int = 0
    private(set) var fixCount: Int = 0
    private(set) var outlierCount: Int = 0
    /// Retained only to measure the next step. Never leaves the actor.
    private(set) var lastFix: LocationFix?
    /// When the guard in `DrivingConfirmationPolicy` fired for this session. Latched so
    /// confirmation is reported — and checkpointed — exactly once.
    private(set) var confirmedAt: Date?

    init(startedAt: Date, lastVehicleEvidenceAt: Date? = nil) {
        self.startedAt = startedAt
        self.lastVehicleEvidenceAt = lastVehicleEvidenceAt
    }

    var isConfirmed: Bool {
        confirmedAt != nil
    }

    func duration(now: Date) -> TimeInterval {
        now.timeIntervalSince(startedAt)
    }

    mutating func noteVehicleEvidence(at date: Date) {
        lastVehicleEvidenceAt = max(lastVehicleEvidenceAt ?? date, date)
    }

    mutating func markConfirmed(at date: Date) {
        guard confirmedAt == nil else { return }
        confirmedAt = date
    }

    /// Folds one fix into the session. Returns `false` when the step was rejected as an
    /// outlier, so the caller can count it rather than discover it later as inflated
    /// distance (docs/05 §5).
    @discardableResult
    mutating func record(fix: LocationFix) -> Bool {
        guard fix.isValid else {
            outlierCount += 1
            return false
        }
        fixCount += 1

        if let previous = lastFix {
            guard LocationOutlierPolicy.isPlausibleStep(from: previous, to: fix) else {
                outlierCount += 1
                // Deliberately does not advance `lastFix`: anchoring on a jump would make
                // the *next* legitimate fix look like a jump too.
                return false
            }
            distanceMeters += GeoDistance.meters(from: previous, to: fix)
        }
        lastFix = fix

        if let speed = fix.speed, speed >= DrivingConfirmationPolicy.movingSpeedThreshold {
            movingSampleCount += 1
        }
        return true
    }
}

/// docs/05_PARKING_DETECTION_ENGINE.md §7 "Driving Confirmation".
///
/// > recent vehicle evidence AND (duration >=120s OR distance >=800m) AND movement
/// > evidence consistent with travel. One event alone never confirms a full driving
/// > session.
///
/// Every threshold here is a field-tuning starting point in the same spirit as the §8
/// evidence weights, not a value the spec derives.
enum DrivingConfirmationPolicy {
    /// How recent "recent vehicle evidence" is. Longer than a red light, shorter than a
    /// coffee stop.
    static let vehicleEvidenceMaxAge: TimeInterval = 180

    /// docs/05 §7 initial conceptual guard.
    static let minimumDuration: TimeInterval = 120
    static let minimumDistance: Double = 800

    /// ~7.2 km/h — above brisk walking, below any real traffic speed.
    static let movingSpeedThreshold: Double = 2.0

    /// The "one event alone never confirms" clause, made concrete: a single fix can never
    /// satisfy the movement requirement.
    static let minimumMovingSamples = 2

    static func isConfirmed(_ evidence: DrivingEvidence, now: Date) -> Bool {
        guard let vehicleAt = evidence.lastVehicleEvidenceAt,
              now.timeIntervalSince(vehicleAt) <= vehicleEvidenceMaxAge
        else { return false }

        guard evidence.movingSampleCount >= minimumMovingSamples else { return false }

        return evidence.duration(now: now) >= minimumDuration
            || evidence.distanceMeters >= minimumDistance
    }
}

/// The bound in "bounded driving session".
///
/// docs/00_CORE_RULES.md forbids 24h continuous high accuracy location and docs/05 §19
/// puts the session behind a battery gate. Nothing in the motion signals is guaranteed to
/// arrive, so the session cannot rely on them to end: these ceilings are what makes a
/// missed `walking` transition cost one capped session instead of a day of GPS.
enum DrivingSessionTimeoutPolicy {
    /// Hard ceiling on one session. Longer than any ordinary commute, far shorter than a
    /// day. Field-tuning starting point.
    static let maximumDuration: TimeInterval = 2 * 60 * 60

    /// Silence ceiling: no `automotive` observation for this long ends the session even
    /// if no `walking` ever arrives. Long enough to survive a tunnel or a long queue.
    static let vehicleEvidenceTimeout: TimeInterval = 10 * 60

    /// The reason to stop right now, or `nil` to keep going.
    static func expiryReason(for evidence: DrivingEvidence, now: Date) -> DrivingSessionEndReason? {
        if evidence.duration(now: now) >= maximumDuration {
            return .maximumDurationReached
        }
        // Before any vehicle observation lands, the session start is the anchor —
        // otherwise a session opened on a stale signal would never time out.
        let anchor = evidence.lastVehicleEvidenceAt ?? evidence.startedAt
        if now.timeIntervalSince(anchor) >= vehicleEvidenceTimeout {
            return .vehicleEvidenceExpired
        }
        return nil
    }
}

/// Reads motion history for the two transitions the bounded session cares about.
///
/// docs/04 §5 keeps the flags independent, so this asks about flags rather than about a
/// single "current activity": a car at a light is `automotive` *and* `stationary`, and
/// collapsing that would end the session at every red light.
enum MotionEvidenceReader {
    static func latestVehicleEvidence(in samples: [MotionSample]) -> MotionSample? {
        samples.filter { $0.automotive && !$0.walking }.max { $0.timestamp < $1.timestamp }
    }

    /// Walking that starts *after* `reference`. The "shortly after vehicle" evidence in
    /// docs/05 §8 — the strongest single parking signal the platform gives us.
    static func latestWalkingEvidence(in samples: [MotionSample], after reference: Date) -> MotionSample? {
        samples
            .filter { $0.walking && !$0.automotive && $0.timestamp > reference }
            .max { $0.timestamp < $1.timestamp }
    }
}

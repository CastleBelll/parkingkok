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
    ///
    /// Both routes into this counter are folded together on purpose — §7 asks for
    /// "movement evidence consistent with travel", not for a speed — and
    /// `derivedMovingSampleCount` says how much of it the fallback contributed.
    private(set) var movingSampleCount: Int = 0
    /// Accepted fixes that carried a Core Location speed estimate, and accepted fixes that
    /// did not. The pair exists because "confirmation never fired" and "no fix ever had a
    /// speed" look identical from the outside, and on the field device it was the second
    /// one (see `MovementEvidencePolicy`).
    private(set) var speedAvailableCount: Int = 0
    private(set) var speedMissingCount: Int = 0
    /// The subset of `movingSampleCount` the distance fallback contributed. Zero with a
    /// large `speedMissingCount` means the fallback is gated wrong, not that the device
    /// stood still.
    private(set) var derivedMovingSampleCount: Int = 0
    /// Why the fallback last declined a fix, or `nil` once it last accepted one.
    private(set) var movementEvidenceRejection: MovementEvidenceRejection?
    private(set) var fixCount: Int = 0
    private(set) var outlierCount: Int = 0
    /// Retained only to measure the next step. Never leaves the actor.
    private(set) var lastFix: LocationFix?
    /// The fix the distance fallback measures against. Not the same thing as `lastFix`:
    /// it is held until a baseline long enough to decide on has accumulated
    /// (`MovementEvidencePolicy.minimumBaseline`), so at 1 Hz it spans many fixes.
    /// In memory only, exactly like `lastFix`.
    private var movementAnchor: LocationFix?
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

        recordMovementEvidence(from: fix)
        return true
    }

    /// docs/05 §7 "movement evidence consistent with travel", for one accepted fix.
    ///
    /// Speed is still the primary signal and its branch is untouched. The fallback below
    /// it exists because the September 2026 field traces say that branch never runs where
    /// this product lives: all 87 bounded fixes recovered from two underground sessions
    /// arrived with `speed == nil`, which pinned `movingSampleCount` at 0 and made
    /// confirmation structurally impossible while 3.2 km of travel piled up unused.
    ///
    /// Outliers never get here — `record(fix:)` returns before this call — so a GPS jump
    /// cannot become movement evidence, and the counters describe accepted fixes only.
    private mutating func recordMovementEvidence(from fix: LocationFix) {
        if let speed = fix.speed {
            speedAvailableCount += 1
            if speed >= DrivingConfirmationPolicy.movingSpeedThreshold {
                movingSampleCount += 1
            }
            // A fix that carried a speed is still the freshest anchor available to the
            // next fix that does not, so the fallback does not have to start cold when
            // the estimate disappears mid-drive — which is exactly what entering a tunnel
            // looks like.
            movementAnchor = fix
            return
        }

        speedMissingCount += 1
        guard let anchor = movementAnchor else {
            movementAnchor = fix
            return
        }

        switch MovementEvidencePolicy.evaluate(from: anchor, to: fix) {
        case .moving:
            movingSampleCount += 1
            derivedMovingSampleCount += 1
            movementEvidenceRejection = nil
            movementAnchor = fix
        case .inconclusive:
            break
        case let .rejected(reason):
            movementEvidenceRejection = reason
            if reason.invalidatesAnchor {
                movementAnchor = fix
            }
        }
    }
}

/// Why the distance fallback declined to call one fix movement evidence.
///
/// Reported in diagnostics rather than logged: `speedMissingCount` high with
/// `derivedMovingSampleCount` at zero is the shape of the original defect, and this is
/// the field that says whether the gate is set wrong or the device really was not moving.
enum MovementEvidenceRejection: String, Sendable, Equatable, Codable {
    /// 정확도 부족 — the displacement is inside the combined positional uncertainty of the
    /// two fixes, so it is noise rather than travel.
    case accuracyTooCoarse
    /// 시간차 초과 — the two fixes are too far apart in time for an average speed between
    /// them to describe anything.
    case intervalTooLong
    /// 거리 부족 — the displacement cleared the noise floor, but the average speed across
    /// it is below the travel threshold.
    case distanceTooShort

    /// Whether the *anchor* is what went wrong. A baseline past the ceiling is describing
    /// two unrelated stretches of travel, so the fix in hand becomes the new anchor. The
    /// other two keep the anchor: a longer baseline is precisely what turns an
    /// undecidable displacement into a decidable one.
    var invalidatesAnchor: Bool {
        self == .intervalTooLong
    }
}

/// The verdict on one fix that arrived without a speed.
enum MovementEvidenceOutcome: Sendable, Equatable {
    case moving
    /// Not yet decidable — the baseline since the anchor is still short. Not a rejection:
    /// the anchor is kept and the same question is asked again on the next fix, so this
    /// never reaches diagnostics.
    case inconclusive
    case rejected(MovementEvidenceRejection)
}

/// The distance-based half of docs/05 §7 "movement evidence consistent with travel".
///
/// ### Why it exists
/// §7 never said *speed*. The implementation read it as speed, and the field data says
/// that reading does not survive contact with the product's main setting: across the
/// 2026-09-16/17 iOS traces, 87 of 87 bounded fixes carried no speed at all, because
/// underground and in tunnels there is no GPS Doppler to derive one from. A rule that can
/// only confirm on speed cannot confirm in an underground car park.
///
/// ### Why it is gated this hard
/// Two fixes taken while standing still can be hundreds of metres apart if they are
/// inaccurate enough, and the same traces contain exactly that: a pair with accuracies of
/// 521 m and 47.9 m measured 928 m apart in 23 s. Taken at face value that is 145 km/h on
/// a subway line whose trains do not exceed 80 — it is noise, and it must not confirm a
/// drive.
///
/// ### Every constant here is a field-tuning starting point
/// In the same sense as the §8 evidence weights, and with one extra caveat: **there is no
/// above-ground car data behind any of them yet.** Both sessions the numbers were checked
/// against are subway rides. Above ground the speed branch probably works and this path
/// may hardly run. Re-derive these against real driving traces (docs/05 §18).
enum MovementEvidencePolicy {
    /// How many standard deviations of positional uncertainty the displacement has to
    /// clear before it counts as travel.
    ///
    /// `horizontalAccuracy` is a 1σ radius, so the 1σ uncertainty of a *displacement*
    /// between two independent fixes is `sqrt(a₁² + a₂²)`. Requiring two of those is a
    /// ~95% one-sided statement that the device really moved.
    ///
    /// Chosen against the field pair above, not picked round: its gate is
    /// `2·sqrt(521² + 47.9²) ≈ 1043 m` against a measured 928 m, so it is rejected. One
    /// sigma would have accepted it and confirmed a drive on a train.
    static let noiseFloorSigmas: Double = 2

    /// The shortest baseline on which a *threshold-speed* drive can clear the noise floor.
    ///
    /// Solving `movingSpeedThreshold · T ≥ noiseFloorSigmas · sqrt(2) · a` at the 20 m
    /// accuracy that ends the trace format's `good` bucket gives `T ≥ 28.3 s`. Shorter
    /// than that and the gate would reject slow but real travel however clean the fixes
    /// were — the same structural dead end the speed-only rule had, one layer down. At
    /// 1 Hz this means the fallback decides roughly twice a minute, which is ample against
    /// `minimumMovingSamples`.
    static let minimumBaseline: TimeInterval = 30

    /// The longest baseline an average speed still describes.
    ///
    /// Past this the average hides its own shape: a drive, a five-minute stop and another
    /// drive average out to something that is not "consistent with travel" at any point in
    /// between. It is also far below the significant-change cadence, which is what a gap
    /// this long inside a bounded session actually means.
    ///
    /// It used to equal `vehicleEvidenceMaxAge`; that coincidence ended when docs/05 §7
    /// fixed the vehicle window at 300s across both platforms. The two answer different
    /// questions — how long an average still describes travel, against how long ago the
    /// vehicle was last seen — so they were never required to match.
    static let maximumBaseline: TimeInterval = 180

    static func evaluate(from anchor: LocationFix, to fix: LocationFix) -> MovementEvidenceOutcome {
        let baseline = fix.timestamp.timeIntervalSince(anchor.timestamp)
        if baseline > maximumBaseline {
            return .rejected(.intervalTooLong)
        }
        // Covers a non-positive baseline too: two fixes at the same instant, or a clock
        // that stepped backwards, decide nothing and must not divide.
        guard baseline >= minimumBaseline else { return .inconclusive }

        let displacement = GeoDistance.meters(from: anchor, to: fix)
        let combinedVariance = anchor.horizontalAccuracy.squared + fix.horizontalAccuracy.squared
        let noiseFloor = noiseFloorSigmas * combinedVariance.squareRoot()
        guard displacement >= noiseFloor else { return .rejected(.accuracyTooCoarse) }

        guard displacement / baseline >= DrivingConfirmationPolicy.movingSpeedThreshold else {
            return .rejected(.distanceTooShort)
        }
        return .moving
    }
}

private extension Double {
    var squared: Double {
        self * self
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
    ///
    /// 300s is the cross-platform value fixed in docs/05 §7. It was 180 here and 300 on
    /// Android, and the longer one won on the measured subway trip: Core Motion edges ran
    /// minutes apart there, so 180 is tight. Missing a trip costs the whole recording,
    /// while opening one too early costs a single timeout window.
    static let vehicleEvidenceMaxAge: TimeInterval = 300

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

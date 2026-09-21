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
    /// The normalized `vehicle_exit` edge (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2).
    /// Distinct from `walkingDetected` because Android delivers a real IN_VEHICLE EXIT
    /// while iOS derives one, and a fixture only ever carries this one.
    case vehicleExit
    /// docs/05 §3a "The car link": the projection or the car's Bluetooth audio went away.
    /// The only end reason that reaches `CANDIDATE_PENDING` without passing through
    /// `PARKING_TRANSITION`.
    case carLinkDisconnected
    /// No further vehicle evidence within `DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout`.
    case vehicleEvidenceExpired
    /// docs/05 §3a `DRIVING → PARKING_TRANSITION`: movement evidence went quiet for
    /// `ParkingTransitionPolicy.movementIdleWindow`. Only a confirmed session can end this
    /// way — a session that has never moved is what `vehicleEvidenceExpired` is for.
    case movementIdle
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
    /// Metres accumulated for §7's `distance >= 800m` clause, one anchored leg at a time.
    /// Outliers never land here, and neither does jitter: see `accumulateDistance(to:)`.
    private(set) var distanceMeters: Double = 0
    /// Legs the noise floor kept out of `distanceMeters`.
    ///
    /// Instrumented rather than silently dropped. A session that accumulates nothing
    /// underground and one that never moved look identical from the outside, and this is
    /// the field that separates them: a count far above the accepted legs says the floor
    /// is wrong for this device, not that the car stood still.
    private(set) var distanceNoiseFloorRejectCount: Int = 0
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
    /// The fix `distanceMeters` measures its next leg from. A second anchor rather than a
    /// reuse of `movementAnchor`, because §7 asks the two clauses different questions:
    /// distance has no baseline bounds and no speed gate, only the noise floor they share.
    /// In memory only, exactly like `lastFix`.
    private var distanceAnchor: LocationFix?
    /// When the guard in `DrivingConfirmationPolicy` fired for this session. Latched so
    /// confirmation is reported — and checkpointed — exactly once.
    private(set) var confirmedAt: Date?
    /// The newest fix that counted as movement, by either route.
    ///
    /// Not derivable from `lastFix`: that advances on every accepted fix, including the
    /// hundred a car makes while standing in a car park. docs/05 §3a asks how long it has
    /// been since the device actually *moved*, and this is the only field that answers it.
    /// In memory only, exactly like `lastFix`.
    private(set) var lastMovingSampleAt: Date?

    init(startedAt: Date, lastVehicleEvidenceAt: Date? = nil) {
        self.startedAt = startedAt
        self.lastVehicleEvidenceAt = lastVehicleEvidenceAt
    }

    var isConfirmed: Bool {
        confirmedAt != nil
    }

    /// §7's guard, **evaluated** rather than latched.
    ///
    /// [isConfirmed] is a latch that `promoteToDriving` sets on §3a's 90-second bar, which
    /// is a weaker test than §7 and is set by a path §11's departure never takes. Reading
    /// the latch there meant a real departure never confirmed — found by the test that was
    /// supposed to prove it did.
    ///
    /// > recent vehicle evidence AND (duration >=120s OR distance >=800m) AND movement
    /// > evidence consistent with travel.
    func meetsDrivingConfirmation(now: Date) -> Bool {
        guard let lastVehicle = lastVehicleEvidenceAt,
              now.timeIntervalSince(lastVehicle) <= DrivingConfirmationPolicy.vehicleEvidenceMaxAge
        else { return false }
        let longEnough = duration(now: now) >= DrivingConfirmationPolicy.minimumDuration
        let farEnough = distanceMeters >= DrivingConfirmationPolicy.minimumDistance
        guard longEnough || farEnough else { return false }
        return movingSampleCount >= MovementEvidencePolicy.minimumMovingSamples
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
        }
        lastFix = fix

        accumulateDistance(to: fix)
        recordMovementEvidence(from: fix)
        return true
    }

    /// docs/05 §7 `distance >= 800m`, under the same noise floor as movement evidence.
    ///
    /// A leg is added only when its displacement clears the combined positional
    /// uncertainty of the two fixes. The §5 outlier cap alone is no gate at all on a
    /// coarse fix: two fixes accurate to 1000 m recorded 60 s and 900 m apart read as
    /// 15 m/s and pass it, and that is jitter, not travel.
    ///
    /// Failing keeps the anchor, exactly as the movement clause does, so slow travel still
    /// accumulates — one leg later, measured from further back. Clearing it advances the
    /// anchor, which is what stops the same metres being counted twice.
    ///
    /// The baseline bounds and the speed gate stay out of this deliberately. Those ask
    /// whether a leg looks like travel, which is the movement clause's question; this one
    /// only asks how far.
    private mutating func accumulateDistance(to fix: LocationFix) {
        guard let anchor = distanceAnchor else {
            // The first fix of a session has nothing to measure from: the one before it
            // belongs to the previous trip, and counting that gap would credit this drive
            // with the whole distance since the last parking spot.
            distanceAnchor = fix
            return
        }
        let displacement = GeoDistance.meters(from: anchor, to: fix)
        guard displacement >= MovementEvidencePolicy.noiseFloor(from: anchor, to: fix) else {
            distanceNoiseFloorRejectCount += 1
            return
        }
        distanceMeters += displacement
        distanceAnchor = fix
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
                lastMovingSampleAt = fix.timestamp
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
            lastMovingSampleAt = fix.timestamp
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

    /// docs/05 §7's "one event alone never confirms", applied to movement rather than to
    /// the state transition: a single fix can never establish that a device travelled.
    ///
    /// It used to gate `DrivingConfirmationPolicy.isConfirmed` and no longer does (§3a).
    /// It lives here because this is the policy it describes, and it is what the field
    /// trace replay measures a recorded drive against.
    static let minimumMovingSamples = 2

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

    /// The displacement a pair of fixes has to clear before it describes travel rather
    /// than noise.
    ///
    /// Shared with the `distance >= 800m` accumulation in `DrivingEvidence`: docs/05 §7
    /// puts both clauses behind one floor, so there is one definition of it.
    static func noiseFloor(from anchor: LocationFix, to fix: LocationFix) -> Double {
        let combinedVariance = anchor.horizontalAccuracy.squared + fix.horizontalAccuracy.squared
        return noiseFloorSigmas * combinedVariance.squareRoot()
    }

    static func evaluate(from anchor: LocationFix, to fix: LocationFix) -> MovementEvidenceOutcome {
        let baseline = fix.timestamp.timeIntervalSince(anchor.timestamp)
        if baseline > maximumBaseline {
            return .rejected(.intervalTooLong)
        }
        // Covers a non-positive baseline too: two fixes at the same instant, or a clock
        // that stepped backwards, decide nothing and must not divide.
        guard baseline >= minimumBaseline else { return .inconclusive }

        let displacement = GeoDistance.meters(from: anchor, to: fix)
        guard displacement >= noiseFloor(from: anchor, to: fix) else {
            return .rejected(.accuracyTooCoarse)
        }

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

    /// docs/05 §3a `minimumVehicleDuration`: how long vehicle activity has to be
    /// sustained before `DRIVING_CANDIDATE` is promoted to `DRIVING`.
    ///
    /// 90s, matching the bar §11 uses for departure, so one direction cannot be laxer than
    /// the other.
    static let minimumVehicleDuration: TimeInterval = 90

    /// docs/05 §3a `drivingCandidateWindow`: how long `DRIVING_CANDIDATE` may wait for a
    /// promotion before it gives up and returns to `IDLE`.
    ///
    /// 300 s, which §3a takes from §7's `vehicleEvidenceMaxAge` — evidence older than that
    /// is already not counted. It is deliberately longer than `minimumVehicleDuration`, so
    /// on a wake where both windows have elapsed the promotion is the one that became true
    /// first and the engine reads it first.
    static let drivingCandidateWindow: TimeInterval = 300

    /// docs/05 §7's trip minimums. **No longer the promotion gate** — see `isConfirmed` —
    /// but still what decides the `vehicle_duration_met` / `vehicle_distance_met` reason
    /// codes and §8's "comfortably over minimum" weight.
    static let minimumDuration: TimeInterval = 120
    static let minimumDistance: Double = 800

    /// ~7.2 km/h — above brisk walking, below any real traffic speed.
    static let movingSpeedThreshold: Double = 2.0

    /// docs/05 §3a: sustained vehicle activity, and nothing else.
    ///
    /// ### Movement evidence deliberately does not gate this
    /// An earlier reading required movement evidence as well. Replaying the three drives
    /// recorded on 2026-09-19 against it found the defect: the 14:26 trip is the textbook
    /// signature — `vehicle_enter`, `vehicle_exit` seven minutes later, `walking_enter` —
    /// and it carries **zero location events**. Gated on movement it never leaves
    /// `DRIVING_CANDIDATE`, and the parking is never detected at all.
    ///
    /// That is not an edge case: §13 and the notes around §7 put underground car parks,
    /// tunnels and urban canyons at the centre of this product, and those are exactly the
    /// places GPS Doppler speed never arrives. Movement evidence still matters — it is what
    /// §8 weighs through distance, and what separates a real trip from a phone on a desk —
    /// but it belongs in the confidence bucket, not in the transition. A drive with no
    /// fixes reaches `CANDIDATE_PENDING` with lower confidence; it is never made invisible.
    ///
    /// **This is time-based, so it cannot be evaluated only when a fix arrives.** A drive
    /// that produces no fixes produces no call sites either, which is why the coordinator
    /// also asks on every wake.
    static func isConfirmed(_ evidence: DrivingEvidence, now: Date) -> Bool {
        guard let vehicleAt = evidence.lastVehicleEvidenceAt,
              now.timeIntervalSince(vehicleAt) <= vehicleEvidenceMaxAge
        else { return false }
        return evidence.duration(now: now) >= minimumVehicleDuration
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
    ///
    /// Both halves, for the one caller that needs both: recreating a session from a
    /// checkpoint written by a process that has since died.
    static func expiryReason(for evidence: DrivingEvidence, now: Date) -> DrivingSessionEndReason? {
        boundReason(for: evidence, now: now) ?? silenceReason(for: evidence, now: now)
    }

    /// The bounds `ParkingDetectionEngine` owns, because both are rules about the drive
    /// itself rather than about how a platform delivers evidence.
    static func boundReason(for evidence: DrivingEvidence, now: Date) -> DrivingSessionEndReason? {
        if evidence.duration(now: now) >= maximumDuration {
            return .maximumDurationReached
        }
        // docs/05 §3a. Deliberately gated on a *confirmed* session that has already moved:
        // before confirmation there is no movement to have stopped, and treating silence
        // there as a parking transition would open a candidate for a car nobody drove.
        if evidence.isConfirmed,
           ParkingTransitionPolicy.isMovementIdle(lastMovingSampleAt: evidence.lastMovingSampleAt, now: now) {
            return .movementIdle
        }
        return nil
    }

    /// The **adapter's** bound, and the engine deliberately does not apply it.
    ///
    /// iOS polls Core Motion history, so the absence of automotive evidence is the only
    /// way an exit edge ever arrives; `BackgroundCoordinator` turns that silence into the
    /// normalized `vehicle_exit` the contract's §2 vocabulary has. Android gets a real
    /// IN_VEHICLE EXIT and needs nothing here, and a fixture carries its exit explicitly —
    /// `subway_commute_underground.json` has 45 minutes between its `vehicle_enter` and
    /// its `vehicle_exit`, and an engine that expired vehicle evidence on its own clock
    /// could never replay it.
    static func silenceReason(for evidence: DrivingEvidence, now: Date) -> DrivingSessionEndReason? {
        // Before any vehicle observation lands, the session start is the anchor —
        // otherwise a session opened on a stale signal would never time out.
        let anchor = evidence.lastVehicleEvidenceAt ?? evidence.startedAt
        guard now.timeIntervalSince(anchor) >= vehicleEvidenceTimeout else { return nil }
        return .vehicleEvidenceExpired
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

    /// Stationary that starts *after* `reference` — docs/05 §8 "stationary after driving",
    /// and §3a's second way into `CANDIDATE_PENDING`.
    ///
    /// `!automotive` matters more here than anywhere else: Core Motion reports `automotive`
    /// and `stationary` together at a red light (see `MotionSample`), and a sample that
    /// still says "in a vehicle" is the light, not the car park. The caller has a second
    /// guard on top — vehicle evidence arriving after the transition began sends the state
    /// back to `DRIVING` before this is ever consulted.
    static func latestStationaryEvidence(in samples: [MotionSample], after reference: Date) -> MotionSample? {
        samples
            .filter { $0.stationary && !$0.automotive && $0.timestamp > reference }
            .max { $0.timestamp < $1.timestamp }
    }
}

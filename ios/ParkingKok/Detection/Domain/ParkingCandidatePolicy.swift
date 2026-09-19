import Foundation

/// What the finished drive and the transition after it actually showed, in the vocabulary
/// docs/05_PARKING_DETECTION_ENGINE.md §8 weighs.
///
/// A pure value with no clock and no platform in it, so the whole of the §6/§8/§9 decision
/// is testable without a coordinator — the same reason `DrivingEvidence` and
/// `ReliableLocationPolicy` are values.
///
/// ### The evidence that is deliberately not here
/// §8 also weighs `vehicle resumes quickly`, `movement continues` and `short stop
/// pattern`. None of the three can be true at the moment this runs, because §3a spends
/// them on *edges* rather than on the score: vehicle evidence returning or movement
/// resuming inside `transitionWindow` sends `PARKING_TRANSITION` back to `DRIVING`, and a
/// session that never earned `DRIVING` leaves through `DRIVING_CANDIDATE → IDLE`. A
/// candidate scored here has already survived all three, so fields for them would be
/// constants — and a weight that can never apply is worse than no weight, because it reads
/// as if the engine considered something it did not.
struct ParkingEvidence: Sendable, Equatable {
    /// A bounded driving session was confirmed under §7 before it ended. This is §6's
    /// "evidence of recent meaningful vehicle session".
    var hasMeaningfulVehicleSession = false
    /// The vehicle session ended — §6's second clause.
    var vehicleEnded = false
    /// Walking observed after the vehicle evidence. The strongest single signal §8 has.
    var walkingAfterVehicle = false
    var stationaryAfterVehicle = false
    /// Movement evidence went quiet for `ParkingTransitionPolicy.movementIdleWindow`
    /// before the session ended.
    var locationStopped = false
    /// The fix quality collapsed towards the end of the drive — the underground pattern
    /// in §13. **Supporting evidence only**: §6 says it can never satisfy the rule alone,
    /// which is why it is absent from `confirmationSignals`.
    var gpsQualityDegraded = false
    /// A trusted car projection (CarPlay) disconnected. Always `false` in this build —
    /// nothing observes CarPlay yet — and modelled anyway because §8 gives it a weight and
    /// Android's engine has the same field.
    var carProjectionDisconnected = false
    /// A reliable point was captured for this drive.
    var reliableLocationCaptured = false
    /// §8's "route duration/distance comfortably over minimum".
    var driveDuration: TimeInterval?
    var driveDistanceMeters: Double?

    /// §6: at least one of walking / stationary / location stop / projection disconnect.
    /// GPS degradation is not on this list by construction.
    var confirmationSignals: Int {
        [walkingAfterVehicle, stationaryAfterVehicle, locationStopped, carProjectionDisconnected]
            .filter(\.self)
            .count
    }
}

/// The §6 rule, the §8 weights and the §9 buckets, in one place.
///
/// Every number here is a **field-tuning starting point** in the sense §8 and §18 mean:
/// they are the documented defaults, not derived truth, and neither platform may pick its
/// own. Changing one is a `DetectorVersion` bump (docs/17 §4).
enum ParkingCandidatePolicy {
    // ── §8 positive weights ─────────────────────────────────────────────────
    static let meaningfulVehicleSessionWeight = 25
    static let vehicleEndedWeight = 15
    static let walkingAfterVehicleWeight = 30
    static let stationaryAfterVehicleWeight = 10
    static let locationStoppedWeight = 10
    static let gpsQualityDegradedWeight = 5
    static let carProjectionDisconnectedWeight = 20
    static let comfortablyOverMinimumWeight = 5

    // ── §9 buckets ──────────────────────────────────────────────────────────
    static let highMinimumScore = 80
    static let mediumMinimumScore = 60

    /// §8's "comfortably over minimum" made concrete: twice the §7 confirmation bar, on
    /// either clause. §7 already accepts duration *or* distance, so this does too.
    static let comfortableMultiplier: Double = 2

    /// docs/05 §10. The lifetime a candidate is given when it is created.
    static let expiry: TimeInterval = 45 * 60

    /// The candidate this evidence justifies, or `nil` when §6's rule is not met.
    ///
    /// `nil` is not a failure and is not reported: it is the ordinary outcome of a
    /// transition window that closed without a confirming signal, and §3a routes that
    /// straight back to `IDLE`.
    static func evaluate(
        _ evidence: ParkingEvidence,
        id: UUID,
        detectedAt: Date,
        lastReliableLocation: LastReliableLocation?,
        accuracyBucket: LocationAccuracyBucket?
    ) -> ParkingCandidate? {
        guard satisfiesCandidateRule(evidence) else { return nil }

        let score = score(for: evidence)
        return ParkingCandidate(
            id: id,
            detectedAt: detectedAt,
            confidenceBucket: bucket(for: score),
            reasonCodes: reasonCodes(for: evidence, lastReliableLocation: lastReliableLocation),
            lastReliableLocation: lastReliableLocation,
            expiresAt: detectedAt.addingTimeInterval(expiry),
            score: score,
            driveDuration: evidence.driveDuration,
            driveDistanceMeters: evidence.driveDistanceMeters,
            accuracyBucket: accuracyBucket
        )
    }

    /// docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §6, verbatim: a meaningful vehicle
    /// session, evidence it ended, and at least one confirmation signal.
    static func satisfiesCandidateRule(_ evidence: ParkingEvidence) -> Bool {
        evidence.hasMeaningfulVehicleSession
            && evidence.vehicleEnded
            && evidence.confirmationSignals > 0
    }

    /// §8. Clamped at zero because a negative score has no bucket and would only ever be
    /// read as "very low", which `low` already says.
    static func score(for evidence: ParkingEvidence) -> Int {
        var total = 0
        if evidence.hasMeaningfulVehicleSession {
            total += meaningfulVehicleSessionWeight
        }
        if evidence.vehicleEnded {
            total += vehicleEndedWeight
        }
        if evidence.walkingAfterVehicle {
            total += walkingAfterVehicleWeight
        }
        if evidence.stationaryAfterVehicle {
            total += stationaryAfterVehicleWeight
        }
        if evidence.locationStopped {
            total += locationStoppedWeight
        }
        if evidence.gpsQualityDegraded {
            total += gpsQualityDegradedWeight
        }
        if evidence.carProjectionDisconnected {
            total += carProjectionDisconnectedWeight
        }
        if isComfortablyOverMinimum(evidence) {
            total += comfortablyOverMinimumWeight
        }
        return max(0, total)
    }

    /// §9: high ≥ 80, medium 60…79, low below.
    static func bucket(for score: Int) -> ConfidenceBucket {
        switch score {
        case highMinimumScore...: .high
        case mediumMinimumScore...: .medium
        default: .low
        }
    }

    /// §3a: "codes accumulate as evidence arrives and travel with the candidate; they are
    /// never recomputed at the end from the final state." They are derived here from the
    /// evidence *flags*, which is the accumulated record — not from the score, and not
    /// from the bucket.
    ///
    /// Emitted in `CandidateReasonCode`'s own order so the list is stable.
    static func reasonCodes(
        for evidence: ParkingEvidence,
        lastReliableLocation: LastReliableLocation?
    ) -> [CandidateReasonCode] {
        var codes: [CandidateReasonCode] = []
        if evidence.hasMeaningfulVehicleSession {
            codes.append(.recentVehicleActivity)
        }
        if let duration = evidence.driveDuration,
           duration >= DrivingConfirmationPolicy.minimumDuration {
            codes.append(.vehicleDurationMet)
        }
        if let distance = evidence.driveDistanceMeters,
           distance >= DrivingConfirmationPolicy.minimumDistance {
            codes.append(.vehicleDistanceMet)
        }
        if evidence.vehicleEnded {
            codes.append(.vehicleExitDetected)
        }
        if evidence.walkingAfterVehicle {
            codes.append(.walkingAfterVehicle)
        }
        if evidence.stationaryAfterVehicle {
            codes.append(.stationaryAfterVehicle)
        }
        if evidence.locationStopped {
            codes.append(.locationStopped)
        }
        if evidence.gpsQualityDegraded {
            codes.append(.locationQualityDegraded)
        }
        if lastReliableLocation != nil || evidence.reliableLocationCaptured {
            codes.append(.reliableLocationCaptured)
        }
        if evidence.carProjectionDisconnected {
            codes.append(.carProjectionDisconnected)
        }
        return codes
    }

    private static func isComfortablyOverMinimum(_ evidence: ParkingEvidence) -> Bool {
        if let duration = evidence.driveDuration,
           duration >= DrivingConfirmationPolicy.minimumDuration * comfortableMultiplier {
            return true
        }
        if let distance = evidence.driveDistanceMeters,
           distance >= DrivingConfirmationPolicy.minimumDistance * comfortableMultiplier {
            return true
        }
        return false
    }
}

/// The `PARKING_TRANSITION` half of the §3a table — the state that is still deciding.
///
/// Its windows are evaluated **lazily**, on the next wake, exactly like every other
/// boundary in this engine: docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 puts the cost of
/// a boundary at zero, and a timer that fires 300 seconds after a drive is a wake the
/// product has not earned. The cost is that the state can be observed a little late; the
/// candidate it produces is stamped with the evidence's own time, not the wake's.
enum ParkingTransitionPolicy {
    /// §3a. How long `PARKING_TRANSITION` waits for a confirming signal before giving up.
    /// **unvalidated** — reused from §7's vehicle window so a walk that starts late still
    /// counts.
    static let transitionWindow: TimeInterval = 300

    /// §3a. No movement evidence for this long inside `DRIVING` is itself a reason to
    /// start deciding, even if Core Motion never reports the exit.
    /// **unvalidated** — `MovementEvidencePolicy.maximumBaseline`, which is the longest
    /// interval an average speed still describes.
    static let movementIdleWindow: TimeInterval = MovementEvidencePolicy.maximumBaseline

    /// Whether the window has closed on a transition entered at `enteredAt`.
    static func hasElapsed(enteredAt: Date, now: Date) -> Bool {
        now.timeIntervalSince(enteredAt) >= transitionWindow
    }

    /// Whether movement has been quiet long enough to stop calling this a drive.
    ///
    /// `lastMovingSampleAt` is `nil` until the session has seen movement at all, and that
    /// case is deliberately not idle: a session that has not yet moved is what
    /// `DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout` already governs, and treating
    /// it as a parking transition would open a candidate for a car that never left.
    static func isMovementIdle(lastMovingSampleAt: Date?, now: Date) -> Bool {
        guard let lastMovingSampleAt else { return false }
        return now.timeIntervalSince(lastMovingSampleAt) >= movementIdleWindow
    }
}

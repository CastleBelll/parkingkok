import Foundation

/// The stable evidence vocabulary shared with Android, the fixtures and analytics
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §4).
///
/// **The list is closed** (docs/05_PARKING_DETECTION_ENGINE.md §3a): an engine that needs
/// a code which is not here has found a gap in the contract, and the answer is to raise it
/// rather than to add a string. Raw values are the contract strings so a candidate written
/// by this build stays comparable with one written by Android.
enum CandidateReasonCode: String, Sendable, Equatable, Codable, CaseIterable {
    case recentVehicleActivity = "recent_vehicle_activity"
    case vehicleDurationMet = "vehicle_duration_met"
    case vehicleDistanceMet = "vehicle_distance_met"
    case vehicleExitDetected = "vehicle_exit_detected"
    case walkingAfterVehicle = "walking_after_vehicle"
    case stationaryAfterVehicle = "stationary_after_vehicle"
    case locationStopped = "location_stopped"
    case locationQualityDegraded = "location_quality_degraded"
    case reliableLocationCaptured = "reliable_location_captured"
    case carProjectionDisconnected = "car_projection_disconnected"
    case candidateTimeout = "candidate_timeout"
}

/// FR-002's `ParkingCandidate` — the guess the app puts to the user, and the only thing
/// the confirmation screen and the notification are built from.
///
/// ### Why it is a separate record from `ParkingSession`
/// A candidate is not a parking. It expires on its own (§10), it is created by a process
/// that may have been launched by Core Location with no UI at all, and it must survive
/// process death. `ParkingSession` lives in SwiftData behind the main actor; opening that
/// container on a background wake is exactly the work docs/04 §7 forbids there. So this is
/// a value written to a JSON file beside the detection checkpoint — see
/// `FileParkingCandidateStore`.
///
/// ### What it may carry
/// `lastReliableLocation` is local-only and never leaves the device (CLAUDE.md Hard
/// Constraints). Everything else is a time, a bucket or a reason code — which is what lets
/// `analyticsProperties` exist at all without a coordinate having anywhere to go.
///
/// `platform` from FR-002 is not a stored field: this file is only ever written by iOS,
/// and `AnalyticsPayload` stamps `platform` on every event it sends.
struct ParkingCandidate: Sendable, Equatable, Codable, Identifiable {
    let id: UUID
    /// When the engine decided — the time the confirmation screen shows as "오후 8:14".
    let detectedAt: Date
    let confidenceBucket: ConfidenceBucket
    /// In the fixed order of `CandidateReasonCode`, so two runs of the same evidence
    /// produce the same list and a fixture can state it.
    let reasonCodes: [CandidateReasonCode]
    /// The point the parking record inherits on confirmation. `nil` when the drive
    /// produced no fix good enough to keep (§6) — the candidate is still real, and the
    /// record it becomes simply has no location, exactly like a manual save without
    /// permission.
    let lastReliableLocation: LastReliableLocation?
    let expiresAt: Date
    /// The §8 internal score. Kept because §5 of the domain contract allows an internal
    /// integer and because a bucket alone cannot explain a field result; it is never
    /// reported (docs/17 §3 has no property for it).
    let score: Int
    /// The finished drive, for the §3 analytics buckets only. Never a route.
    let driveDuration: TimeInterval?
    let driveDistanceMeters: Double?
    /// Quality of the fix this candidate is anchored on, for the §3 `accuracyBucket`.
    let accuracyBucket: LocationAccuracyBucket?

    /// docs/05 §10. A candidate that reaches this is withdrawn and creates nothing.
    func isExpired(now: Date) -> Bool {
        now >= expiresAt
    }

    /// Whether the user is told about it at all (§9 MVP: high/medium notify, low does not).
    ///
    /// A `low` candidate is still recorded, so the app can show it when opened and the
    /// trace keeps the evidence (§10a "Posting").
    var isNotifiable: Bool {
        confidenceBucket != .low
    }

    /// Everything docs/17 §3 permits this event to say.
    ///
    /// The §4 reason codes travel as the three booleans §3 defines for them —
    /// `walking_after_vehicle` → `walkingEvidence`, `location_quality_degraded` →
    /// `gpsDegradation`, `car_projection_disconnected` → `optionalVehicleSignal`. They are
    /// not sent as a fourth, free-form property: §3's list is the allowlist, and
    /// `AnalyticsPayload` has no key to put one in, which is the point of that design.
    var analyticsProperties: DetectionProperties {
        DetectionProperties(
            confidenceBucket: confidenceBucket,
            driveDurationBucket: driveDuration.flatMap(DriveDurationBucket.init(seconds:)),
            distanceBucket: driveDistanceMeters.flatMap(DistanceBucket.init(meters:)),
            accuracyBucket: accuracyBucket,
            walkingEvidence: reasonCodes.contains(.walkingAfterVehicle),
            gpsDegradation: reasonCodes.contains(.locationQualityDegraded),
            optionalVehicleSignal: reasonCodes.contains(.carProjectionDisconnected)
        )
    }
}

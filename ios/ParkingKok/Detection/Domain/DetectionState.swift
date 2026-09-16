import Foundation

/// The shared detection state vocabulary
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §3).
///
/// Raw values are the contract strings so a checkpoint written by this build stays
/// readable — and comparable against the Android implementation — without a mapping
/// table. The transition rules that move between these states are M0A-2; this
/// milestone only needs to persist and restore the value.
///
/// `PARKING_TRANSITION` and `CANDIDATE_PENDING` are separate states because they behave
/// differently: the first waits for a confirmation signal with nothing shown to the user,
/// the second has already persisted a candidate and raised a notification and is counting
/// down its 45-minute expiry. The contract briefly collapsed them into one
/// `PARKING_CANDIDATE`; the product flow and the engine spec never did.
enum DetectionState: String, Sendable, Codable, CaseIterable {
    case idle = "IDLE"
    case drivingCandidate = "DRIVING_CANDIDATE"
    case driving = "DRIVING"
    case parkingTransition = "PARKING_TRANSITION"
    case candidatePending = "CANDIDATE_PENDING"
    case parked = "PARKED"
    case departureCandidate = "DEPARTURE_CANDIDATE"
}

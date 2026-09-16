import Foundation

/// The shared detection state vocabulary
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §3).
///
/// Raw values are the contract strings so a checkpoint written by this build stays
/// readable — and comparable against the Android implementation — without a mapping
/// table. The transition rules that move between these states are M0A-2; this
/// milestone only needs to persist and restore the value.
///
/// Note: `docs/05_PARKING_DETECTION_ENGINE.md` §3 lists `PARKING_TRANSITION` and
/// `CANDIDATE_PENDING` where the cross-platform contract lists `PARKING_CANDIDATE`.
/// CLAUDE.md ranks the contract above the engine spec, so the contract wins here.
enum DetectionState: String, Sendable, Codable, CaseIterable {
    case idle = "IDLE"
    case drivingCandidate = "DRIVING_CANDIDATE"
    case driving = "DRIVING"
    case parkingCandidate = "PARKING_CANDIDATE"
    case parked = "PARKED"
    case departureCandidate = "DEPARTURE_CANDIDATE"
}

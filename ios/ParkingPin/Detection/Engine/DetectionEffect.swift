import Foundation

/// The platform-independent effects from `docs/05_PARKING_DETECTION_ENGINE.md` §15.
///
/// The engine decides; the adapter performs. That split is what makes the §3a table
/// testable without Core Location, a notification centre or a file system, and it is what
/// lets `platform-tests/*.json` be replayed through the very code the device runs.
///
/// Effects are returned in the order they must happen. §10a fixes one of those orders and
/// the permission-denied path rests on it: the candidate is written before it is
/// announced, so a denied notification cannot cost the user the candidate.
enum DetectionEffect: Sendable, Equatable {
    /// docs/04 §3: open the bounded driving session.
    case startBoundedLocationCapture
    /// docs/04 §3 "Stop aggressive tracking".
    case stopLocationCapture
    /// docs/05 §14. Always the newest value; the adapter does not merge.
    case persistCheckpoint(DetectionCheckpoint)
    case drivingConfirmed(at: Date)
    case sessionEnded(reason: DrivingSessionEndReason, at: Date)
    /// Write it to the candidate store. Always emitted before the notification.
    case createCandidate(ParkingCandidate)
    /// §9: only for a candidate the user is actually told about. A `low` one is created
    /// and never announced.
    case issueCandidateNotification(ParkingCandidate)
    /// Retire a candidate: clear the store and withdraw its notification.
    case withdrawCandidate(id: UUID)
    /// §6's rule was not met by a transition that closed. An ordinary outcome, surfaced so
    /// a rising count with no candidates is visible in diagnostics rather than silent.
    case candidateRuleUnmet

    /// §11 departure, confirmed: close the open parking record.
    ///
    /// `at` is when the car **started moving**, not when the engine finished deciding.
    /// `DEPARTURE_CANDIDATE → DRIVING` is guarded by §7's confirmation in full, which needs
    /// minutes of real driving, so stamping "now" would put the end of the parking somewhere
    /// down the road. The value is the moment §11's two bars were first cleared.
    ///
    /// Only the confirmed transition emits it. §11's "if uncertain → suggestion, not
    /// destructive silent end" is honoured by the state below: reaching
    /// `DEPARTURE_CANDIDATE` and never confirming ends nothing and says nothing.
    case endActiveParking(at: Date)
}

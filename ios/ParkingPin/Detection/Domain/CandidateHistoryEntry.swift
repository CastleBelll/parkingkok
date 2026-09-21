import Foundation

/// One resolved candidate, as docs/10 §7b's bell lists it.
///
/// ### What it may carry, and what it must not
/// docs/05 §10a fixes the three fields exactly: "An entry keeps the raised-at time, the
/// outcome, and for a confirmed one the record id. Not the location." There is no
/// coordinate, no address and no floor here — the floor on a `저장됨` row is read from the
/// parking record `recordId` points at, which is the only copy of it that exists.
///
/// That is not merely tidiness: §7b's list is the same surface a day later, and docs/09
/// keeps location off the notification it mirrors.
struct CandidateHistoryEntry: Sendable, Equatable, Codable, Identifiable {
    /// The `candidateId`. Also the dedup key — see `CandidateHistoryStoring.append`.
    let id: UUID
    /// When the engine raised the guess, not when it was answered. §7b's right-hand
    /// column shows the moment the phone buzzed, which is what the user is recognising.
    let raisedAt: Date
    let outcome: CandidateOutcome
    /// The `ParkingSession` a confirmation became, so the row can open it and show the
    /// floor. `nil` for every other outcome.
    let recordId: UUID?

    init(id: UUID, raisedAt: Date, outcome: CandidateOutcome, recordId: UUID? = nil) {
        self.id = id
        self.raisedAt = raisedAt
        self.outcome = outcome
        // A record id on a rejection or an expiry would be a record that was never
        // written; the type refuses to hold one rather than leaving it to each call site.
        self.recordId = outcome == .confirmed ? recordId : nil
    }

    /// The entry a resolved candidate becomes.
    init(candidate: ParkingCandidate, outcome: CandidateOutcome, recordId: UUID? = nil) {
        self.init(id: candidate.id, raisedAt: candidate.detectedAt, outcome: outcome, recordId: recordId)
    }
}

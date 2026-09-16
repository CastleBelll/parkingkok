import Foundation

/// The durable detection state that has to survive process death
/// (docs/04_IOS_IMPLEMENTATION.md §6, docs/00_CORE_RULES.md Background).
///
/// Field list is verbatim from docs/04 §6. `lastReliableLocation` is declared here but
/// stays `nil` through M0A-1 — see `LastReliableLocation`.
struct DetectionCheckpoint: Sendable, Equatable, Codable {
    var state: DetectionState
    var stateEnteredAt: Date
    var lastAutomotiveAt: Date?
    var lastReliableLocation: LastReliableLocation?
    var lastLocationAt: Date?
    /// Metres travelled in the current vehicle session.
    var travelDistanceEstimate: Double
    var candidateId: UUID?
    /// Bumped on every write so a widget or a later process can detect staleness
    /// (docs/04 §13 uses the same idea for the shared snapshot).
    var revision: Int

    init(
        state: DetectionState,
        stateEnteredAt: Date,
        lastAutomotiveAt: Date? = nil,
        lastReliableLocation: LastReliableLocation? = nil,
        lastLocationAt: Date? = nil,
        travelDistanceEstimate: Double = 0,
        candidateId: UUID? = nil,
        revision: Int = 0
    ) {
        self.state = state
        self.stateEnteredAt = stateEnteredAt
        self.lastAutomotiveAt = lastAutomotiveAt
        self.lastReliableLocation = lastReliableLocation
        self.lastLocationAt = lastLocationAt
        self.travelDistanceEstimate = travelDistanceEstimate
        self.candidateId = candidateId
        self.revision = revision
    }

    /// The first checkpoint a fresh install writes.
    static func initial(at date: Date) -> DetectionCheckpoint {
        DetectionCheckpoint(state: .idle, stateEnteredAt: date)
    }

    /// Newest timestamp the checkpoint knows about — the anchor for motion replay
    /// (docs/04 §6 step 3 calls this "checkpoint.time").
    var latestTimestamp: Date {
        [lastLocationAt, lastAutomotiveAt, lastReliableLocation?.capturedAt]
            .compactMap(\.self)
            .reduce(stateEnteredAt, max)
    }

    func age(now: Date) -> TimeInterval {
        now.timeIntervalSince(latestTimestamp)
    }
}

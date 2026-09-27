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
    /// docs/05 §14 "A restored departure keeps its evidence" (2026-09-28): the get-in session
    /// `PARKED` measures §11's bars on, and the departure `DEPARTURE_CANDIDATE` asks §7's
    /// guard of. `nil` in every other state, and in any checkpoint written before schema 2.
    ///
    /// On-device only, like `lastReliableLocation`: it holds the session's anchor fixes, and
    /// `DiagnosticsReport` flattens the checkpoint precisely so they never leave the device.
    var departure: DepartureCheckpoint?
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
        departure: DepartureCheckpoint? = nil,
        revision: Int = 0
    ) {
        self.state = state
        self.stateEnteredAt = stateEnteredAt
        self.lastAutomotiveAt = lastAutomotiveAt
        self.lastReliableLocation = lastReliableLocation
        self.lastLocationAt = lastLocationAt
        self.travelDistanceEstimate = travelDistanceEstimate
        self.candidateId = candidateId
        self.departure = departure
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

/// What a process death must not take from a departure (docs/05 §14): everything §11's bars
/// and §7's guard read — the session's start, distance and its anchor, moving samples, the
/// last vehicle evidence (a car-link edge included, §11b) — and the vehicle-activity level
/// §11's 90 s bar is measured from. Android's runtime reloads the same things with its whole
/// engine state.
struct DepartureCheckpoint: Sendable, Equatable, Codable {
    var drive: DrivingEvidence
    /// When the current stretch of vehicle activity began; `nil` when none is active.
    var vehicleActiveSince: Date?
}

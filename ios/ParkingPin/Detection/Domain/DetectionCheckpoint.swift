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
    /// The rest of the engine's state (docs/05 §14 "Both platforms persist and reload their
    /// whole engine state", schema 3): with the fields above it is everything the engine
    /// holds, written whenever any of it changes and restored verbatim. `nil` only in a
    /// checkpoint written before schema 3, which `ParkingDetectionEngine.restore` migrates.
    var engine: DetectionEngineRecord?
    /// Schema 2's departure record, read from such a checkpoint and never written again — the
    /// one input of the pre-schema-3 migration it did not have as a field of its own.
    var legacyDeparture: DepartureCheckpoint?
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
        engine: DetectionEngineRecord? = nil,
        legacyDeparture: DepartureCheckpoint? = nil,
        revision: Int = 0
    ) {
        self.state = state
        self.stateEnteredAt = stateEnteredAt
        self.lastAutomotiveAt = lastAutomotiveAt
        self.lastReliableLocation = lastReliableLocation
        self.lastLocationAt = lastLocationAt
        self.travelDistanceEstimate = travelDistanceEstimate
        self.candidateId = candidateId
        self.engine = engine
        self.legacyDeparture = legacyDeparture
        self.revision = revision
    }

    /// The first checkpoint a fresh install writes: `IDLE`, with an empty engine state.
    static func initial(at date: Date) -> DetectionCheckpoint {
        DetectionCheckpoint(state: .idle, stateEnteredAt: date, engine: DetectionEngineRecord())
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

    private enum CodingKeys: String, CodingKey {
        case state, stateEnteredAt, lastAutomotiveAt, lastReliableLocation, lastLocationAt
        case travelDistanceEstimate, candidateId, engine, revision
        /// Schema 2's key, kept so a schema 2 file still decodes.
        case legacyDeparture = "departure"
    }
}

/// Schema 2's departure record (2026-09-28 to 2026-09-29): the session a `PARKED` get-in or a
/// `DEPARTURE_CANDIDATE` measured, and the drive of a session that had lost its capture.
/// Read only to migrate such a checkpoint (`ParkingDetectionEngine.restore`); schema 3
/// persists the whole engine state (`DetectionEngineRecord`) instead.
struct DepartureCheckpoint: Sendable, Equatable, Codable {
    var drive: DrivingEvidence
    /// When the current stretch of vehicle activity began; `nil` when none is active.
    var vehicleActiveSince: Date?
    /// docs/05 §11 "A lost capture decides nothing": the session lost its bounded capture and
    /// goes on without one, so a relaunch reopens none. Absent from a record written before
    /// the field existed, which is read as a session whose capture was running.
    var isCaptureLost: Bool

    init(drive: DrivingEvidence, vehicleActiveSince: Date?, isCaptureLost: Bool = false) {
        self.drive = drive
        self.vehicleActiveSince = vehicleActiveSince
        self.isCaptureLost = isCaptureLost
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        drive = try container.decode(DrivingEvidence.self, forKey: .drive)
        vehicleActiveSince = try container.decodeIfPresent(Date.self, forKey: .vehicleActiveSince)
        isCaptureLost = try container.decodeIfPresent(Bool.self, forKey: .isCaptureLost) ?? false
    }
}

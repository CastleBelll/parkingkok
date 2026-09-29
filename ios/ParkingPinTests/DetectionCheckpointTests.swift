import Foundation
import Testing
@testable import ParkingPin

@Suite("DetectionCheckpoint")
struct DetectionCheckpointTests {
    @Test("Round-trips every field through the file store")
    func roundTripsThroughStore() throws {
        // Arrange
        let file = TemporaryCheckpointFile()
        let store = FileDetectionCheckpointStore(fileURL: file.url)
        let checkpoint = DetectionCheckpoint(
            state: .parked,
            stateEnteredAt: TestTime.offset(0),
            lastAutomotiveAt: TestTime.offset(-300),
            lastReliableLocation: LastReliableLocation(
                latitude: 37.5,
                longitude: 127.0,
                horizontalAccuracy: 12.5,
                capturedAt: TestTime.offset(-60)
            ),
            lastLocationAt: TestTime.offset(-30),
            travelDistanceEstimate: 1234.5,
            candidateId: UUID(uuidString: "2C3E7F6A-0000-4000-8000-000000000001"),
            revision: 7
        )

        // Act
        try store.save(checkpoint)
        let result = store.load()

        // Assert
        #expect(result == .restored(checkpoint))
    }

    @Test("Reports absent rather than failing when nothing was ever written")
    func reportsAbsentWhenMissing() {
        // Arrange
        let file = TemporaryCheckpointFile()
        let store = FileDetectionCheckpointStore(fileURL: file.url)

        // Act / Assert
        #expect(store.load() == .absent)
    }

    @Test("Reports corruption instead of recovering silently")
    func reportsCorruption() throws {
        // Arrange
        let file = TemporaryCheckpointFile()
        try Data("not json at all".utf8).write(to: file.url)
        let store = FileDetectionCheckpointStore(fileURL: file.url)

        // Act
        let result = store.load()

        // Assert
        guard case let .failed(failure) = result else {
            Issue.record("expected a failure, got \(result)")
            return
        }
        guard case .corrupt = failure else {
            Issue.record("expected .corrupt, got \(failure)")
            return
        }
    }

    @Test("Rejects a payload written by a different schema version")
    func reportsSchemaMismatch() throws {
        // Arrange
        let file = TemporaryCheckpointFile()
        let foreign = FileDetectionCheckpointStore.schemaVersion + 1
        let payload = """
        {"schemaVersion":\(foreign),"checkpoint":{"state":"IDLE","stateEnteredAt":0,\
        "travelDistanceEstimate":0,"revision":0}}
        """
        try Data(payload.utf8).write(to: file.url)
        let store = FileDetectionCheckpointStore(fileURL: file.url)

        // Act
        let result = store.load()

        // Assert
        #expect(
            result == .failed(
                .schemaMismatch(found: foreign, expected: FileDetectionCheckpointStore.schemaVersion)
            )
        )
    }

    @Test("Round-trips the whole engine state through the file store")
    func roundTripsEngineState() throws {
        // Arrange — docs/05 §14: every part of the engine state, anchors included.
        let file = TemporaryCheckpointFile()
        let store = FileDetectionCheckpointStore(fileURL: file.url)
        var drive = DrivingEvidence(startedAt: TestTime.offset(0), lastVehicleEvidenceAt: TestTime.offset(0))
        for (seconds, north) in [(10.0, 0.0), (100.0, 600.0)] {
            drive.record(fix: LocationFix(
                timestamp: TestTime.offset(seconds),
                latitude: 37.5 + north / 111_320,
                longitude: 127.0,
                horizontalAccuracy: 8,
                speed: 12
            ))
        }
        var transition = ParkingTransition(
            enteredAt: TestTime.offset(100),
            entryReason: .movementIdle,
            drive: drive,
            driveDurationAtEnd: 100,
            sessionStartedAt: TestTime.offset(0),
            vehicleExitDetected: false,
            isCapturing: false
        )
        transition.evidence.walkingAfterVehicle = true
        let engine = DetectionEngineRecord(
            driving: drive,
            isDrivingCaptureLost: true,
            transition: transition,
            candidateDrive: drive,
            candidateResume: CandidateResume(
                driveEndedAt: TestTime.offset(100),
                deadline: TestTime.offset(400),
                reportedMovingFixes: 1
            ),
            isVehicleActive: true,
            vehicleActiveSince: TestTime.offset(0),
            connectedCarLinks: [.bluetoothAudio, .projection],
            hasProducedCandidateInSession: true
        )
        let checkpoint = DetectionCheckpoint(
            state: .departureCandidate,
            stateEnteredAt: TestTime.offset(100),
            engine: engine,
            revision: 3
        )

        // Act
        try store.save(checkpoint)
        let result = store.load()
        let written = try String(contentsOf: file.url, encoding: .utf8)

        // Assert
        #expect(result == .restored(checkpoint))
        #expect(written.contains(#""schemaVersion":3"#))
        #expect(!written.contains(#""departure""#), "schema 2's record is never written again")
    }

    /// docs/05 §11 "A lost capture decides nothing": a departure record written before
    /// `isCaptureLost` existed describes a session whose capture was running.
    @Test("A departure record without isCaptureLost reads as a running capture")
    func departureRecordWithoutCaptureFlagReadsAsRunning() throws {
        // Arrange — encode a record, then drop the field the older writer did not have.
        let record = DepartureCheckpoint(
            drive: DrivingEvidence(startedAt: TestTime.offset(0), lastVehicleEvidenceAt: TestTime.offset(0)),
            vehicleActiveSince: TestTime.offset(0),
            isCaptureLost: true
        )
        var object = try #require(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(record)) as? [String: Any]
        )
        #expect(object.removeValue(forKey: "isCaptureLost") != nil)
        let older = try JSONSerialization.data(withJSONObject: object)

        // Act
        let decoded = try JSONDecoder().decode(DepartureCheckpoint.self, from: older)

        // Assert
        #expect(!decoded.isCaptureLost)
        #expect(decoded.drive == record.drive)
    }

    @Test("Reads a schema 1 checkpoint, which has no departure evidence")
    func readsSchemaOneCheckpoint() throws {
        // Arrange — the shape every install wrote before 2026-09-28.
        let file = TemporaryCheckpointFile()
        let payload = """
        {"schemaVersion":1,"checkpoint":{"state":"DEPARTURE_CANDIDATE","stateEnteredAt":0,\
        "travelDistanceEstimate":600,"revision":4}}
        """
        try Data(payload.utf8).write(to: file.url)
        let store = FileDetectionCheckpointStore(fileURL: file.url)

        // Act
        let restored = store.load().checkpoint

        // Assert
        #expect(restored?.state == .departureCandidate)
        #expect(restored?.travelDistanceEstimate == 600)
        #expect(restored?.legacyDeparture == nil)
        #expect(restored?.engine == nil)
    }

    /// docs/05 §14 "A checkpoint from before schema 3": schema 2's departure record is read
    /// under its own key, and the engine migrates it — a departure that keeps its evidence.
    @Test("A schema 2 departure checkpoint restores its evidence and is written back as schema 3")
    func migratesSchemaTwoDeparture() async throws {
        // Arrange — the shape written between 2026-09-28 and 2026-09-29.
        let file = TemporaryCheckpointFile()
        let store = FileDetectionCheckpointStore(fileURL: file.url)
        var drive = DrivingEvidence(startedAt: TestTime.offset(0), lastVehicleEvidenceAt: TestTime.offset(0))
        drive.record(fix: LocationFix(
            timestamp: TestTime.offset(90),
            latitude: 37.5,
            longitude: 127.0,
            horizontalAccuracy: 8,
            speed: 12
        ))
        let record = DepartureCheckpoint(drive: drive, vehicleActiveSince: TestTime.offset(0))
        let recordJSON = try #require(String(data: JSONEncoder().encode(record), encoding: .utf8))
        let payload = """
        {"schemaVersion":2,"checkpoint":{"state":"DEPARTURE_CANDIDATE",\
        "stateEnteredAt":\(TestTime.offset(100).timeIntervalSinceReferenceDate),\
        "travelDistanceEstimate":0,"revision":4,"departure":\(recordJSON)}}
        """
        try Data(payload.utf8).write(to: file.url)
        let engine = ParkingDetectionEngine()

        // Act
        let legacy = try #require(store.load().checkpoint)
        let effects = await engine.restore(legacy, now: TestTime.offset(120))
        let migrated = effects.compactMap { if case let .persistCheckpoint(c) = $0 { c } else { nil } }.last

        // Assert
        #expect(legacy.legacyDeparture == record)
        #expect(await engine.state == .departureCandidate)
        #expect(await engine.snapshot().driving == drive)
        #expect(effects.contains(.startBoundedLocationCapture))
        #expect(migrated?.engine?.driving == drive, "the migration is written back once")
        #expect(migrated?.legacyDeparture == nil)
    }

    @Test("Overwrites in place so a crash cannot leave two checkpoints")
    func overwritesPreviousCheckpoint() throws {
        // Arrange
        let file = TemporaryCheckpointFile()
        let store = FileDetectionCheckpointStore(fileURL: file.url)
        let first = DetectionCheckpoint.initial(at: TestTime.offset(0))
        var second = first
        second.revision = 42
        second.state = .driving

        // Act
        try store.save(first)
        try store.save(second)

        // Assert
        #expect(store.load().checkpoint?.revision == 42)
        #expect(store.load().checkpoint?.state == .driving)
    }

    @Test("clear() is idempotent and leaves an absent checkpoint")
    func clearIsIdempotent() throws {
        // Arrange
        let file = TemporaryCheckpointFile()
        let store = FileDetectionCheckpointStore(fileURL: file.url)
        try store.save(.initial(at: TestTime.offset(0)))

        // Act
        try store.clear()
        try store.clear()

        // Assert
        #expect(store.load() == .absent)
    }

    @Test("latestTimestamp picks the newest known moment, ignoring nils")
    func latestTimestampPicksNewest() {
        // Arrange
        let checkpoint = DetectionCheckpoint(
            state: .driving,
            stateEnteredAt: TestTime.offset(0),
            lastAutomotiveAt: TestTime.offset(120),
            lastReliableLocation: nil,
            lastLocationAt: TestTime.offset(60)
        )

        // Act / Assert
        #expect(checkpoint.latestTimestamp == TestTime.offset(120))
        #expect(checkpoint.age(now: TestTime.offset(200)) == 80)
    }

    @Test("A checkpoint with only stateEnteredAt still has an anchor")
    func latestTimestampFallsBackToStateEntry() {
        // Arrange
        let checkpoint = DetectionCheckpoint.initial(at: TestTime.offset(0))

        // Act / Assert
        #expect(checkpoint.latestTimestamp == TestTime.offset(0))
        #expect(checkpoint.lastReliableLocation == nil)
    }
}

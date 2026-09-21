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

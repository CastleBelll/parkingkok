import Foundation
import Testing
@testable import ParkingKok

/// docs/05 §10a "History" and docs/10 §7b: what the bell records, what it refuses to
/// record, and what a row does when it is tapped.
@Suite("Notification history")
struct CandidateHistoryStoreTests {
    private let now = TestTime.reference

    private func entry(
        minutesAgo: Double,
        outcome: CandidateOutcome = .expired,
        recordId: UUID? = nil
    ) -> CandidateHistoryEntry {
        CandidateHistoryEntry(
            id: UUID(),
            raisedAt: now.addingTimeInterval(-minutesAgo * 60),
            outcome: outcome,
            recordId: recordId
        )
    }

    @Test("An entry carries the raised-at time, the outcome and nothing else")
    func entryShape() throws {
        // Arrange — a candidate that has a coordinate, which is the thing at risk.
        let candidate = TestCandidate.make(
            lastReliableLocation: LastReliableLocation(
                latitude: 37.566_295,
                longitude: 126.977_945,
                horizontalAccuracy: 12,
                capturedAt: now
            )
        )

        // Act
        let recordId = UUID()
        let entry = CandidateHistoryEntry(candidate: candidate, outcome: .confirmed, recordId: recordId)
        let encoded = try #require(String(data: JSONEncoder().encode(entry), encoding: .utf8))

        // Assert — §10a: "Not the location." The encoded form is what outlives the
        // process, so that is where the absence has to be true.
        #expect(entry.raisedAt == candidate.detectedAt)
        #expect(entry.outcome == .confirmed)
        #expect(entry.recordId == recordId)
        #expect(!encoded.contains("37.5"))
        #expect(!encoded.contains("126.9"))
        #expect(!encoded.lowercased().contains("latitude"))
        #expect(!encoded.lowercased().contains("longitude"))
    }

    @Test("A record id on anything but a confirmation is refused")
    func recordIdOnlyForConfirmed() {
        // Arrange / Act — the caller offers one anyway.
        let rejected = CandidateHistoryEntry(id: UUID(), raisedAt: now, outcome: .rejected, recordId: UUID())
        let expired = CandidateHistoryEntry(id: UUID(), raisedAt: now, outcome: .expired, recordId: UUID())

        // Assert — a record id here would name a record that was never written.
        #expect(rejected.recordId == nil)
        #expect(expired.recordId == nil)
    }

    @Test("Entries come back newest first")
    func newestFirst() {
        // Arrange
        let file = TemporaryDetectionFile()
        let store = FileCandidateHistoryStore(fileURL: file.fileURL)
        let old = entry(minutesAgo: 600)
        let new = entry(minutesAgo: 5)

        // Act
        store.append(old)
        store.append(new)

        // Assert
        #expect(store.entries().map(\.id) == [new.id, old.id])
    }

    @Test("Past thirty, the oldest falls off")
    func capacity() {
        // Arrange
        let file = TemporaryDetectionFile()
        let store = FileCandidateHistoryStore(fileURL: file.fileURL)
        let oldest = entry(minutesAgo: 1000)

        // Act — the oldest first, then a full thirty on top of it.
        store.append(oldest)
        let newer = (0 ..< FileCandidateHistoryStore.maximumEntries).map { index in
            entry(minutesAgo: Double(900 - index))
        }
        for one in newer {
            store.append(one)
        }

        // Assert — docs/10 §7b: "The last 30 entries ... Older ones fall off."
        let stored = store.entries()
        #expect(stored.count == FileCandidateHistoryStore.maximumEntries)
        #expect(!stored.contains { $0.id == oldest.id })
        #expect(stored.first?.id == newer.last?.id)
    }

    @Test("Appending the same candidate twice records it once")
    func idempotent() {
        // Arrange — the screen and the engine can both retire the same candidate.
        let file = TemporaryDetectionFile()
        let store = FileCandidateHistoryStore(fileURL: file.fileURL)
        let one = entry(minutesAgo: 10, outcome: .confirmed, recordId: UUID())

        // Act
        store.append(one)
        store.append(CandidateHistoryEntry(id: one.id, raisedAt: one.raisedAt, outcome: .expired))

        // Assert — the first answer stands; §12 allows one candidate, so the second
        // append is the same event arriving twice.
        #expect(store.entries().count == 1)
        #expect(store.entries().first?.outcome == .confirmed)
    }

    @Test("A history file that will not decode is an empty bell, never a failure")
    func unreadableFile() throws {
        // Arrange
        let file = TemporaryDetectionFile()
        try Data("not json".utf8).write(to: file.fileURL)
        let store = FileCandidateHistoryStore(fileURL: file.fileURL)

        // Act / Assert
        #expect(store.entries().isEmpty)
        store.append(entry(minutesAgo: 1))
        #expect(store.entries().count == 1)
    }

    @Test("A store with nowhere to write holds nothing and refuses nothing")
    func unavailableStore() {
        // Arrange
        let store = UnavailableCandidateHistoryStore()

        // Act
        store.append(entry(minutesAgo: 1))

        // Assert — CLAUDE.md: a capability that is unavailable is not an app failure.
        #expect(store.entries().isEmpty)
    }
}

/// A scratch file in the shape the production store expects.
final class TemporaryDetectionFile {
    let directory: URL
    let fileURL: URL

    init(name: String = "candidate-history.json") {
        directory = URL.temporaryDirectory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appending(path: name, directoryHint: .notDirectory)
    }

    deinit {
        try? FileManager.default.removeItem(at: directory)
    }
}

/// The other half of docs/05 §10a's "History": the resolutions the engine makes with no
/// UI in the process.
///
/// These never reach `CandidateModel` — the file is already gone by the time a screen
/// looks — so if the coordinator did not record them the bell would simply lose them.
@Suite("Notification history from the engine")
struct CandidateHistoryFromEngineTests {
    private let reference = TestTime.offset(0)

    private struct Harness {
        let coordinator: BackgroundCoordinator
        let candidates: StubParkingCandidateStore
        let history: StubCandidateHistoryStore
        let notifier: StubCandidateNotifier
        let clock: MutableDateProvider
    }

    private func harness() -> Harness {
        let candidates = StubParkingCandidateStore()
        let history = StubCandidateHistoryStore()
        let notifier = StubCandidateNotifier()
        let clock = MutableDateProvider(reference)
        return Harness(
            coordinator: BackgroundCoordinator(
                checkpointStore: StubCheckpointStore(loadResult: .absent),
                motionHistory: StubMotionHistoryProvider(
                    result: .success([
                        MotionSample(
                            timestamp: reference.addingTimeInterval(-30),
                            automotive: true,
                            stationary: true,
                            confidence: .high
                        )
                    ])
                ),
                locationCapture: StubBoundedLocationCapture(),
                dateProvider: clock,
                candidateStore: candidates,
                candidateHistory: history,
                candidateNotifier: notifier
            ),
            candidates: candidates,
            history: history,
            notifier: notifier,
            clock: clock
        )
    }

    @Test("A candidate that timed out unanswered is recorded as 응답 없음")
    func engineExpiryIsRecorded() async throws {
        // Arrange — a real candidate, created by the engine.
        let harness = harness()
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        await harness.coordinator.injectCandidateForFieldTest(walking: true)
        let created = try #require(harness.candidates.load())
        #expect(harness.history.entries().isEmpty)

        // Act — nobody ever opened the app.
        harness.clock.advance(by: ParkingCandidatePolicy.expiry + 60)
        await harness.coordinator.evaluateDrivingTimeouts()

        // Assert — docs/05 §10: withdrawn, no record created, and the bell can still
        // account for it.
        let entry = try #require(harness.history.entries().first)
        #expect(entry.id == created.id)
        #expect(entry.raisedAt == created.detectedAt)
        #expect(entry.outcome == .expired)
        #expect(entry.recordId == nil)
        #expect(harness.candidates.load() == nil)
        #expect(harness.notifier.withdrawnCandidateIds == [created.id])
    }

    @Test("The entry the engine writes carries no coordinate")
    func engineEntryHasNoCoordinate() async throws {
        // Arrange
        let harness = harness()
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        await harness.coordinator.injectCandidateForFieldTest(walking: true)

        // Act
        harness.clock.advance(by: ParkingCandidatePolicy.expiry + 60)
        await harness.coordinator.evaluateDrivingTimeouts()

        // Assert
        let entry = try #require(harness.history.entries().first)
        let encoded = try #require(String(data: JSONEncoder().encode(entry), encoding: .utf8))
        #expect(!encoded.lowercased().contains("latitude"))
        #expect(!encoded.lowercased().contains("longitude"))
    }
}

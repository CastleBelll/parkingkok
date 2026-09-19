import Foundation
import Testing
@testable import ParkingKok

/// docs/10 §7b through the model the screen actually reads: what each of the three
/// resolutions leaves behind, what a row says, where it goes, and when the bell has a dot.
@MainActor
@Suite("Notification history list")
struct NotificationHistoryTests {
    private static let now = TestTime.reference

    private struct Harness {
        let candidates: CandidateModel
        let parking: ParkingModel
        let store: StubParkingCandidateStore
        let history: StubCandidateHistoryStore
        let clock: MutableDateProvider
    }

    private func harness(pending: ParkingCandidate? = nil) throws -> Harness {
        let clock = MutableDateProvider(Self.now)
        let parkingStore = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: clock
        )
        let parking = ParkingModel(store: parkingStore, clock: clock)
        parking.refresh()

        let store = StubParkingCandidateStore(current: pending)
        let history = StubCandidateHistoryStore()
        let model = CandidateModel(
            store: store,
            history: history,
            notifier: StubCandidateNotifier(),
            parking: parking,
            clock: clock,
            resolver: StubCandidateResolver()
        )
        model.refresh()
        return Harness(candidates: model, parking: parking, store: store, history: history, clock: clock)
    }

    // ── The three outcomes ──────────────────────────────────────────────────

    @Test("Confirming records 저장됨 against the record it became")
    func confirmedIsRecorded() throws {
        // Arrange
        let candidate = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: candidate)

        // Act
        #expect(harness.candidates.confirm(candidate, draft: ManualParkingDraft(floorText: "B3")))

        // Assert
        let entry = try #require(harness.history.entries().first)
        #expect(harness.history.entries().count == 1)
        #expect(entry.id == candidate.id)
        #expect(entry.raisedAt == candidate.detectedAt)
        #expect(entry.outcome == .confirmed)
        #expect(entry.recordId == harness.parking.activeSession?.id)
    }

    @Test("Rejecting records 주차 아님 and names no record")
    func rejectedIsRecorded() throws {
        // Arrange
        let candidate = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: candidate)

        // Act
        harness.candidates.reject(candidate)

        // Assert
        let entry = try #require(harness.history.entries().first)
        #expect(entry.outcome == .rejected)
        #expect(entry.recordId == nil)
        #expect(harness.parking.activeSession == nil)
    }

    @Test("Expiring records 응답 없음 the moment anybody looks")
    func expiredIsRecorded() throws {
        // Arrange — docs/05 §10 evaluates the deadline lazily; nothing is scheduled.
        let candidate = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: candidate)
        #expect(harness.history.entries().isEmpty)

        // Act
        harness.clock.advance(by: ParkingCandidatePolicy.expiry + 1)
        harness.candidates.refresh()

        // Assert
        let entry = try #require(harness.history.entries().first)
        #expect(entry.outcome == .expired)
        #expect(entry.recordId == nil)
        #expect(harness.candidates.pending == nil)
    }

    @Test("The live slot still holds one candidate, whatever the history holds")
    func slotKeepsItsOneJob() throws {
        // Arrange — docs/05 §10a: "giving the slot a second job is how it would end up
        // holding two live candidates by accident".
        let first = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: first)

        // Act
        harness.candidates.reject(first)
        let second = TestCandidate.make(detectedAt: Self.now.addingTimeInterval(60))
        try harness.store.save(second)
        harness.candidates.refresh()

        // Assert
        #expect(harness.store.load()?.id == second.id)
        #expect(harness.candidates.pending?.id == second.id)
        #expect(harness.history.entries().count == 1)
    }

    // ── The dot ─────────────────────────────────────────────────────────────

    @Test("The bell carries a dot only while a candidate is unanswered")
    func dotTracksTheUnansweredCandidate() throws {
        // Arrange
        let candidate = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: candidate)

        // Assert — unanswered
        #expect(harness.candidates.hasUnansweredCandidate)

        // Act — answered
        harness.candidates.reject(candidate)

        // Assert — a history full of rows is not an unanswered candidate.
        #expect(!harness.candidates.hasUnansweredCandidate)
        #expect(harness.candidates.notificationHistory().count == 1)
    }

    @Test("An expired candidate takes the dot with it")
    func dotClearsOnExpiry() throws {
        // Arrange
        let harness = try harness(pending: TestCandidate.make(detectedAt: Self.now))

        // Act
        harness.clock.advance(by: ParkingCandidatePolicy.expiry + 1)
        harness.candidates.refresh()

        // Assert
        #expect(!harness.candidates.hasUnansweredCandidate)
    }

    @Test("With nothing ever raised there is no dot and no list")
    func emptyState() throws {
        // Arrange / Act
        let harness = try harness()

        // Assert
        #expect(!harness.candidates.hasUnansweredCandidate)
        #expect(harness.candidates.notificationHistory().isEmpty)
    }

    // ── Tapping a row ───────────────────────────────────────────────────────

    @Test("A pending row opens the confirmation screen")
    func pendingRowOpensConfirmation() throws {
        // Arrange
        let candidate = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: candidate)

        // Act
        let item = try #require(harness.candidates.notificationHistory().first)

        // Assert
        #expect(item.kind == .pending)
        #expect(item.detail == "확인이 필요해요")
        #expect(item.destination == .candidateConfirmation(id: candidate.id))
        #expect(item.isTappable)
    }

    @Test("A confirmed row opens its record and names the floor")
    func confirmedRowOpensRecord() throws {
        // Arrange
        let candidate = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: candidate)
        #expect(harness.candidates.confirm(
            candidate,
            draft: ManualParkingDraft(floorText: "B3", zone: "A구역", spot: "142")
        ))

        // Act
        let item = try #require(harness.candidates.notificationHistory().first)

        // Assert — docs/10 §7b's `B3 · A구역 142 로 저장됨`, read off the record because
        // the entry is forbidden to store a floor.
        let recordId = try #require(harness.parking.activeSession?.id)
        #expect(item.kind == .saved(place: "B3 · A구역 · 142"))
        #expect(item.detail == "B3 · A구역 · 142 로 저장됨")
        #expect(item.destination == .parkingDetail(id: recordId))
    }

    @Test("A rejected or expired row does nothing and must not look tappable")
    func resolvedRowsAreInert() throws {
        // Arrange
        let rejected = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: rejected)
        harness.candidates.reject(rejected)
        let expiring = TestCandidate.make(detectedAt: Self.now.addingTimeInterval(60))
        try harness.store.save(expiring)
        harness.candidates.refresh()
        harness.clock.advance(by: ParkingCandidatePolicy.expiry + 61)
        harness.candidates.refresh()

        // Act
        let items = harness.candidates.notificationHistory()

        // Assert
        #expect(items.count == 2)
        #expect(items.allSatisfy { !$0.isTappable })
        #expect(items.allSatisfy { $0.destination == nil })
        #expect(items.map(\.detail) == ["응답 없음", "주차 아님"])
    }

    @Test("A 저장됨 row whose record was deleted stops being tappable")
    func deletedRecordMakesRowInert() async throws {
        // Arrange
        let candidate = TestCandidate.make(detectedAt: Self.now)
        let harness = try harness(pending: candidate)
        #expect(harness.candidates.confirm(candidate, draft: ManualParkingDraft(floorText: "B3")))
        let recordId = try #require(harness.parking.activeSession?.id)

        // Act
        await harness.parking.delete(id: recordId)

        // Assert — the outcome is still true; there is simply nothing left to open.
        let item = try #require(harness.candidates.notificationHistory().first)
        #expect(item.kind == .saved(place: nil))
        #expect(item.detail == "저장됨")
        #expect(!item.isTappable)
    }

    @Test("The list is newest first, with the unanswered one on top")
    func ordering() throws {
        // Arrange
        let older = TestCandidate.make(detectedAt: Self.now.addingTimeInterval(-3600))
        let harness = try harness(pending: older)
        harness.candidates.reject(older)
        let pending = TestCandidate.make(detectedAt: Self.now)
        try harness.store.save(pending)
        harness.candidates.refresh()

        // Act
        let items = harness.candidates.notificationHistory()

        // Assert
        #expect(items.map(\.id) == [pending.id, older.id])
    }
}

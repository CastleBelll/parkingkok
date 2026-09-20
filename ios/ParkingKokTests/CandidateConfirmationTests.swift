import Foundation
import Testing
import UserNotifications
@testable import ParkingKok

/// The user's half of docs/05 §10a and docs/10 §7a: confirming, rejecting, backing out,
/// and what a tap on a notification that has already expired does.
@MainActor
@Suite("Candidate confirmation")
struct CandidateConfirmationTests {
    private static let now = TestTime.reference

    private struct Harness {
        let candidates: CandidateModel
        let parking: ParkingModel
        let store: StubParkingCandidateStore
        let notifier: StubCandidateNotifier
        let analytics: RecordingAnalyticsSink
        let resolver: StubCandidateResolver
        let clock: MutableDateProvider
    }

    private func harness(
        pending: ParkingCandidate? = nil,
        history: [ParkingSession] = []
    ) throws -> Harness {
        let clock = MutableDateProvider(Self.now)
        let parkingStore = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: clock
        )
        for session in history {
            try parkingStore.startSession(session)
            if let endedAt = session.endedAt {
                try parkingStore.endSession(id: session.id, at: endedAt)
            }
        }
        let parking = ParkingModel(store: parkingStore, clock: clock)
        parking.refresh()

        let store = StubParkingCandidateStore(current: pending)
        let notifier = StubCandidateNotifier()
        let sink = RecordingAnalyticsSink()
        let resolver = StubCandidateResolver()
        let model = CandidateModel(
            store: store,
            notifier: notifier,
            analytics: AnalyticsRecorder(
                consent: MutableAnalyticsConsentStore(granted: true),
                sink: sink,
                clock: clock
            ),
            parking: parking,
            clock: clock,
            resolver: resolver
        )
        model.refresh()
        return Harness(
            candidates: model,
            parking: parking,
            store: store,
            notifier: notifier,
            analytics: sink,
            resolver: resolver,
            clock: clock
        )
    }

    /// A finished record with a floor, for the quick-pick tests.
    private func completed(floor: String, startedAt: Date) -> ParkingSession {
        ParkingSession(
            id: UUID(),
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(3600),
            source: .manual,
            confidenceBucket: nil,
            location: nil,
            floor: FloorValue.parse(floor),
            zone: nil,
            spot: nil,
            memo: nil,
            photoRelativePath: nil,
            createdAt: startedAt,
            updatedAt: startedAt
        )
    }

    // MARK: - Confirming

    @Test("Confirming writes a detected record with the candidate's location and the chosen floor")
    func confirmWritesDetectedRecord() throws {
        // Arrange
        let candidate = TestCandidate.make(lastReliableLocation: TestCandidate.location)
        let harness = try harness(pending: candidate)

        // Act
        let confirmed = harness.candidates.confirm(
            candidate,
            draft: ManualParkingDraft(floorText: "B3")
        )

        // Assert
        #expect(confirmed)
        let active = try #require(harness.parking.activeSession)
        #expect(active.source == .detected)
        #expect(active.startedAt == candidate.detectedAt)
        #expect(active.floor?.displayText == "B3")
        #expect(active.confidenceBucket == .medium)
        #expect(active.location?.latitude == TestCandidate.location.latitude)
        // The candidate itself is gone, its notification withdrawn, and the engine told.
        #expect(harness.candidates.pending == nil)
        #expect(harness.store.clearCount == 1)
    }

    @Test("Confirming reports the event and moves the state machine to PARKED")
    func confirmReportsAndResolves() async throws {
        // Arrange
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate)

        // Act
        harness.candidates.confirm(candidate, draft: ManualParkingDraft(floorText: "B3"))
        await harness.candidates.retirement?.value

        // Assert
        #expect(harness.analytics.payloads.map(\.name) == ["parking_candidate_confirmed"])
        #expect(harness.notifier.withdrawnCandidateIds == [candidate.id])
        #expect(harness.resolver.outcomes == [.confirmed])
    }

    /// FR-004's conflict policy: one active parking, and the older one ends where this
    /// drive finished rather than being left open beside it.
    @Test("Confirming while another parking is active ends the old one")
    func confirmEndsTheActiveParking() throws {
        // Arrange
        let existing = ParkingSession(
            id: UUID(),
            startedAt: Self.now.addingTimeInterval(-7200),
            endedAt: nil,
            source: .manual,
            confidenceBucket: nil,
            location: nil,
            floor: FloorValue.parse("2F"),
            zone: nil,
            spot: nil,
            memo: nil,
            photoRelativePath: nil,
            createdAt: Self.now.addingTimeInterval(-7200),
            updatedAt: Self.now.addingTimeInterval(-7200)
        )
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate, history: [existing])

        // Act
        let confirmed = harness.candidates.confirm(candidate, draft: ManualParkingDraft(floorText: "B1"))

        // Assert
        #expect(confirmed)
        #expect(harness.parking.activeSession?.floor?.displayText == "B1")
        let finished = try #require(harness.parking.completedSessions.first { $0.id == existing.id })
        #expect(finished.endedAt == candidate.detectedAt)
    }

    // MARK: - Rejecting

    /// Acceptance: **the rejection event is never dropped.** §10a: "Rejection is the event
    /// that pays for the whole feature."
    @Test("Rejecting reports the event, discards the candidate and creates nothing")
    func rejectReportsAndDiscards() async throws {
        // Arrange
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate)

        // Act
        harness.candidates.reject(candidate)
        await harness.candidates.retirement?.value

        // Assert
        #expect(harness.analytics.payloads.map(\.name) == ["parking_candidate_rejected"])
        #expect(harness.parking.activeSession == nil)
        #expect(harness.candidates.pending == nil)
        #expect(harness.notifier.withdrawnCandidateIds == [candidate.id])
        #expect(harness.resolver.outcomes == [.rejected])
    }

    /// The rejection payload is a detection event like any other, so §3's allowlist has to
    /// hold for it too — a reason to reject is never a place.
    @Test("The rejection event carries only the allowed properties")
    func rejectionStaysInsideTheAllowlist() async throws {
        // Arrange
        let candidate = TestCandidate.make(lastReliableLocation: TestCandidate.location)
        let harness = try harness(pending: candidate)

        // Act
        harness.candidates.reject(candidate)
        await harness.candidates.retirement?.value

        // Assert
        let payload = try #require(harness.analytics.payloads.first)
        #expect(Set(payload.parameters.keys).isSubset(of: AnalyticsPayload.Key.all))
    }

    // MARK: - Expiry

    /// Acceptance: **45 minutes later, nothing is created.**
    @Test("A candidate past its expiry is retired on the next look and writes no record")
    func expiredCandidateCreatesNothing() async throws {
        // Arrange
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate)
        #expect(harness.candidates.pending != nil)

        // Act
        harness.clock.advance(by: ParkingCandidatePolicy.expiry)
        harness.candidates.refresh()
        await harness.candidates.retirement?.value

        // Assert — gone, withdrawn, and no parking anywhere.
        #expect(harness.candidates.pending == nil)
        #expect(harness.parking.activeSession == nil)
        #expect(harness.notifier.withdrawnCandidateIds == [candidate.id])
        #expect(harness.resolver.outcomes == [.expired])
        // docs/17 §2 has no event for a guess nobody answered.
        #expect(harness.analytics.payloads.isEmpty)
    }

    /// Acceptance: **tapping an expired notification.** §10a: it "lands on home; the app
    /// does not apologise for it in a dialog", and it never silently creates a parking.
    @Test("Tapping an expired notification routes nowhere and creates nothing")
    func tappingExpiredNotificationLandsOnHome() throws {
        // Arrange
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate)
        harness.clock.advance(by: ParkingCandidatePolicy.expiry)
        let responder = CandidateNotificationResponder(model: harness.candidates)

        // Act
        responder.handle(
            actionIdentifier: UNNotificationDefaultActionIdentifier,
            userInfo: [CandidateNotificationAction.candidateIdKey: candidate.id.uuidString]
        )

        // Assert — no destination, no record.
        #expect(harness.candidates.pendingNavigation == nil)
        #expect(harness.parking.activeSession == nil)
    }

    @Test("Tapping a live notification opens that candidate's confirmation screen")
    func tappingLiveNotificationOpensTheScreen() throws {
        // Arrange
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate)
        let responder = CandidateNotificationResponder(model: harness.candidates)

        // Act
        responder.handle(
            actionIdentifier: UNNotificationDefaultActionIdentifier,
            userInfo: [CandidateNotificationAction.candidateIdKey: candidate.id.uuidString]
        )

        // Assert
        #expect(harness.candidates.pendingNavigation == .candidateConfirmation(id: candidate.id))
    }

    /// docs/02 §5's `주차 아님`, answered from the lock screen without the app ever drawing.
    @Test("The 주차 아님 action rejects without opening the app")
    func notParkingActionRejects() async throws {
        // Arrange
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate)
        let responder = CandidateNotificationResponder(model: harness.candidates)

        // Act
        responder.handle(
            actionIdentifier: CandidateNotificationAction.notParking,
            userInfo: [CandidateNotificationAction.candidateIdKey: candidate.id.uuidString]
        )
        await harness.candidates.retirement?.value

        // Assert
        #expect(harness.analytics.payloads.map(\.name) == ["parking_candidate_rejected"])
        #expect(harness.candidates.pendingNavigation == nil)
    }

    /// Dismissing a notification is not an answer. §10a leaves the candidate pending until
    /// it expires, because a swipe is how people clear a lock screen, not how they decide.
    @Test("Dismissing the notification leaves the candidate pending")
    func dismissLeavesCandidatePending() throws {
        // Arrange
        let candidate = TestCandidate.make()
        let harness = try harness(pending: candidate)
        let responder = CandidateNotificationResponder(model: harness.candidates)

        // Act
        responder.handle(
            actionIdentifier: UNNotificationDismissActionIdentifier,
            userInfo: [CandidateNotificationAction.candidateIdKey: candidate.id.uuidString]
        )

        // Assert
        #expect(harness.candidates.pending?.id == candidate.id)
    }
}

import Foundation
import Testing
@testable import ParkingPin

/// docs/05 §11a: what the adapter does with `proposeParkingEnd` — writes the proposal, asks,
/// and ends nothing.
@Suite("§11a departure proposal — coordinator")
struct BackgroundCoordinatorProposalTests {
    private let t0 = TestTime.offset(0)

    private func at(_ seconds: TimeInterval) -> Date {
        t0.addingTimeInterval(seconds)
    }

    private struct Harness {
        let coordinator: BackgroundCoordinator
        let proposals: InMemoryParkingEndProposalStore
        let notifier: RecordingParkingEndProposalNotifier
        let analytics: RecordingAnalyticsSink
    }

    /// A coordinator whose engine is one fix short of confirming a departure from a hand-saved
    /// parking — `manual_save_then_departure.json` up to t=730, where §11's bars were cleared.
    private func harness(active: ActiveParkingSummary?) async -> Harness {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        let events: [DetectionEvent] = [
            .userSavedParking(at: at(0)),
            .vehicleEnter(at: at(600), confidence: .high),
            .location(TestGeo.fix(at: at(610), metersNorth: 0, accuracy: 8, speed: 12)),
            .location(TestGeo.fix(at: at(730), metersNorth: 1000, accuracy: 8, speed: 12))
        ]
        for event in events {
            _ = await engine.handle(event)
        }
        let proposals = InMemoryParkingEndProposalStore()
        let notifier = RecordingParkingEndProposalNotifier()
        let sink = RecordingAnalyticsSink()
        let clock = MutableDateProvider(at(760))
        let coordinator = BackgroundCoordinator(
            checkpointStore: StubCheckpointStore(loadResult: .absent),
            motionHistory: StubMotionHistoryProvider(result: .success([])),
            locationCapture: StubBoundedLocationCapture(),
            dateProvider: clock,
            analytics: AnalyticsRecorder(
                consent: MutableAnalyticsConsentStore(granted: true),
                sink: sink,
                clock: clock
            ),
            activeParking: { active },
            proposalStore: proposals,
            proposalNotifier: notifier,
            engine: engine
        )
        return Harness(coordinator: coordinator, proposals: proposals, notifier: notifier, analytics: sink)
    }

    private func confirmingFix() -> LocationFix {
        TestGeo.fix(at: at(760), metersNorth: 1300, accuracy: 8, speed: 12)
    }

    @Test("A confirmed departure writes the proposal, then asks with the record's place")
    func departureWritesAndAsks() async throws {
        // Arrange
        let sessionId = UUID()
        let active = ActiveParkingSummary(
            sessionId: sessionId,
            startedAt: at(0),
            floor: FloorValue.parse("B3"),
            zone: "A구역",
            spot: "142"
        )
        let harness = await harness(active: active)

        // Act
        await harness.coordinator.handleDrivingFix(confirmingFix())

        // Assert — the proposal is about that record and carries the pull-away time.
        let proposal = try #require(harness.proposals.load())
        #expect(proposal.sessionId == sessionId)
        #expect(proposal.departedAt == at(730))
        let posted = await harness.notifier.posted
        #expect(posted.count == 1)
        #expect(posted.first?.proposal == proposal)
        #expect(posted.first?.placeText == "B3 · A구역 · 142")
        #expect(await harness.coordinator.currentSnapshot().parkingEndProposedCount == 1)
    }

    @Test("A confirmed departure ends nothing and reports no parking_auto_end")
    func departureEndsNothing() async {
        // Arrange
        let active = ActiveParkingSummary(sessionId: UUID(), startedAt: at(0), floor: nil, zone: nil, spot: nil)
        let harness = await harness(active: active)

        // Act
        await harness.coordinator.handleDrivingFix(confirmingFix())

        // Assert — only the user's 주차 종료 reports it (docs/17).
        #expect(!harness.analytics.payloads.map(\.name).contains("parking_auto_end"))
        #expect(await harness.coordinator.currentSnapshot().currentCheckpoint?.state == .driving)
    }

    @Test("With no active parking there is nothing to ask about")
    func noActiveParkingNoProposal() async {
        // Arrange
        let harness = await harness(active: nil)

        // Act
        await harness.coordinator.handleDrivingFix(confirmingFix())

        // Assert
        #expect(harness.proposals.load() == nil)
        #expect(await harness.notifier.posted.isEmpty)
        // The engine still drives on, so the next parking is detected.
        #expect(await harness.coordinator.currentSnapshot().currentCheckpoint?.state == .driving)
    }

    @Test("A later departure replaces the pending proposal: one at a time")
    func laterDepartureReplaces() async throws {
        // Arrange
        let active = ActiveParkingSummary(sessionId: UUID(), startedAt: at(0), floor: nil, zone: nil, spot: nil)
        let harness = await harness(active: active)
        try harness.proposals.save(ParkingEndProposal(
            sessionId: active.sessionId,
            departedAt: at(100),
            proposedAt: at(200)
        ))

        // Act
        await harness.coordinator.handleDrivingFix(confirmingFix())

        // Assert
        #expect(harness.proposals.load()?.departedAt == at(730))
    }

    @Test("user_kept_parking through the coordinator parks the engine and stops the capture")
    func keptParkingThroughTheCoordinator() async {
        // Arrange
        let active = ActiveParkingSummary(sessionId: UUID(), startedAt: at(0), floor: nil, zone: nil, spot: nil)
        let harness = await harness(active: active)
        await harness.coordinator.handleDrivingFix(confirmingFix())

        // Act
        await harness.coordinator.userKeptParking(at: at(800))

        // Assert
        let snapshot = await harness.coordinator.currentSnapshot()
        #expect(snapshot.currentCheckpoint?.state == .parked)
        #expect(!snapshot.isCapturingDrivingLocation)
    }
}

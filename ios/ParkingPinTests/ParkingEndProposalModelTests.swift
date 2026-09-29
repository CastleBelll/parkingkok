import Foundation
import Testing
@testable import ParkingPin

/// docs/05 §11a (DECIDED BY THE USER 2026-09-29): the app's half of the departure proposal —
/// the home prompt, the three outcomes, and the rule that the next parking ends the old one
/// at `departedAt`.
@MainActor
@Suite("§11a departure proposal — model")
struct ParkingEndProposalModelTests {
    private static let start = TestTime.reference
    private static let departedAt = start.addingTimeInterval(3600)
    private static let now = start.addingTimeInterval(5400)

    @MainActor
    private struct Harness {
        let parking: ParkingModel
        let store: SwiftDataParkingStore
        let proposals: InMemoryParkingEndProposalStore
        let notifier: RecordingParkingEndProposalNotifier
        let detection: StubManualParkingReporter
        let analytics: RecordingAnalyticsSink
        let clock: MutableDateProvider

        /// The active record the proposal is about.
        var active: ParkingSession {
            get throws { try #require(parking.activeSession) }
        }

        func settle() async {
            await parking.proposalWithdrawal?.value
            await parking.detectionReport?.value
        }
    }

    /// An active parking at `start` — `B3`, `A구역`, `142` unless told otherwise — and,
    /// when asked, a pending proposal about it.
    private func harness(
        floor: String? = "B3",
        zone: String? = "A구역",
        spot: String? = "142",
        proposing: Bool = true,
        proposalSessionId: UUID?? = nil,
        failingNewRecordWrite: Bool = false
    ) throws -> Harness {
        let clock = MutableDateProvider(Self.now)
        let store = try SwiftDataParkingStore(container: SwiftDataParkingStore.makeInMemoryContainer(), clock: clock)
        let session = ParkingSession(
            id: UUID(),
            startedAt: Self.start,
            endedAt: nil,
            source: .manual,
            confidenceBucket: nil,
            location: nil,
            floor: floor.map(FloorValue.parse) ?? nil,
            zone: zone,
            spot: spot,
            memo: nil,
            photoRelativePath: nil,
            createdAt: Self.start,
            updatedAt: Self.start
        )
        try store.startSession(session)
        let proposals = InMemoryParkingEndProposalStore(
            proposing
                ? ParkingEndProposal(
                    sessionId: proposalSessionId ?? session.id,
                    departedAt: Self.departedAt,
                    proposedAt: Self.departedAt.addingTimeInterval(300)
                )
                : nil
        )
        let notifier = RecordingParkingEndProposalNotifier()
        let detection = StubManualParkingReporter()
        let sink = RecordingAnalyticsSink()
        let parking = ParkingModel(
            store: failingNewRecordWrite ? NewRecordWriteFailingParkingStore(store) : store,
            locationProvider: UnavailableParkingLocationProvider(),
            clock: clock,
            analytics: AnalyticsRecorder(
                consent: MutableAnalyticsConsentStore(granted: true),
                sink: sink,
                clock: clock
            ),
            detection: detection,
            endProposals: ParkingEndProposalInbox(store: proposals, notifier: notifier)
        )
        parking.refresh()
        return Harness(
            parking: parking,
            store: store,
            proposals: proposals,
            notifier: notifier,
            detection: detection,
            analytics: sink,
            clock: clock
        )
    }

    // MARK: - The prompt

    @Test("A pending proposal shows the prompt with the record's place and both answers")
    func promptShowsThePlace() throws {
        // Arrange / Act
        let harness = try harness()

        // Assert
        let prompt = try #require(harness.parking.endPrompt)
        #expect(prompt.title == "출발한 것 같아요")
        #expect(prompt.body == "B3 · A구역 · 142 주차를 종료할까요?")
        #expect(prompt.endTitle == "주차 종료")
        #expect(prompt.keepTitle == "아직 주차 중")
    }

    @Test("The prompt omits whatever the record does not have")
    func promptOmitsMissingParts() throws {
        // Arrange / Act
        let floorOnly = try harness(zone: nil, spot: nil)
        let nothing = try harness(floor: nil, zone: nil, spot: nil)
        let spotOnly = try harness(floor: nil, zone: nil, spot: "7")

        // Assert
        #expect(floorOnly.parking.endPrompt?.body == "B3 주차를 종료할까요?")
        #expect(nothing.parking.endPrompt?.body == "주차를 종료할까요?")
        #expect(spotOnly.parking.endPrompt?.body == "7번 주차를 종료할까요?")
    }

    @Test("No proposal, no prompt")
    func noProposalNoPrompt() throws {
        // Arrange / Act
        let harness = try harness(proposing: false)

        // Assert
        #expect(harness.parking.endPrompt == nil)
        #expect(harness.parking.activeSession != nil)
    }

    @Test("A proposal about another record is stale: no prompt, file cleared, notification withdrawn")
    func staleProposalIsRetired() async throws {
        // Arrange / Act
        let harness = try harness(proposalSessionId: .some(UUID()))
        await harness.settle()

        // Assert
        #expect(harness.parking.endPrompt == nil)
        #expect(harness.proposals.load() == nil)
        #expect(await harness.notifier.withdrawCount == 1)
    }

    @Test("A pending proposal survives a relaunch: a fresh model reads it from the file")
    func proposalSurvivesProcessDeath() throws {
        // Arrange
        let harness = try harness()
        let relaunched = ParkingModel(
            store: harness.store,
            clock: harness.clock,
            endProposals: ParkingEndProposalInbox(store: harness.proposals, notifier: harness.notifier)
        )

        // Act
        relaunched.refresh()

        // Assert
        #expect(relaunched.endPrompt != nil)
        #expect(relaunched.endProposal?.departedAt == Self.departedAt)
    }

    // MARK: - 주차 종료

    @Test("주차 종료 ends the record at departedAt, not now, and reports parking_auto_end")
    func acceptEndsAtDepartedAt() async throws {
        // Arrange
        let harness = try harness()
        let id = try harness.active.id

        // Act
        let ended = harness.parking.acceptEndProposal()
        await harness.settle()

        // Assert
        #expect(ended)
        #expect(harness.parking.activeSession == nil)
        #expect(harness.parking.session(id: id)?.endedAt == Self.departedAt)
        #expect(harness.analytics.payloads.map(\.name) == ["parking_auto_end"])
        #expect(harness.proposals.load() == nil)
        #expect(await harness.notifier.withdrawCount >= 1)
        #expect(harness.parking.endPrompt == nil)
        #expect(harness.detection.keptAt.isEmpty)
    }

    @Test("주차 종료 never ends a record before it started")
    func acceptClampsToStart() throws {
        // Arrange — a proposal stamped before the record (clock drift).
        let clock = MutableDateProvider(Self.now)
        let store = try SwiftDataParkingStore(container: SwiftDataParkingStore.makeInMemoryContainer(), clock: clock)
        let session = ParkingSession(
            id: UUID(), startedAt: Self.start, endedAt: nil, source: .manual, confidenceBucket: nil,
            location: nil, floor: nil, zone: nil, spot: nil, memo: nil, photoRelativePath: nil,
            createdAt: Self.start, updatedAt: Self.start
        )
        try store.startSession(session)
        let proposals = InMemoryParkingEndProposalStore(
            ParkingEndProposal(
                sessionId: session.id,
                departedAt: Self.start.addingTimeInterval(-60),
                proposedAt: Self.start
            )
        )
        let parking = ParkingModel(
            store: store,
            clock: clock,
            endProposals: ParkingEndProposalInbox(store: proposals, notifier: RecordingParkingEndProposalNotifier())
        )
        parking.refresh()

        // Act
        #expect(parking.acceptEndProposal())

        // Assert
        #expect(parking.session(id: session.id)?.endedAt == Self.start)
    }

    // MARK: - 아직 주차 중

    @Test("아직 주차 중 keeps the record, clears the proposal and tells the engine user_kept_parking")
    func keepKeepsTheRecord() async throws {
        // Arrange
        let harness = try harness()
        let id = try harness.active.id

        // Act
        harness.parking.keepParking()
        await harness.settle()

        // Assert
        #expect(harness.parking.activeSession?.id == id)
        #expect(harness.parking.activeSession?.endedAt == nil)
        #expect(harness.parking.endPrompt == nil)
        #expect(harness.proposals.load() == nil)
        #expect(await harness.notifier.withdrawCount == 1)
        #expect(harness.detection.keptAt == [Self.now])
        #expect(harness.analytics.payloads.isEmpty)
    }

    @Test("아직 주차 중 with nothing pending does nothing")
    func keepWithoutProposal() async throws {
        // Arrange
        let harness = try harness(proposing: false)

        // Act
        harness.parking.keepParking()
        await harness.settle()

        // Assert
        #expect(harness.detection.keptAt.isEmpty)
        #expect(await harness.notifier.withdrawCount == 0)
    }

    // MARK: - Ignored

    @Test("Ignored, the record stays active and the proposal stays pending")
    func ignoredStaysPending() async throws {
        // Arrange
        let harness = try harness()

        // Act — the app is opened again later without an answer.
        harness.clock.advance(by: 3 * 3600)
        harness.parking.refresh()
        await harness.settle()

        // Assert
        #expect(harness.parking.activeSession != nil)
        #expect(harness.parking.endPrompt != nil)
        #expect(harness.proposals.load() != nil)
        #expect(await harness.notifier.withdrawCount == 0)
    }

    @Test("A hand save while a proposal is pending ends the old record at departedAt")
    func manualSaveEndsOldRecordAtDepartedAt() async throws {
        // Arrange
        let harness = try harness()
        let oldId = try harness.active.id

        // Act
        let saved = await harness.parking.saveManualParking(ManualParkingDraft(floorText: "4F"))
        await harness.settle()

        // Assert
        #expect(saved)
        #expect(harness.parking.session(id: oldId)?.endedAt == Self.departedAt)
        #expect(harness.parking.activeSession?.floor == FloorValue.parse("4F"))
        #expect(harness.parking.endPrompt == nil)
        #expect(harness.proposals.load() == nil)
        #expect(await harness.notifier.withdrawCount >= 1)
        // Not an acceptance of the proposal: only the explicit 주차 종료 reports it.
        #expect(!harness.analytics.payloads.map(\.name).contains("parking_auto_end"))
    }

    @Test("Without a proposal, a hand save over an active parking is still refused (FR-004)")
    func manualSaveWithoutProposalIsRefused() async throws {
        // Arrange
        let harness = try harness(proposing: false)
        let oldId = try harness.active.id

        // Act
        let saved = await harness.parking.saveManualParking(ManualParkingDraft(floorText: "4F"))

        // Assert
        #expect(!saved)
        #expect(harness.parking.activeSession?.id == oldId)
    }

    @Test("A confirmed candidate while a proposal is pending ends the old record at departedAt")
    func confirmedCandidateEndsOldRecordAtDepartedAt() async throws {
        // Arrange — the candidate was detected well after the car left.
        let harness = try harness()
        let oldId = try harness.active.id
        let candidate = TestCandidate.make(detectedAt: Self.departedAt.addingTimeInterval(1200))

        // Act
        let newId = harness.parking.saveDetectedParking(from: candidate, draft: ManualParkingDraft(floorText: "B1"))
        await harness.settle()

        // Assert
        #expect(newId != nil)
        #expect(harness.parking.session(id: oldId)?.endedAt == Self.departedAt)
        #expect(harness.parking.activeSession?.id == newId)
        #expect(harness.parking.endPrompt == nil)
        #expect(harness.proposals.load() == nil)
    }

    // MARK: - The next parking settles the proposal only when it is written

    @Test("A hand save whose write fails leaves the old record active and the proposal pending")
    func failedManualSaveKeepsTheProposal() async throws {
        // Arrange
        let harness = try harness(failingNewRecordWrite: true)
        let oldId = try harness.active.id

        // Act
        let saved = await harness.parking.saveManualParking(ManualParkingDraft(floorText: "4F"))
        await harness.settle()

        // Assert
        #expect(!saved)
        #expect(try harness.store.activeSession()?.id == oldId)
        #expect(harness.parking.activeSession?.id == oldId)
        #expect(harness.parking.endPrompt != nil)
        #expect(harness.proposals.load() != nil)
        #expect(await harness.notifier.withdrawCount == 0)
    }

    @Test("A confirmed candidate whose write fails leaves the old record active and the proposal pending")
    func failedCandidateSaveKeepsTheProposal() async throws {
        // Arrange
        let harness = try harness(failingNewRecordWrite: true)
        let oldId = try harness.active.id
        let candidate = TestCandidate.make(detectedAt: Self.departedAt.addingTimeInterval(1200))

        // Act
        let newId = harness.parking.saveDetectedParking(from: candidate, draft: ManualParkingDraft(floorText: "B1"))
        await harness.settle()

        // Assert
        #expect(newId == nil)
        #expect(try harness.store.activeSession()?.id == oldId)
        #expect(harness.parking.endPrompt != nil)
        #expect(harness.proposals.load() != nil)
        #expect(await harness.notifier.withdrawCount == 0)
    }

    @Test("Without a proposal, a confirmed candidate whose write fails does not end the active record")
    func failedCandidateSaveWithoutProposalKeepsTheRecord() throws {
        // Arrange
        let harness = try harness(proposing: false, failingNewRecordWrite: true)
        let oldId = try harness.active.id
        let candidate = TestCandidate.make(detectedAt: Self.departedAt.addingTimeInterval(1200))

        // Act
        let newId = harness.parking.saveDetectedParking(from: candidate, draft: ManualParkingDraft(floorText: "B1"))

        // Assert
        #expect(newId == nil)
        #expect(try harness.store.activeSession()?.id == oldId)
    }

    // MARK: - A candidate that is no longer live settles nothing

    /// The candidate path a form takes: a `CandidateModel` over the harness's parking.
    private func candidates(over harness: Harness, pending: ParkingCandidate) -> (CandidateModel, StubParkingCandidateStore) {
        let store = StubParkingCandidateStore(current: pending)
        let model = CandidateModel(
            store: store,
            notifier: StubCandidateNotifier(),
            parking: harness.parking,
            clock: harness.clock
        )
        model.refresh()
        return (model, store)
    }

    /// What §11a promises for a save that writes nothing: the old record, the proposal
    /// and its prompt are exactly as they were, and no record was added.
    private func expectNothingSettled(_ harness: Harness, oldId: UUID) async throws {
        await harness.settle()
        let old = try #require(try harness.store.activeSession())
        #expect(old.id == oldId)
        #expect(old.endedAt == nil)
        #expect(harness.parking.activeSession?.id == oldId)
        #expect(harness.parking.completedSessions.isEmpty)
        #expect(harness.proposals.load() != nil)
        #expect(harness.parking.endPrompt != nil)
        #expect(await harness.notifier.withdrawCount == 0)
    }

    @Test("Confirming a candidate that expired while the form was open ends nothing and keeps the proposal")
    func expiredCandidateConfirmSettlesNothing() async throws {
        // Arrange — the form captured the candidate, then its 45 minutes ran out.
        let harness = try harness()
        let oldId = try harness.active.id
        let captured = TestCandidate.make(detectedAt: Self.now)
        let (model, _) = candidates(over: harness, pending: captured)
        harness.clock.advance(by: ParkingCandidatePolicy.expiry + 1)

        // Act
        let confirmed = model.confirm(captured, draft: ManualParkingDraft(floorText: "B1"))

        // Assert
        #expect(!confirmed)
        try await expectNothingSettled(harness, oldId: oldId)
    }

    @Test("Confirming a candidate that was superseded while the form was open ends nothing and keeps the proposal")
    func supersededCandidateConfirmSettlesNothing() async throws {
        // Arrange — a newer candidate replaced the one the form captured.
        let harness = try harness()
        let oldId = try harness.active.id
        let captured = TestCandidate.make(detectedAt: Self.now)
        let (model, store) = candidates(over: harness, pending: captured)
        try store.save(TestCandidate.make(detectedAt: Self.now.addingTimeInterval(60)))

        // Act
        let confirmed = model.confirm(captured, draft: ManualParkingDraft(floorText: "B1"))

        // Assert
        #expect(!confirmed)
        try await expectNothingSettled(harness, oldId: oldId)
    }

    @Test("Ending the parking by hand withdraws the proposal and ends it when the user said")
    func manualEndWithdrawsProposal() async throws {
        // Arrange
        let harness = try harness()
        let id = try harness.active.id

        // Act
        #expect(harness.parking.endActiveParking())
        await harness.settle()

        // Assert
        #expect(harness.parking.session(id: id)?.endedAt == Self.now)
        #expect(harness.proposals.load() == nil)
        #expect(await harness.notifier.withdrawCount >= 1)
        #expect(harness.analytics.payloads.isEmpty)
    }

    // MARK: - Notification actions

    @Test("The notification's 주차 종료 ends the record at departedAt")
    func notificationEndAction() async throws {
        // Arrange
        let harness = try harness()
        let id = try harness.active.id
        let responder = ParkingEndProposalResponder(model: harness.parking)

        // Act
        responder.handle(actionIdentifier: ParkingEndProposalAction.endParking, userInfo: [:])
        await harness.settle()

        // Assert
        #expect(harness.parking.session(id: id)?.endedAt == Self.departedAt)
        #expect(harness.proposals.load() == nil)
    }

    @Test("The notification's 아직 주차 중 keeps the record and reports user_kept_parking")
    func notificationKeepAction() async throws {
        // Arrange
        let harness = try harness()
        let responder = ParkingEndProposalResponder(model: harness.parking)

        // Act
        responder.handle(actionIdentifier: ParkingEndProposalAction.keepParking, userInfo: [:])
        await harness.settle()

        // Assert
        #expect(harness.parking.activeSession != nil)
        #expect(harness.detection.keptAt == [Self.now])
        #expect(harness.proposals.load() == nil)
    }

    @Test("A tap or a swipe is not an answer: the proposal stays pending")
    func notificationBodyTapIsNotAnAnswer() async throws {
        // Arrange
        let harness = try harness()
        let responder = ParkingEndProposalResponder(model: harness.parking)

        // Act
        responder.handle(actionIdentifier: "com.apple.UNNotificationDefaultActionIdentifier", userInfo: [:])
        responder.handle(actionIdentifier: "com.apple.UNNotificationDismissActionIdentifier", userInfo: [:])
        await harness.settle()

        // Assert
        #expect(harness.parking.endPrompt != nil)
        #expect(harness.proposals.load() != nil)
        #expect(harness.detection.keptAt.isEmpty)
    }

    @Test("The router hands a departure response to the proposal responder, not the candidate's")
    func routerDispatchesTheDepartureCategory() async throws {
        // Arrange
        let harness = try harness()
        let router = PKNotificationRouter()
        router.register { ParkingEndProposalResponder(model: harness.parking) }

        // Act
        router.dispatch(
            category: ParkingEndProposalAction.categoryIdentifier,
            actionIdentifier: ParkingEndProposalAction.keepParking,
            userInfo: [:]
        )
        await harness.settle()

        // Assert
        #expect(harness.detection.keptAt == [Self.now])
    }
}

/// A real store whose every write that creates a record fails, as a full disk or a
/// SwiftData save error would. Ending a record on its own still works — that is exactly
/// the half-done state the next-parking path must never leave behind.
@MainActor
private final class NewRecordWriteFailingParkingStore: ParkingStoring {
    private let base: SwiftDataParkingStore

    init(_ base: SwiftDataParkingStore) {
        self.base = base
    }

    func activeSession() throws -> ParkingSession? {
        try base.activeSession()
    }

    func completedSessions(limit: Int?) throws -> [ParkingSession] {
        try base.completedSessions(limit: limit)
    }

    func session(id: UUID) throws -> ParkingSession? {
        try base.session(id: id)
    }

    func startSession(_: ParkingSession) throws {
        throw ParkingStoreError.writeFailed("injected")
    }

    func update(_ session: ParkingSession) throws {
        try base.update(session)
    }

    func endSession(id: UUID, at endedAt: Date) throws {
        try base.endSession(id: id, at: endedAt)
    }

    func replaceActiveSession(ending _: UUID, at _: Date, with _: ParkingSession) throws {
        throw ParkingStoreError.writeFailed("injected")
    }

    func delete(id: UUID) throws {
        try base.delete(id: id)
    }

    func deleteAll() throws {
        try base.deleteAll()
    }
}

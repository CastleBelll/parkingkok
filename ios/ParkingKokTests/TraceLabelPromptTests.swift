import Foundation
import Testing
import UserNotifications
@testable import ParkingKok

/// docs/05 §9 labelling: the in-app screen was never used, so the label is asked for from
/// the lock screen the moment a session closes. These state the four rules that decide
/// whether anything is asked at all, and what one tap writes.
@Suite("Trace label prompt")
struct TraceLabelPromptTests {
    /// A fixed zone so the body text is a value the test can state, not the machine's.
    private let seoul = TimeZone(identifier: "Asia/Seoul") ?? .gmt

    private func recorder(_ store: StubTraceStore, prompter: StubLabelPrompter) -> TraceRecorder {
        TraceRecorder(store: store, metadata: TestTrace.metadata, prompter: prompter)
    }

    private func sample(
        _ offset: TimeInterval,
        automotive: Bool = false,
        walking: Bool = false,
        stationary: Bool = false
    ) -> MotionSample {
        MotionSample(
            timestamp: TestTime.offset(offset),
            automotive: automotive,
            walking: walking,
            stationary: stationary,
            running: false,
            confidence: .high
        )
    }

    // MARK: - When a prompt is asked for

    /// The change itself: a real trip that stopped growing is asked about, once, with the
    /// id of the session that closed rather than the one that opened.
    @Test("Rotation asks for a label on the session that just closed")
    func rotationRequestsPrompt() {
        // Arrange
        let store = StubTraceStore()
        let prompter = StubLabelPrompter()
        var recorder = recorder(store, prompter: prompter)

        // Act — a drive, then silence past the idle gap, then a new trip.
        recorder.record(motionSamples: [sample(0, automotive: true), sample(600, walking: true)])
        let closedId = recorder.openSessionId
        recorder.record(motionSamples: [sample(TraceSessionBoundaryPolicy.idleGap + 700, stationary: true)])

        // Assert
        #expect(prompter.prompts.map(\.sessionId) == [closedId])
        #expect(prompter.prompts.first?.eventCount == 3)
    }

    /// §9 closes the open session when the user opts out, and a session that can never grow
    /// again is exactly as worth labelling as a rotated one.
    @Test("Switching recording off asks for a label on the open session")
    func optOutRequestsPrompt() {
        // Arrange
        let store = StubTraceStore()
        let prompter = StubLabelPrompter()
        var recorder = recorder(store, prompter: prompter)
        recorder.record(motionSamples: [sample(0, automotive: true), sample(300, walking: true)])
        let openId = recorder.openSessionId

        // Act
        recorder.closeOpenSession()

        // Assert
        #expect(prompter.prompts.map(\.sessionId) == [openId])
    }

    /// The condition that keeps the prompt answerable. A session of three `location` events
    /// holds nothing a person could recognise: they were not told anything was recording,
    /// and no fix distinguishes a bus from a desk. Ask only what can be answered.
    @Test("A session with no motion event is never prompted for")
    func locationOnlySessionIsNotPrompted() {
        // Arrange
        let store = StubTraceStore()
        let prompter = StubLabelPrompter()
        var recorder = recorder(store, prompter: prompter)

        // Act — significant-change samples only, then a rotation.
        for offset in stride(from: 0.0, through: 60.0, by: 30.0) {
            recorder.record(qualitySample: LocationQualitySample(
                timestamp: TestTime.offset(offset),
                horizontalAccuracy: 12
            ))
        }
        #expect(store.latestSession?.events.count == 3)
        recorder.record(qualitySample: LocationQualitySample(
            timestamp: TestTime.offset(TraceSessionBoundaryPolicy.idleGap + 120),
            horizontalAccuracy: 12
        ))

        // Assert
        #expect(prompter.prompts.isEmpty)
    }

    /// A non-viable session is deleted at rotation, so a label tapped onto it would have
    /// nowhere to land.
    @Test("A session discarded as non-viable is never prompted for")
    func nonViableSessionIsNotPrompted() {
        // Arrange
        let store = StubTraceStore()
        let prompter = StubLabelPrompter()
        var recorder = recorder(store, prompter: prompter)

        // Act — one lone edge, then a rotation.
        recorder.record(motionSamples: [sample(0, stationary: true)])
        let lonelyId = recorder.openSessionId
        recorder.record(motionSamples: [sample(TraceSessionBoundaryPolicy.idleGap + 60, automotive: true)])

        // Assert
        #expect(store.nonViableDiscards == [lonelyId])
        #expect(prompter.prompts.isEmpty)
    }

    /// An open session may still grow, so §9's rule that viability is judged only at
    /// rotation applies to prompting too.
    @Test("An open session is not prompted for while it can still grow")
    func openSessionIsNotPrompted() {
        // Arrange
        let store = StubTraceStore()
        let prompter = StubLabelPrompter()
        var recorder = recorder(store, prompter: prompter)

        // Act
        recorder.record(motionSamples: [sample(0, automotive: true), sample(60, walking: true)])

        // Assert
        #expect(prompter.prompts.isEmpty)
    }

    // MARK: - Permission

    @Test("A permitted prompt is delivered and nothing is counted")
    func authorizedPromptIsDelivered() async throws {
        // Arrange
        let store = StubTraceStore()
        let delivery = StubLabelPromptDelivery(authorized: true)
        let prompter = TraceLabelPrompter(delivery: delivery, onSuppressed: { store.recordLabelPromptSuppressed() })
        let prompt = try #require(TraceLabelPrompt.of(driveSession()))

        // Act
        await prompter.resolvePrompt(prompt)

        // Assert
        #expect(delivery.deliveredPrompts.map(\.sessionId) == [prompt.sessionId])
        #expect(store.labelPromptSuppressedCount == 0)
    }

    /// The denial path §9 requires to be visible: nothing is shown, nothing is retried, and
    /// the count is the only thing that says a field run had no chance of collecting labels.
    @Test("Without notification permission the prompt is skipped and counted")
    func unauthorizedPromptIsCountedOnly() async throws {
        // Arrange
        let store = StubTraceStore()
        let delivery = StubLabelPromptDelivery(authorized: false)
        let prompter = TraceLabelPrompter(delivery: delivery, onSuppressed: { store.recordLabelPromptSuppressed() })
        let prompt = try #require(TraceLabelPrompt.of(driveSession()))

        // Act
        await prompter.resolvePrompt(prompt)

        // Assert
        #expect(delivery.deliveredPrompts.isEmpty)
        #expect(store.labelPromptSuppressedCount == 1)
        #expect(store.summary().labelPromptSuppressedCount == 1)
    }

    // MARK: - What a tap writes

    @Test("Tapping a mode writes that label to the session")
    func tapWritesLabel() throws {
        // Arrange
        let store = StubTraceStore()
        let session = driveSession()
        try store.write(session)
        let responder = TraceLabelPromptResponder(store: store)

        // Act
        let applied = responder.applyLabel(
            actionIdentifier: TraceLabelPromptAction.identifier(for: .subway),
            sessionId: session.sessionId
        )

        // Assert — a subway parks nothing, so `parked` is answered without a second tap.
        #expect(applied)
        let stored = try #require(store.load(id: session.sessionId))
        #expect(stored.label == TraceLabel(mode: .subway, parked: false, note: nil))
    }

    /// A car is the one mode where parking stays genuinely open, so the tap must not claim
    /// an answer it does not have (§9 makes `parked: null` a first-class value).
    @Test("Tapping 자동차 leaves parked unanswered")
    func carLeavesParkedUnknown() {
        #expect(TraceLabelPrompt.label(for: .car) == TraceLabel(mode: .car, parked: nil, note: nil))
        #expect(TraceLabelPrompt.label(for: .bus).parked == false)
        #expect(TraceLabelPrompt.label(for: .walk).parked == false)
    }

    /// The body tap opens the app, where the existing labelling screen takes over; it must
    /// not silently write a mode nobody chose.
    @Test("The body tap writes nothing")
    func defaultActionWritesNothing() throws {
        // Arrange
        let store = StubTraceStore()
        let session = driveSession()
        try store.write(session)
        let responder = TraceLabelPromptResponder(store: store)

        // Act
        let applied = responder.applyLabel(
            actionIdentifier: UNNotificationDefaultActionIdentifier,
            sessionId: session.sessionId
        )

        // Assert
        #expect(!applied)
        #expect(store.load(id: session.sessionId)?.label.isLabeled == false)
    }

    /// The rolling cap can evict a session between the prompt and the tap, which is an
    /// ordinary outcome and must not throw out of a notification callback.
    @Test("Tapping a session the store no longer has is refused, not thrown")
    func missingSessionIsRefused() {
        // Arrange
        let responder = TraceLabelPromptResponder(store: StubTraceStore())

        // Act & Assert
        #expect(!responder.applyLabel(
            actionIdentifier: TraceLabelPromptAction.identifier(for: .car),
            sessionId: UUID()
        ))
    }

    @Test("An unknown action identifier maps to no mode")
    func unknownActionIdentifier() {
        #expect(TraceLabelPromptAction.mode(forActionIdentifier: "com.other.action") == nil)
        #expect(TraceLabelPromptAction.mode(forActionIdentifier: UNNotificationDefaultActionIdentifier) == nil)
        for mode in TraceLabelPrompt.offeredModes {
            let identifier = TraceLabelPromptAction.identifier(for: mode)
            #expect(TraceLabelPromptAction.mode(forActionIdentifier: identifier) == mode)
        }
    }

    // MARK: - What the user reads

    /// The body has to be recognisable as *that* trip — 17 minutes in a vehicle then a walk
    /// — which is what makes a one-tap answer trustworthy rather than a guess.
    @Test("The body states the time range, the length and what the device saw")
    func bodyDescribesTheTrip() throws {
        // Arrange — 05:26 → 05:55 KST, a 17-minute ride followed by a walk.
        let start = Date(traceMillis: 1_780_000_000_000)
        let events = [
            TraceEvent.motion(.vehicleEnter, at: start, confidence: .high),
            TraceEvent.motion(.vehicleExit, at: start.addingTimeInterval(17 * 60), confidence: .high),
            TraceEvent.motion(.walkingEnter, at: start.addingTimeInterval(18 * 60), confidence: .high)
        ]
        let session = TestTrace.session(
            startedAt: start,
            endedAt: start.addingTimeInterval(29 * 60),
            events: events
        )

        // Act
        let prompt = try #require(TraceLabelPrompt.of(session))
        let body = prompt.body(timeZone: seoul)

        // Assert
        #expect(prompt.movementSummary == "차량 17분 + 도보")
        #expect(body == "05:26–05:55 · 29분 · 차량 17분 + 도보 · 이벤트 3개")
    }

    /// Core Motion routinely reports one side of the vehicle pair only, and an unclosed ride
    /// ran to the end of the recording — not to zero minutes.
    @Test("A ride with no exit is measured to the end of the session")
    func unclosedVehicleSpanRunsToSessionEnd() throws {
        // Arrange
        let start = TestTime.offset(0)
        let session = TestTrace.session(
            startedAt: start,
            endedAt: start.addingTimeInterval(12 * 60),
            events: [
                TraceEvent.motion(.stationaryExit, at: start, confidence: .high),
                TraceEvent.motion(.vehicleEnter, at: start.addingTimeInterval(2 * 60), confidence: .high)
            ]
        )

        // Act
        let prompt = try #require(TraceLabelPrompt.of(session))

        // Assert
        #expect(prompt.movementSummary == "차량 10분 + 정지")
    }

    /// The whole file exists to leave the device's location behind. A notification body is
    /// read on a lock screen and photographed into bug reports, so it is held to the same
    /// rule as the trace itself (CLAUDE.md Hard Constraints).
    @Test("The prompt text carries no coordinate and no accuracy")
    func bodyCarriesNoCoordinate() throws {
        // Arrange — a session whose location events carry accuracy, speed and distance.
        let start = TestTime.offset(0)
        let session = TestTrace.session(
            startedAt: start,
            endedAt: start.addingTimeInterval(300),
            events: [
                TraceEvent.motion(.vehicleEnter, at: start, confidence: .high),
                TraceEvent.location(
                    at: start.addingTimeInterval(60),
                    accuracy: 37.5,
                    speed: 12.25,
                    distanceFromPreviousM: 812.5
                ),
                TraceEvent.motion(.vehicleExit, at: start.addingTimeInterval(240), confidence: .high)
            ]
        )

        // Act
        let prompt = try #require(TraceLabelPrompt.of(session))
        let text = "\(TraceLabelPrompt.title) \(prompt.body(timeZone: seoul))"

        // Assert — no coordinate exists to leak, and none of the location detail is quoted.
        for forbidden in ["37.5", "12.25", "812.5", "latitude", "longitude"] {
            #expect(!text.contains(forbidden), "prompt text leaked \(forbidden)")
        }
    }

    /// Four is iOS's ceiling for an expanded notification, and the order is what decides who
    /// survives Android's three. Stated here so a future edit cannot quietly add a fifth.
    @Test("Four modes are offered, most useful first")
    func offeredModes() {
        #expect(TraceLabelPrompt.offeredModes == [.car, .bus, .subway, .walk])
        #expect(TraceLabelPrompt.offeredModes.count <= UserNotificationLabelPromptDelivery.maximumActionCount)
        #expect(!TraceLabelPrompt.offeredModes.contains(.unknown))
    }

    // MARK: - The real notification service

    /// The half only the OS can answer: that `UNUserNotificationCenter` accepts this
    /// category, keeps all four actions, and takes the request.
    ///
    /// iOS silently truncates a category with too many actions, and a malformed one is
    /// rejected outright — neither shows up in a stubbed test. Run on the reference iPhone
    /// (docs/04 field checklist) as well as in the simulator.
    ///
    /// Whether the notification is *shown* depends on a permission no test can grant on a
    /// physical device, so that is asserted only when the device happens to be authorized;
    /// the unauthorized case is what `labelPromptSuppressedCount` reports.
    @Test("The real notification category keeps all four one-tap actions")
    func realCategoryRegistersItsActions() async throws {
        // Arrange
        let center = UNUserNotificationCenter.current()
        let delivery = UserNotificationLabelPromptDelivery()
        let prompt = try #require(TraceLabelPrompt.of(driveSession()))

        // Act
        await delivery.deliver(prompt)

        // Assert
        let categories = await center.notificationCategories()
        let ours = try #require(categories.first { $0.identifier == TraceLabelPromptAction.categoryIdentifier })
        #expect(ours.actions.count == TraceLabelPrompt.offeredModes.count)
        #expect(ours.actions.map(\.title) == ["자동차", "버스", "지하철", "도보"])
        // A foreground action would reintroduce the app launch this change exists to remove.
        #expect(ours.actions.allSatisfy { !$0.options.contains(.foreground) })

        if await delivery.isAuthorized() {
            let delivered = await center.deliveredNotifications()
            #expect(delivered.contains {
                $0.request.content.categoryIdentifier == TraceLabelPromptAction.categoryIdentifier
            })
        }

        // Leave nothing behind in Notification Center for the next run to trip over.
        let identifier = "\(TraceLabelPromptAction.categoryIdentifier).\(prompt.sessionId.uuidString)"
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
    }

    private func driveSession() -> TraceSession {
        let start = TestTime.offset(0)
        return TestTrace.session(
            startedAt: start,
            endedAt: start.addingTimeInterval(600),
            events: [
                TraceEvent.motion(.vehicleEnter, at: start, confidence: .high),
                TraceEvent.motion(.vehicleExit, at: start.addingTimeInterval(540), confidence: .high)
            ]
        )
    }
}

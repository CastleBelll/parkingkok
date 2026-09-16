import Foundation
import Testing
@testable import ParkingKok

@Suite("Trace recording")
struct TraceRecorderTests {
    private func recorder(_ store: StubTraceStore) -> TraceRecorder {
        TraceRecorder(store: store, metadata: TestTrace.metadata)
    }

    private func sample(
        _ offset: TimeInterval,
        automotive: Bool = false,
        walking: Bool = false,
        stationary: Bool = false,
        running: Bool = false,
        confidence: MotionConfidence = .high
    ) -> MotionSample {
        MotionSample(
            timestamp: TestTime.offset(offset),
            automotive: automotive,
            walking: walking,
            stationary: stationary,
            running: running,
            confidence: confidence
        )
    }

    private func types(_ session: TraceSession?) -> [String] {
        (session?.events ?? []).map(\.type.rawValue)
    }

    // MARK: - Privacy

    /// The whole reason this file exists is to be copied off the device, so a coordinate
    /// reaching it turns every recorded trip into a parking-location log
    /// (docs/05 §9, CLAUDE.md Hard Constraints). `TraceEvent` has no coordinate member,
    /// but `TraceRecorder` is handed `LocationFix` values that do — so this checks the
    /// encoded bytes, exactly as the diagnostics export does.
    @Test("No coordinate survives into the encoded trace, though every fix carried one")
    func encodedTraceCarriesNoCoordinate() throws {
        // Arrange — distinctive values that would be unmistakable in the output.
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        for step in 0 ... 4 {
            recorder.record(fix: LocationFix(
                timestamp: TestTime.offset(Double(step) * 20),
                latitude: 37.123_456_7 + Double(step) * 0.001,
                longitude: 127.987_654_3 + Double(step) * 0.001,
                horizontalAccuracy: 8,
                speed: 12
            ))
        }

        // Assert
        let session = try #require(store.latestSession)
        let data = try JSONEncoder().encode(session)
        let json = try #require(String(data: data, encoding: .utf8))
        #expect(!json.contains("37.12"))
        #expect(!json.contains("127.98"))
        #expect(!json.lowercased().contains("latitude"))
        #expect(!json.lowercased().contains("longitude"))
        #expect(!json.lowercased().contains("coordinate"))
        // The derived value is what survives instead.
        #expect(session.events.contains { $0.distanceFromPreviousM != nil })
    }

    // MARK: - Recording needs no vehicle evidence

    /// The defect this boundary exists to fix. Capture only opens on vehicle evidence, so
    /// tying a trace to the bounded driving session meant a walk or a subway ride — the
    /// negative cases docs/05 §17 needs, and the only ones collectable without a car — was
    /// never recorded at all (docs/05 §9).
    @Test("A walk opens a session, with no vehicle evidence anywhere in it")
    func walkingOpensASessionWithoutVehicleEvidence() throws {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act — leave the house, walk, wait for a train, walk again.
        recorder.record(motionSamples: [
            sample(0, stationary: true),
            sample(30, walking: true),
            sample(600, stationary: true),
            sample(900, walking: true)
        ])

        // Assert
        let session = try #require(store.latestSession)
        #expect(types(session) == [
            "stationary_enter",
            "walking_enter",
            "stationary_exit",
            "stationary_enter",
            "walking_enter",
            "stationary_exit"
        ])
        #expect(!session.events.contains { $0.type == .vehicleEnter })
        #expect(recorder.isRecording)
    }

    /// A subway ride underground may produce nothing but significant-change wakes, and
    /// Core Motion may never call it `automotive`. It still has to be recorded.
    @Test("A lone significant-change sample opens a session")
    func significantChangeOpensASession() throws {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(qualitySample: LocationQualitySample(
            timestamp: TestTime.offset(5),
            horizontalAccuracy: 60
        ))

        // Assert
        let events = try #require(store.latestSession?.events)
        #expect(events.count == 1)
        #expect(events[0].accuracy == 60)
        #expect(events[0].speed == nil)
        #expect(events[0].distanceFromPreviousM == nil)
    }

    @Test("A fix opens a session on its own")
    func locationOpensASession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(0)))

        // Assert
        #expect(store.storedSessions.count == 1)
        #expect(types(store.latestSession) == ["location"])
    }

    /// An activity §2 has no wire vocabulary for. It must not leave an empty trace behind.
    @Test("An activity with no contract vocabulary records nothing at all")
    func unmappedActivityOpensNoSession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(motionSamples: [sample(0, running: true)])

        // Assert
        #expect(store.storedSessions.isEmpty)
        #expect(!recorder.isRecording)
    }

    // MARK: - Session boundary

    @Test("Events inside the idle gap stay in one session")
    func eventsInsideTheGapShareASession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act — 29 min 59 s apart.
        recorder.record(motionSamples: [sample(0, walking: true)])
        recorder.record(motionSamples: [sample(29 * 60 + 59, stationary: true)])

        // Assert
        #expect(store.storedSessions.count == 1)
        #expect(types(store.latestSession) == ["walking_enter", "stationary_enter"])
    }

    @Test("Silence for the idle gap starts the next trip")
    func idleGapRotatesTheSession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act — the commute out, half an hour at the desk, the commute back.
        recorder.record(motionSamples: [sample(0, automotive: true)])
        recorder.record(motionSamples: [sample(TraceSessionBoundaryPolicy.idleGap, walking: true)])

        // Assert — two trips, and the second starts at its own first event.
        #expect(store.storedSessions.count == 2)
        let sessions = store.storedSessions
        #expect(types(sessions[0]) == ["vehicle_enter"])
        #expect(types(sessions[1]) == ["walking_enter"])
        #expect(sessions[1].startDate == TestTime.offset(TraceSessionBoundaryPolicy.idleGap))
    }

    @Test("A session that never falls silent rotates at the duration cap")
    func durationCapRotatesTheSession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        let cap = TraceSessionBoundaryPolicy.maximumDuration

        // Act — a fix every 15 minutes, past four hours.
        for step in 0 ... Int(cap / (15 * 60)) {
            recorder.record(fix: TestGeo.fix(
                at: TestTime.offset(Double(step) * 15 * 60),
                metersNorth: Double(step) * 100
            ))
        }

        // Assert
        #expect(store.storedSessions.count == 2)
        #expect(store.latestSession?.startDate == TestTime.offset(cap))
    }

    /// Replaces the old per-session truncation. A trace that silently stopped growing looks
    /// exactly like a trip that ended, which is the one thing a recording must not lie about.
    @Test("A session rotates at the event ceiling instead of being truncated")
    func eventCapRotatesRatherThanTruncates() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        let cap = TraceSessionBoundaryPolicy.maximumEvents

        // Act — significant-change samples a second apart, so only the count can fire:
        // 1050 of them stay well inside both the idle gap and the duration cap.
        for step in 0 ..< (cap + 50) {
            recorder.record(qualitySample: LocationQualitySample(
                timestamp: TestTime.offset(Double(step)),
                horizontalAccuracy: 10
            ))
        }

        // Assert — nothing was dropped; the overflow went into the next session.
        #expect(store.storedSessions.count == 2)
        #expect(store.storedSessions[0].events.count == cap)
        #expect(store.latestSession?.events.count == 50)
    }

    /// An NTP correction mid-trip would otherwise write a session whose events run
    /// backwards, which the converter cannot turn into a fixture.
    @Test("A clock jump backwards splits rather than corrupting the recording")
    func clockJumpRotatesTheSession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(qualitySample: LocationQualitySample(timestamp: TestTime.offset(0), horizontalAccuracy: 10))
        recorder.record(qualitySample: LocationQualitySample(timestamp: TestTime.offset(-60), horizontalAccuracy: 10))

        // Assert
        #expect(store.storedSessions.count == 2)
        #expect(store.latestSession?.startDate == TestTime.offset(-60))
    }

    @Test("Ordinary clock skew keeps one session")
    func clockSkewKeepsOneSession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act — an event stamped a second before the session opened.
        recorder.record(qualitySample: LocationQualitySample(timestamp: TestTime.offset(0), horizontalAccuracy: 10))
        recorder.record(qualitySample: LocationQualitySample(timestamp: TestTime.offset(-1), horizontalAccuracy: 10))

        // Assert
        #expect(store.storedSessions.count == 1)
        #expect(store.latestSession?.events.count == 2)
    }

    /// docs/05 §9: the opt-in is the one boundary the user controls. The next event must
    /// start a new trip rather than extending the one they opted out of.
    @Test("Opting out closes the open session immediately")
    func optingOutClosesTheSession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        recorder.record(motionSamples: [sample(0, walking: true)])

        // Act
        recorder.closeOpenSession()

        // Assert
        #expect(!recorder.isRecording)
        #expect(store.openSessionId == nil)

        // And the next event, well inside the idle gap, is a new trip.
        recorder.record(motionSamples: [sample(60, stationary: true)])
        #expect(store.storedSessions.count == 2)
    }

    @Test("The session being recorded is protected from the rolling cap")
    func openSessionIsProtectedFromEviction() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(motionSamples: [sample(0, walking: true)])

        // Assert
        #expect(store.pruneProtectedIds == [recorder.openSessionId])
    }

    // MARK: - Process death

    /// §9 defines a session as a run of events with no long silence in it — a property of
    /// the stream, not of the process. iOS is relaunched for every significant change, so a
    /// session per process would cut a walk to the station into a handful of files.
    @Test("A relaunch inside the idle gap continues the open session")
    func relaunchContinuesTheOpenSession() {
        // Arrange — a process that recorded a walk and then died.
        let store = StubTraceStore()
        var first = recorder(store)
        first.record(motionSamples: [sample(0, walking: true)])
        let sessionId = first.openSessionId

        // Act — a new process over the same store.
        var second = recorder(store)
        second.record(qualitySample: LocationQualitySample(
            timestamp: TestTime.offset(300),
            horizontalAccuracy: 30
        ))

        // Assert
        #expect(second.openSessionId == sessionId)
        #expect(store.storedSessions.count == 1)
        #expect(types(store.latestSession) == ["walking_enter", "location"])
    }

    @Test("A relaunch past the idle gap starts a new session")
    func relaunchPastTheGapRotates() {
        // Arrange
        let store = StubTraceStore()
        var first = recorder(store)
        first.record(motionSamples: [sample(0, walking: true)])

        // Act
        var second = recorder(store)
        second.record(qualitySample: LocationQualitySample(
            timestamp: TestTime.offset(TraceSessionBoundaryPolicy.idleGap),
            horizontalAccuracy: 30
        ))

        // Assert
        #expect(second.openSessionId != first.openSessionId)
        #expect(store.storedSessions.count == 2)
    }

    /// The motion edges are inferred from history (docs/05 §2), so a restored session that
    /// forgot them would emit a second `vehicle_enter` for a drive that never stopped.
    @Test("A restored session does not restate an activity that never changed")
    func restoredSessionKeepsItsMotionEdges() {
        // Arrange — the drive is under way when the process dies.
        let store = StubTraceStore()
        var first = recorder(store)
        first.record(motionSamples: [sample(0, automotive: true)])

        // Act — the same history is replayed on the next wake, plus one new sample.
        var second = recorder(store)
        second.record(motionSamples: [sample(0, automotive: true), sample(60, automotive: true)])

        // Assert
        #expect(types(store.latestSession) == ["vehicle_enter"])
    }

    @Test("A restored walk is not entered twice")
    func restoredWalkIsNotReEntered() {
        // Arrange
        let store = StubTraceStore()
        var first = recorder(store)
        first.record(motionSamples: [sample(0, stationary: true), sample(30, walking: true)])

        // Act
        var second = recorder(store)
        second.record(motionSamples: [sample(90, walking: true)])

        // Assert
        #expect(types(store.latestSession) == ["stationary_enter", "walking_enter", "stationary_exit"])
    }

    /// The coordinate is never written to disk, so it cannot come back. Claiming the whole
    /// step from before the relaunch would report travel that never happened in one hop.
    @Test("The first fix after a relaunch carries no distance")
    func restoredSessionHasNoDistanceAnchor() throws {
        // Arrange
        let store = StubTraceStore()
        var first = recorder(store)
        first.record(fix: TestGeo.fix(at: TestTime.offset(0), metersNorth: 0))

        // Act
        var second = recorder(store)
        second.record(fix: TestGeo.fix(at: TestTime.offset(60), metersNorth: 1000))

        // Assert
        let events = try #require(store.latestSession?.events)
        #expect(events.count == 2)
        #expect(events[1].distanceFromPreviousM == nil)
    }

    // MARK: - Motion vocabulary

    @Test("Core Motion flags become §2's normalized transitions, in fixture order")
    func motionTransitionsUseContractVocabulary() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act — a drive that stops at a light, parks, and walks away.
        recorder.record(motionSamples: [
            sample(0, automotive: true),
            sample(60, automotive: true, stationary: true),
            sample(90, automotive: true),
            sample(240, walking: true),
            sample(300, stationary: true)
        ])

        // Assert
        #expect(types(store.latestSession) == [
            "vehicle_enter",
            "stationary_enter",
            "stationary_exit",
            "vehicle_exit",
            "walking_enter",
            "stationary_enter"
        ])
    }

    /// iOS has no vehicle-exit transition of its own; §2 says the semantics are inferred
    /// from history. The exit above is that inference, and it must not fire on a slice
    /// where Core Motion simply had no opinion.
    @Test("An opinion-free sample never fabricates a transition")
    func unknownActivityDoesNotMoveEdges() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(motionSamples: [
            sample(0, automotive: true),
            sample(30),
            sample(60, automotive: true)
        ])

        // Assert — one enter, and no exit/re-enter pair invented by the gap.
        #expect(types(store.latestSession) == ["vehicle_enter"])
    }

    /// Motion history is re-queried on every wake, so the same samples arrive repeatedly.
    /// Without the watermark a commute would record one `vehicle_enter` per wake.
    @Test("Replayed history is recorded once, not once per wake")
    func replayedHistoryIsIdempotent() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        let history = [sample(0, automotive: true), sample(120, walking: true)]

        // Act
        recorder.record(motionSamples: history)
        recorder.record(motionSamples: history)
        recorder.record(motionSamples: history + [sample(180, stationary: true)])

        // Assert
        #expect(types(store.latestSession) == ["vehicle_enter", "vehicle_exit", "walking_enter", "stationary_enter"])
    }

    // MARK: - Location

    @Test("Fixes are downsampled, and the dropped distance is carried into the next event")
    func locationIsDownsampledWithoutLosingDistance() throws {
        // Arrange — 1 Hz fixes moving 10 m each, for 40 s.
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        for second in 0 ... 40 {
            recorder.record(fix: TestGeo.fix(
                at: TestTime.offset(Double(second)),
                metersNorth: Double(second) * 10
            ))
        }

        // Assert — one event per 15 s window, not 41 of them.
        let events = try #require(store.latestSession?.events)
        #expect(events.count == 3)
        #expect(events.map(\.atMillis) == [0, 15, 30].map { TestTime.offset(Double($0)).traceMillis })
        #expect(events[0].distanceFromPreviousM == nil)
        // 15 fixes × 10 m, summed across the samples downsampling dropped.
        #expect(try #require(events[1].distanceFromPreviousM).isApproximately(150))
        #expect(try #require(events[2].distanceFromPreviousM).isApproximately(150))
    }

    @Test("A GPS jump adds no distance and does not become the next anchor")
    func implausibleStepsAreNotTravel() throws {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act — a 50 km jump one second later, then a legitimate 200 m step.
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(0), metersNorth: 0))
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(1), metersNorth: 50000))
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(20), metersNorth: 200))

        // Assert
        let events = try #require(store.latestSession?.events)
        #expect(events.count == 2)
        #expect(try #require(events[1].distanceFromPreviousM).isApproximately(200))
    }

    @Test("Falling quality is recorded as a bucket transition, never as accuracy alone")
    func qualityDegradationIsRecorded() throws {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act — good, then a tunnel two seconds later, well inside the sampling interval.
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(0), accuracy: 8))
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(2), accuracy: 120))

        // Assert — the degradation forces the sample through the downsampler.
        let events = try #require(store.latestSession?.events)
        #expect(types(store.latestSession) == ["location", "location", "location_quality_degraded"])
        #expect(events[2].fromBucket == .good)
        #expect(events[2].toBucket == .poor)
    }

    @Test("Improving quality is not a degradation")
    func recoveryEmitsNoTransition() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(0), accuracy: 120))
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(20), accuracy: 8))

        // Assert
        #expect(types(store.latestSession) == ["location", "location"])
    }

    // MARK: - Persistence

    /// A file left behind by process death must already be usable. Rewriting `endedAt` on
    /// every append is what removes the need for a repair pass on the next launch.
    @Test("endedAt tracks the newest event, so an abandoned file is still complete")
    func endedAtAdvancesWithEveryAppend() throws {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(motionSamples: [sample(0, automotive: true)])
        let afterFirst = try #require(store.latestSession?.endDate)
        recorder.record(motionSamples: [sample(120, walking: true)])
        let afterSecond = try #require(store.latestSession?.endDate)

        // Assert
        #expect(afterFirst == TestTime.offset(0))
        #expect(afterSecond == TestTime.offset(120))
    }

    /// Recording is instrumentation. A failing write must surface in diagnostics and
    /// leave the detection path alone (docs/05 §9 best-effort).
    @Test("A write failure is recorded, not thrown")
    func writeFailureIsBestEffort() {
        // Arrange
        let store = StubTraceStore(writeError: .writeFailed("NSCocoaErrorDomain(513)"))
        var recorder = recorder(store)

        // Act
        recorder.record(motionSamples: [sample(0, automotive: true)])

        // Assert
        #expect(recorder.lastFailure?.contains("513") == true)
        #expect(recorder.isRecording)
    }

    /// The pointer is what lets the next process find the open session, so it must never
    /// name a session whose file failed to land.
    @Test("The open-session pointer only moves after the file is written")
    func pointerFollowsASuccessfulWrite() {
        // Arrange
        let store = StubTraceStore(writeError: .writeFailed("NSCocoaErrorDomain(513)"))
        var recorder = recorder(store)

        // Act
        recorder.record(motionSamples: [sample(0, automotive: true)])

        // Assert
        #expect(store.openSessionId == nil)
    }
}

private extension Double {
    /// Haversine on a sphere, so metre expectations are compared with a tolerance rather
    /// than for equality.
    func isApproximately(_ other: Double, tolerance: Double = 1) -> Bool {
        abs(self - other) <= tolerance
    }
}

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
        confidence: MotionConfidence = .high
    ) -> MotionSample {
        MotionSample(
            timestamp: TestTime.offset(offset),
            automotive: automotive,
            walking: walking,
            stationary: stationary,
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
        recorder.beginSession(at: TestTime.offset(0))

        for step in 0 ... 4 {
            recorder.record(fix: LocationFix(
                timestamp: TestTime.offset(Double(step) * 20),
                latitude: 37.123_456_7 + Double(step) * 0.001,
                longitude: 127.987_654_3 + Double(step) * 0.001,
                horizontalAccuracy: 8,
                speed: 12
            ))
        }
        recorder.endSession(at: TestTime.offset(100))

        // Act
        let session = try #require(store.latestSession)
        let data = try JSONEncoder().encode(session)
        let json = try #require(String(data: data, encoding: .utf8))

        // Assert
        #expect(!json.contains("37.12"))
        #expect(!json.contains("127.98"))
        #expect(!json.lowercased().contains("latitude"))
        #expect(!json.lowercased().contains("longitude"))
        #expect(!json.lowercased().contains("coordinate"))
        // The derived value is what survives instead.
        #expect(session.events.contains { $0.distanceFromPreviousM != nil })
    }

    // MARK: - Motion vocabulary

    @Test("Core Motion flags become §2's normalized transitions, in fixture order")
    func motionTransitionsUseContractVocabulary() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        recorder.beginSession(at: TestTime.offset(0))

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
        recorder.beginSession(at: TestTime.offset(0))

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
        recorder.beginSession(at: TestTime.offset(0))
        let history = [sample(0, automotive: true), sample(120, walking: true)]

        // Act
        recorder.record(motionSamples: history)
        recorder.record(motionSamples: history)
        recorder.record(motionSamples: history + [sample(180, stationary: true)])

        // Assert
        #expect(types(store.latestSession) == ["vehicle_enter", "vehicle_exit", "walking_enter", "stationary_enter"])
    }

    @Test("History older than the session belongs to the previous trip, not this one")
    func samplesBeforeTheSessionAreIgnored() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        recorder.beginSession(at: TestTime.offset(100))

        // Act
        recorder.record(motionSamples: [sample(10, walking: true), sample(150, automotive: true)])

        // Assert
        #expect(types(store.latestSession) == ["vehicle_enter"])
    }

    // MARK: - Location

    @Test("Fixes are downsampled, and the dropped distance is carried into the next event")
    func locationIsDownsampledWithoutLosingDistance() throws {
        // Arrange — 1 Hz fixes moving 10 m each, for 40 s.
        let store = StubTraceStore()
        var recorder = recorder(store)
        recorder.beginSession(at: TestTime.offset(0))

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
        recorder.beginSession(at: TestTime.offset(0))

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
        recorder.beginSession(at: TestTime.offset(0))

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
        recorder.beginSession(at: TestTime.offset(0))

        // Act
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(0), accuracy: 120))
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(20), accuracy: 8))

        // Assert
        #expect(types(store.latestSession) == ["location", "location"])
    }

    /// In a tunnel or an underground car park the significant-change stream may be the
    /// only location evidence there is, so it is never downsampled away.
    @Test("A significant-change sample is recorded with quality but no distance")
    func significantChangeIsRecorded() throws {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        recorder.beginSession(at: TestTime.offset(0))

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

    // MARK: - Bounds and lifecycle

    @Test("Nothing is recorded outside a session")
    func recordingRequiresAnOpenSession() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.record(motionSamples: [sample(0, automotive: true)])
        recorder.record(fix: TestGeo.fix(at: TestTime.offset(0)))

        // Assert
        #expect(store.storedSessions.isEmpty)
        #expect(!recorder.isRecording)
    }

    @Test("A session stops growing at the per-session ceiling")
    func perSessionEventCapHolds() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        recorder.beginSession(at: TestTime.offset(0))

        // Act — one event per 15 s window, past the ceiling.
        let overflow = TraceRetentionPolicy.standard.maximumEventsPerSession + 50
        for step in 0 ..< overflow {
            recorder.record(fix: TestGeo.fix(
                at: TestTime.offset(Double(step) * TraceRecorder.minimumLocationInterval),
                metersNorth: Double(step)
            ))
        }

        // Assert
        #expect(store.latestSession?.events.count == TraceRetentionPolicy.standard.maximumEventsPerSession)
    }

    /// A file left behind by process death must already be usable. Rewriting `endedAt` on
    /// every append is what removes the need for a repair pass on the next launch.
    @Test("endedAt tracks the newest event, so an abandoned file is still complete")
    func endedAtAdvancesWithEveryAppend() throws {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)
        recorder.beginSession(at: TestTime.offset(0))

        // Act
        recorder.record(motionSamples: [sample(0, automotive: true)])
        let afterFirst = try #require(store.latestSession?.endDate)
        recorder.record(motionSamples: [sample(120, walking: true)])
        let afterSecond = try #require(store.latestSession?.endDate)

        // Assert
        #expect(afterFirst == TestTime.offset(0))
        #expect(afterSecond == TestTime.offset(120))
    }

    @Test("The session being recorded is protected from the rolling cap")
    func openSessionIsProtectedFromEviction() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.beginSession(at: TestTime.offset(0))

        // Assert
        #expect(store.pruneProtectedIds == [recorder.openSessionId])
    }

    @Test("A second begin does not restart the trace")
    func beginIsIdempotent() {
        // Arrange
        let store = StubTraceStore()
        var recorder = recorder(store)

        // Act
        recorder.beginSession(at: TestTime.offset(0))
        let first = recorder.openSessionId
        recorder.beginSession(at: TestTime.offset(60))

        // Assert
        #expect(recorder.openSessionId == first)
        #expect(store.storedSessions.count == 1)
    }

    /// Recording is instrumentation. A failing write must surface in diagnostics and
    /// leave the detection path alone (docs/05 §9 best-effort).
    @Test("A write failure is recorded, not thrown")
    func writeFailureIsBestEffort() {
        // Arrange
        let store = StubTraceStore(writeError: .writeFailed("NSCocoaErrorDomain(513)"))
        var recorder = recorder(store)

        // Act
        recorder.beginSession(at: TestTime.offset(0))
        recorder.record(motionSamples: [sample(0, automotive: true)])

        // Assert
        #expect(recorder.lastFailure?.contains("513") == true)
        #expect(recorder.isRecording)
    }
}

private extension Double {
    /// Haversine on a sphere, so metre expectations are compared with a tolerance rather
    /// than for equality.
    func isApproximately(_ other: Double, tolerance: Double = 1) -> Bool {
        abs(self - other) <= tolerance
    }
}

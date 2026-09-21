import Foundation
import Testing
@testable import ParkingPin

/// What the coordinator hands the recorder, which is where the defect docs/05 §9 names
/// actually lived: the boundary policy was never the only thing tying a trace to a drive —
/// the coordinator only fed the recorder from inside a bounded driving session, so a walk
/// or a subway ride reached it as nothing at all.
@Suite("Trace coordination")
struct TraceCoordinationTests {
    private let reference = TestTime.offset(0)

    private struct Harness {
        let coordinator: BackgroundCoordinator
        let traces: StubTraceStore
        let capture: StubBoundedLocationCapture
        let history: StubMotionHistoryProvider
    }

    private func harness(motion: [MotionSample] = []) -> Harness {
        let traces = StubTraceStore()
        let capture = StubBoundedLocationCapture()
        let history = StubMotionHistoryProvider(result: .success(motion))
        return Harness(
            coordinator: BackgroundCoordinator(
                checkpointStore: StubCheckpointStore(loadResult: .absent),
                motionHistory: history,
                locationCapture: capture,
                dateProvider: MutableDateProvider(reference),
                traceRecorder: TraceRecorder(store: traces, metadata: TestTrace.metadata)
            ),
            traces: traces,
            capture: capture,
            history: history
        )
    }

    private func types(_ session: TraceSession?) -> [String] {
        (session?.events ?? []).map(\.type.rawValue)
    }

    /// The regression this change exists to prevent. Recording used to start inside
    /// `startDrivingSession`, and capture only opens on vehicle evidence, so the negative
    /// cases docs/05_PARKING_DETECTION_ENGINE.md §17 needs — the only ones collectable
    /// without a car — were lost before they reached the recorder.
    @Test("A walk is recorded although no bounded session ever opens")
    func walkIsRecordedWithoutADrivingSession() async {
        // Arrange
        let harness = harness(motion: [
            MotionSample(timestamp: reference.addingTimeInterval(-120), stationary: true, confidence: .high),
            MotionSample(timestamp: reference.addingTimeInterval(-60), walking: true, confidence: .high)
        ])

        // Act
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Assert — nothing drove, and the walk is on disk anyway.
        let isDriving = await harness.coordinator.isDrivingSessionOpen
        #expect(!isDriving)
        #expect(harness.capture.startCount == 0)
        #expect(types(harness.traces.latestSession) == ["stationary_enter", "walking_enter", "stationary_exit"])
    }

    /// A subway ride underground can produce nothing but significant-change wakes.
    @Test("A significant change with no vehicle evidence opens a trace")
    func significantChangeIsRecordedWithoutVehicleEvidence() async {
        // Arrange
        let harness = harness()
        await harness.coordinator.rehydrate(launchReason: .userInitiated)

        // Act
        await harness.coordinator.handleSignificantChange(LocationQualitySample(
            timestamp: reference.addingTimeInterval(-5),
            horizontalAccuracy: 45
        ))

        // Assert
        #expect(types(harness.traces.latestSession) == ["location"])
    }

    /// docs/05 §9: the opt-in is the one boundary the user controls, and while it is off
    /// nothing is recorded at all.
    @Test("Opting out closes the open trace and stops recording")
    func optingOutClosesTheTrace() async {
        // Arrange
        // Two samples, so the session the opt-out closes is one §9's viability rule keeps
        // — a one-event session would be discarded and the count below would be about the
        // wrong rule.
        let harness = harness(motion: [
            MotionSample(timestamp: reference.addingTimeInterval(-90), walking: true, confidence: .high),
            MotionSample(timestamp: reference.addingTimeInterval(-60), stationary: true, confidence: .high)
        ])
        await harness.coordinator.rehydrate(launchReason: .userInitiated)
        let openedCount = harness.traces.storedSessions.count

        // Act
        await harness.coordinator.setTraceRecordingEnabled(false)
        await harness.coordinator.handleSignificantChange(LocationQualitySample(
            timestamp: reference.addingTimeInterval(-5),
            horizontalAccuracy: 45
        ))

        // Assert — the pointer is cleared and the later event recorded nothing.
        #expect(openedCount == 1)
        #expect(harness.traces.openSessionId == nil)
        #expect(harness.traces.storedSessions.count == 1)
    }

    /// The walk away from the car ends the bounded session but not the trip: it is the
    /// other half of the parking transition a §8 fixture is cut from, and §9 lets the idle
    /// gap decide when the trip is actually over.
    @Test("Parking ends the bounded session and leaves the trace open")
    func parkingKeepsTheTraceOpen() async {
        // Arrange — a drive that opens a session on the first wake.
        let drive = MotionSample(
            timestamp: reference.addingTimeInterval(-120),
            automotive: true,
            stationary: true,
            confidence: .high
        )
        let harness = harness(motion: [drive])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        let wasDriving = await harness.coordinator.isDrivingSessionOpen

        // Act — the next wake sees the walk that followed.
        harness.history.setResult(.success([
            drive,
            MotionSample(timestamp: reference.addingTimeInterval(-30), walking: true, confidence: .high)
        ]))
        await harness.coordinator.handleSignificantChange(LocationQualitySample(
            timestamp: reference.addingTimeInterval(-5),
            horizontalAccuracy: 20
        ))

        // Assert — the capture is released, and both halves are in one trace.
        let isDriving = await harness.coordinator.isDrivingSessionOpen
        #expect(wasDriving)
        #expect(!isDriving)
        #expect(!harness.capture.isActive())
        #expect(harness.traces.storedSessions.count == 1)
        #expect(types(harness.traces.latestSession) == [
            "vehicle_enter",
            "stationary_enter",
            "location",
            "vehicle_exit",
            "walking_enter",
            "stationary_exit"
        ])
    }
}

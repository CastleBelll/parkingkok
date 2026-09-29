import Foundation
import Testing
@testable import ParkingPin

/// docs/04_IOS §3a. The capture's health has to be readable *while it runs*: the field
/// question is whether Core Location spoke during the drive, and a value copied only at
/// start and stop answered "0 updates" on 2026-09-24 for a capture that had delivered 62.
@Suite("Capture health diagnostics")
struct CaptureHealthDiagnosticsTests {
    private let reference = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test("Updates delivered during a drive are visible before the capture stops")
    func updateCountIsLive() async {
        // Arrange
        let capture = StubBoundedLocationCapture()
        let coordinator = makeCoordinator(capture: capture)
        await coordinator.rehydrate(launchReason: .significantLocationChange)
        #expect(capture.isActive())

        // Act
        capture.deliverUpdate()
        capture.deliverUpdate()
        await coordinator.handleDrivingFix(TestGeo.fix(at: reference, metersNorth: 0, accuracy: 8))

        // Assert
        let health = await coordinator.currentSnapshot().captureHealth
        #expect(health.updateCount == 2)
    }

    @Test("Whether the capture began in the foreground is carried into the report")
    func foregroundStartIsReported() async {
        // Arrange
        let capture = StubBoundedLocationCapture()
        capture.startsInForeground = false
        let coordinator = makeCoordinator(capture: capture)

        // Act
        await coordinator.rehydrate(launchReason: .significantLocationChange)

        // Assert
        let health = await coordinator.currentSnapshot().captureHealth
        #expect(health.startedInForeground == false)
    }

    @Test("A capture that never started says nothing about the foreground")
    func neverStartedIsNil() {
        #expect(BoundedCaptureHealth.none.startedInForeground == nil)
    }

    private func makeCoordinator(capture: StubBoundedLocationCapture) -> BackgroundCoordinator {
        let automotive = MotionSample(
            timestamp: reference.addingTimeInterval(-30),
            automotive: true,
            stationary: false,
            confidence: .high
        )
        return BackgroundCoordinator(
            checkpointStore: StubCheckpointStore(loadResult: .absent),
            motionHistory: StubMotionHistoryProvider(result: .success([automotive])),
            locationCapture: capture,
            dateProvider: MutableDateProvider(reference)
        )
    }
}

/// docs/05 §3a "Turning Smart Detection off": a watchdog tick already dispatched when the
/// user switched off must not feed the engine afterwards. Every other entry point was gated
/// and this one is now too (review, 2026-09-28). The opt-out has already ended the session,
/// so this pins an outcome that holds with or without the guard.
@Suite("Opt-out gates the watchdog")
struct OptOutWatchdogTests {
    private let reference = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test("A watchdog tick after opting out writes nothing and opens nothing")
    func watchdogTickAfterOptOutIsIgnored() async {
        // Arrange
        let capture = StubBoundedLocationCapture()
        let checkpoints = StubCheckpointStore(loadResult: .absent)
        let clock = MutableDateProvider(reference)
        let automotive = MotionSample(
            timestamp: reference.addingTimeInterval(-30),
            automotive: true,
            stationary: false,
            confidence: .high
        )
        let coordinator = BackgroundCoordinator(
            checkpointStore: checkpoints,
            motionHistory: StubMotionHistoryProvider(result: .success([automotive])),
            locationCapture: capture,
            dateProvider: clock
        )
        await coordinator.rehydrate(launchReason: .significantLocationChange)
        await coordinator.setSmartDetectionEnabled(false)
        let stateAfterOptOut = await coordinator.currentSnapshot().currentCheckpoint?.state
        let writesAfterOptOut = checkpoints.savedCheckpoints.count
        let startsAfterOptOut = capture.startCount

        // Act — the tick that was already on its way.
        clock.advance(by: 400)
        await coordinator.evaluateDrivingTimeouts()

        // Assert
        #expect(await coordinator.currentSnapshot().currentCheckpoint?.state == stateAfterOptOut)
        #expect(checkpoints.savedCheckpoints.count == writesAfterOptOut)
        #expect(capture.startCount == startsAfterOptOut)
    }
}

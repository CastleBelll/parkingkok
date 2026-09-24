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

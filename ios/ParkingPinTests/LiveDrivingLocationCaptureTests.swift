import CoreLocation
import Foundation
import Testing
@testable import ParkingPin

/// docs/04_IOS §3a: the capture has to run from a background start, and has to stop
/// completely. Core Location's callbacks are driven by hand here — the simulator has no
/// drive to deliver.
@MainActor
@Suite("Live driving location capture")
struct LiveDrivingLocationCaptureTests {
    @Test("A drive's manager may run in the background and does not pause at a red light")
    func driveConfiguration() {
        // Arrange
        let manager = CLLocationManager()

        // Act
        LiveDrivingLocationCapture.configureForDrive(manager)

        // Assert
        #expect(manager.allowsBackgroundLocationUpdates)
        #expect(!manager.pausesLocationUpdatesAutomatically)
        #expect(manager.activityType == .automotiveNavigation)
        #expect(manager.desiredAccuracy == kCLLocationAccuracyBestForNavigation)
    }

    @Test("Once the drive is over nothing keeps the app alive in the background")
    func idleConfiguration() {
        // Arrange
        let manager = CLLocationManager()
        LiveDrivingLocationCapture.configureForDrive(manager)

        // Act
        LiveDrivingLocationCapture.configureForIdle(manager)

        // Assert
        #expect(!manager.allowsBackgroundLocationUpdates)
    }

    @Test("Delivered locations reach the engine and are counted")
    func deliveredLocationsAreForwarded() {
        // Arrange
        let (capture, spy) = makeCapture()
        capture.start()

        // Act
        capture.locationManager(CLLocationManager(), didUpdateLocations: [location(), location()])

        // Assert
        #expect(spy.fixes == 2)
        #expect(capture.health().updateCount == 2)
        #expect(capture.health().isUpdating)
        capture.stop()
    }

    @Test("A location that arrives after stop reaches nothing")
    func nothingAfterStop() {
        // Arrange
        let (capture, spy) = makeCapture()
        capture.start()
        capture.stop()

        // Act
        capture.locationManager(CLLocationManager(), didUpdateLocations: [location()])

        // Assert
        #expect(spy.fixes == 0)
        #expect(!capture.health().isUpdating)
        #expect(capture.health().startedAt != nil)
    }

    @Test("Starting twice opens one capture")
    func startIsIdempotent() {
        // Arrange
        let (capture, _) = makeCapture()

        // Act
        capture.start()
        let first = capture.health().startedAt
        capture.start()

        // Assert
        #expect(capture.health().startedAt == first)
        capture.stop()
    }

    @Test("A denied authorization ends the capture's usefulness; an unknown fix does not")
    func errorsAreClassified() {
        // Arrange
        let (capture, spy) = makeCapture()
        capture.start()

        // Act
        capture.locationManager(CLLocationManager(), didFailWithError: CLError(.locationUnknown))
        capture.locationManager(CLLocationManager(), didFailWithError: CLError(.denied))

        // Assert
        #expect(spy.authorizationLosses == 1)
        #expect(spy.failures.isEmpty)
        capture.stop()
    }

    private func makeCapture() -> (LiveDrivingLocationCapture, SpyCaptureDelegate) {
        let capture = LiveDrivingLocationCapture()
        let spy = SpyCaptureDelegate()
        capture.delegate = spy
        return (capture, spy)
    }

    private func location() -> CLLocation {
        CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.5, longitude: 127.0),
            altitude: 0,
            horizontalAccuracy: 8,
            verticalAccuracy: -1,
            course: -1,
            speed: 12,
            timestamp: Date()
        )
    }
}

@MainActor
private final class SpyCaptureDelegate: BoundedLocationCaptureDelegate {
    private(set) var fixes = 0
    private(set) var authorizationLosses = 0
    private(set) var failures: [String] = []

    func captureDidProduce(_ fix: LocationFix) {
        fixes += 1
    }

    func captureDidLoseAuthorization() {
        authorizationLosses += 1
    }

    func captureDidFail(_ description: String) {
        failures.append(description)
    }

    func captureWatchdogDidTick() {}
}

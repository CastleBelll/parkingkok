import CoreLocation
import Foundation

/// One fix, now, for the save the user just asked for.
///
/// Separate from the detection stack's `CLServiceSession` on purpose: that session is a
/// *drive*, bounded and battery-gated (docs/05 §19), and a manual save is a single moment.
/// Starting the drive session to answer it would be the 24-hour-tracking shape docs/00
/// forbids, and stopping it again immediately would be worse.
///
/// `@MainActor` because `CLLocationManager` is not `Sendable` and Swift 6 is right about
/// that: the manager, its delegate callbacks and the continuation they resume all have to
/// live on one actor, and `ParkingLocationProviding` above it is already on this one.
@MainActor
protocol OneShotLocating: Sendable {
    /// The current fix, or `nil` — no authorization, a timeout, or Core Location failing.
    ///
    /// **Never prompts.** FR-001 says a manual save works with no permission at all, so an
    /// undetermined status is a `nil` here and not a dialog in the middle of saving.
    func currentFix(timeout: TimeInterval) async -> CLLocation?
}

/// `CLLocationManager.requestLocation()`, wrapped so a caller can `await` it.
///
/// `requestLocation` is the right API and not `startUpdatingLocation`: it delivers one fix
/// and stops on its own, which is exactly the lifetime of a save.
@MainActor
final class CoreLocationOneShotLocator: NSObject, OneShotLocating {
    /// Long enough for a cold GPS fix outdoors, short enough that the save does not feel
    /// stuck. The save proceeds without a location when it expires, so this bounds the
    /// waiting rather than the success.
    static let defaultTimeout: TimeInterval = 8

    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocation?, Never>?
    private var timeoutTask: Task<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func currentFix(timeout: TimeInterval = defaultTimeout) async -> CLLocation? {
        let status = manager.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else { return nil }
        // One at a time. A second tap while the first is in flight would otherwise leave a
        // continuation nobody resumes.
        guard continuation == nil else { return nil }

        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            timeoutTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(timeout))
                guard !Task.isCancelled else { return }
                self?.finish(nil)
            }
            manager.requestLocation()
        }
    }

    /// Resumes at most once, whichever of Core Location and the timeout arrives first.
    private func finish(_ location: CLLocation?) {
        guard let pending = continuation else { return }
        continuation = nil
        timeoutTask?.cancel()
        timeoutTask = nil
        pending.resume(returning: location)
    }
}

/// `@MainActor` on the conformance itself: Core Location delivers these on the queue the
/// manager was created on, which is this actor, and Swift 6 wants that stated rather than
/// assumed.
extension CoreLocationOneShotLocator: @MainActor CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        finish(locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        // The code only. A Core Location error carries no coordinate, but its description
        // has carried region identifiers before, and docs/09 §11 keeps those out.
        AppLog.detection.info("one-shot fix failed: \((error as NSError).code, privacy: .public)")
        finish(nil)
    }
}

import CoreLocation
import Foundation
import UIKit

/// The bounded driving session, as the domain sees it
/// (docs/05_PARKING_DETECTION_ENGINE.md §15 `startBoundedLocationCapture` /
/// `stopLocationCapture`).
///
/// Async requirements so a `@MainActor` adapter can conform: the owner is
/// `BackgroundCoordinator`, which is an actor, and the Core Location objects must live on
/// the main run loop.
protocol BoundedLocationCapturing: Sendable {
    /// Idempotent. Starting twice must not open a second session.
    func start() async
    /// Idempotent. Must release *every* resource `start()` took.
    func stop() async
    func isActive() async -> Bool
    /// What the capture itself knows about its own health (docs/04_IOS §3a).
    func health() async -> BoundedCaptureHealth
}

/// Three facts the 2026-09-20 field data could not distinguish between
/// (docs/04_IOS_IMPLEMENTATION.md §3a).
///
/// That drive showed `drivingConfirmedAt` nine minutes in — the engine asked for a capture —
/// and fixes that stayed significant-change grade for another forty. Two explanations fit
/// equally: the capture never started, or it started and Core Location delivered nothing.
/// The report said only `isCapturingDrivingLocation: false` *after the fact*, which is
/// consistent with both.
///
/// These separate them. `startedAt` says whether `start()` ran and when. `isUpdating`
/// says whether location updates are running right now. `updateCount` says whether Core
/// Location ever delivered — including deliveries that carried no usable fix, which the
/// `drivingFixCount` in the report does not count.
struct BoundedCaptureHealth: Sendable, Equatable {
    /// When `start()` last ran, or nil if it never has in this process.
    var startedAt: Date?
    /// Whether standard location updates are running right now.
    var isUpdating: Bool
    /// Locations Core Location delivered, whatever they contained.
    var updateCount: Int
    /// Whether the app was active when `start()` last ran; nil if it never has.
    ///
    /// Almost every capture starts in the background — the engine decides a drive began
    /// during a significant-change or motion wake. That is why the capture is built on
    /// `CLLocationManager` (see `LiveDrivingLocationCapture`), and this field is how a field
    /// report shows the background start is working.
    var startedInForeground: Bool?

    static let none = BoundedCaptureHealth(
        startedAt: nil,
        isUpdating: false,
        updateCount: 0,
        startedInForeground: nil
    )
}

@MainActor
protocol BoundedLocationCaptureDelegate: AnyObject {
    func captureDidProduce(_ fix: LocationFix)
    func captureDidLoseAuthorization()
    func captureDidFail(_ description: String)
    /// Periodic tick for as long as — and only as long as — capture is running, so the
    /// timeout rules still fire when the fix stream goes silent in a tunnel.
    func captureWatchdogDidTick()
}

/// Standard location updates from `CLLocationManager`, for one bounded drive
/// (docs/04_IOS_IMPLEMENTATION.md §3 DRIVING, §3a).
///
/// **Why not `CLLocationUpdate.liveUpdates`.** Until 2026-09-24 this held a
/// `CLServiceSession` and a `CLBackgroundActivitySession` around `liveUpdates`. Apple DTS:
/// "A new CLBackgroundActivitySession can only be started from Foreground. From background
/// only an existing running CLBAS session can be continued." The engine decides a drive
/// began during a significant-change or motion wake — in the background, nearly always —
/// so the session granted nothing and the stream ran only once the user opened the app,
/// usually after parking. Every field drive showed that shape (§3a). With Always
/// authorization and the `location` background mode, `startUpdatingLocation` may be started
/// from the background and keeps the process running while it delivers, which is what a
/// drive that began while the phone was in a pocket needs.
///
/// Leaking the updates means high-accuracy GPS keeps running after the drive ended, which
/// is exactly what docs/00_CORE_RULES.md Background forbids and what the §19 battery gate
/// measures. `stop()` therefore tears everything down unconditionally, and `start()`
/// refuses to run twice.
///
/// There is no `deinit` cleanup: this type is owned for the process lifetime by
/// `DetectionRuntime`, so a `deinit` would be dead code and could not hop to the main
/// actor anyway. The session is bounded by `stop()` and by `DrivingSessionTimeoutPolicy`,
/// not by deallocation.
@MainActor
final class LiveDrivingLocationCapture: NSObject, BoundedLocationCapturing {
    weak var delegate: (any BoundedLocationCaptureDelegate)?

    /// How often the watchdog checks the timeout rules. Coarse on purpose: it exists to
    /// bound a stalled session, not to drive detection, and every wake costs battery.
    private static let watchdogInterval = Duration.seconds(60)

    private let manager = CLLocationManager()
    private var watchdogTask: Task<Void, Never>?

    private(set) var isCapturing = false

    /// See `BoundedCaptureHealth`. Kept across `stop()` on purpose: the question the field
    /// data could not answer is "did it ever start", and zeroing these on teardown would
    /// throw away the answer at exactly the moment the report is read.
    private var lastStartedAt: Date?
    private var updateCount = 0
    private var startedInForeground: Bool?

    override init() {
        super.init()
        manager.delegate = self
    }

    func health() -> BoundedCaptureHealth {
        BoundedCaptureHealth(
            startedAt: lastStartedAt,
            isUpdating: isCapturing,
            updateCount: updateCount,
            startedInForeground: startedInForeground
        )
    }

    func start() {
        guard !isCapturing else { return }
        isCapturing = true
        lastStartedAt = Date.now
        startedInForeground = UIApplication.shared.applicationState == .active

        Self.configureForDrive(manager)
        manager.startUpdatingLocation()

        // Tied to the capture, not to the app: it cannot outlive `stop()`, so a session
        // that ends takes its timer with it.
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.watchdogInterval)
                guard !Task.isCancelled, let self, isCapturing else { return }
                delegate?.captureWatchdogDidTick()
            }
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
        Self.configureForIdle(manager)
        watchdogTask?.cancel()
        watchdogTask = nil
        isCapturing = false
    }

    func isActive() -> Bool {
        isCapturing
    }

    /// The settings one drive runs with, and why each one.
    ///
    /// - `automotiveNavigation` and best-for-navigation accuracy: what `liveUpdates` was
    ///   asked for, so §7's thresholds see fixes of the grade they were written against.
    /// - `allowsBackgroundLocationUpdates`: the whole point — delivery continues with the
    ///   app in the background, and may be *started* there.
    /// - `pausesLocationUpdatesAutomatically = false`: Core Location's own pause fires at a
    ///   long red light and does not resume from the background, which would end the drive's
    ///   fixes at the first stop. The session is bounded by §15's rules instead.
    /// - No background indicator: the drive is bounded, and Always authorization permits it.
    static func configureForDrive(_ manager: CLLocationManager) {
        manager.activityType = .automotiveNavigation
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = false
    }

    /// Nothing may keep the app alive in the background once the drive is over.
    static func configureForIdle(_ manager: CLLocationManager) {
        manager.allowsBackgroundLocationUpdates = false
    }

    /// Counted before the fix is examined, so a delivery that carried nothing still shows
    /// Core Location is alive (see `BoundedCaptureHealth.updateCount`).
    private func ingest(_ locations: [CLLocation]) {
        guard isCapturing else { return }
        for location in locations {
            updateCount += 1
            let fix = LocationFix(location)
            if fix.isValid {
                delegate?.captureDidProduce(fix)
            }
        }
    }

    private func fail(_ error: any Error) {
        guard isCapturing else { return }
        let nsError = error as NSError
        switch CLError.Code(rawValue: nsError.code) {
        case .denied:
            delegate?.captureDidLoseAuthorization()
        case .locationUnknown:
            // Transient by definition: Core Location keeps trying and says so this way.
            return
        default:
            let description = "\(nsError.domain)(\(nsError.code))"
            AppLog.detection.error("driving capture failed: \(description, privacy: .public)")
            delegate?.captureDidFail(description)
        }
    }

    private func authorizationChanged(to status: CLAuthorizationStatus) {
        guard isCapturing else { return }
        if status == .denied || status == .restricted {
            delegate?.captureDidLoseAuthorization()
        }
    }
}

/// `@MainActor` on the conformance: the manager was created on the main actor, so Core
/// Location calls back there, and Swift 6 wants that stated rather than assumed.
extension LiveDrivingLocationCapture: @MainActor CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        ingest(locations)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        fail(error)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationChanged(to: manager.authorizationStatus)
    }
}

extension LocationFix {
    /// Core Location → domain. Normalizes Core Location's negative "unknown" speed to
    /// `nil` so no comparison downstream has to know the sentinel.
    init(_ location: CLLocation) {
        self.init(
            timestamp: location.timestamp,
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            horizontalAccuracy: location.horizontalAccuracy,
            speed: location.speed >= 0 ? location.speed : nil
        )
    }
}

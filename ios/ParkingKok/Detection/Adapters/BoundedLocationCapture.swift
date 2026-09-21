import CoreLocation
import Foundation

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
/// These separate them. `startedAt` says whether `start()` ran and when. `holdsSessions`
/// says whether the two objects that keep background delivery alive are still held right
/// now. `updateCount` says whether the async sequence ever yielded — including yields that
/// carried no usable fix, which the `drivingFixCount` in the report does not count.
struct BoundedCaptureHealth: Sendable, Equatable {
    /// When `start()` last ran, or nil if it never has in this process.
    var startedAt: Date?
    /// Whether `CLServiceSession` **and** `CLBackgroundActivitySession` are both held.
    /// False while capturing is the signature of a session that was released underneath us.
    var holdsSessions: Bool
    /// Iterations of `CLLocationUpdate.Updates`, whatever they contained.
    var updateCount: Int

    static let none = BoundedCaptureHealth(startedAt: nil, holdsSessions: false, updateCount: 0)
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

/// `CLLocationUpdate.liveUpdates(.automotiveNavigation)` plus the two session objects
/// background delivery needs (docs/04_IOS_IMPLEMENTATION.md §3 DRIVING).
///
/// Three resources are taken together and must be released together:
///
/// - the `Task` iterating `CLLocationUpdate.Updates`
/// - `CLServiceSession`, which declares the authorization the updates require
/// - `CLBackgroundActivitySession`, which keeps delivery alive — and the status
///   indicator visible — once the app is backgrounded
///
/// Leaking any one of them means high-accuracy GPS keeps running after the drive ended,
/// which is exactly what docs/00_CORE_RULES.md Background forbids and what the §19
/// battery gate measures. `stop()` therefore tears down all three unconditionally, and
/// `start()` refuses to run twice rather than orphaning the first set.
///
/// There is no `deinit` cleanup: this type is owned for the process lifetime by
/// `DetectionRuntime`, so a `deinit` would be dead code and could not hop to the main
/// actor anyway. The session is bounded by `stop()` and by
/// `DrivingSessionTimeoutPolicy`, not by deallocation.
@MainActor
final class LiveDrivingLocationCapture: BoundedLocationCapturing {
    weak var delegate: (any BoundedLocationCaptureDelegate)?

    /// How often the watchdog checks the timeout rules. Coarse on purpose: it exists to
    /// bound a stalled session, not to drive detection, and every wake costs battery.
    private static let watchdogInterval = Duration.seconds(60)

    private var updatesTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var serviceSession: CLServiceSession?
    private var backgroundSession: CLBackgroundActivitySession?

    private(set) var isCapturing = false

    /// See `BoundedCaptureHealth`. Kept across `stop()` on purpose: the question the field
    /// data could not answer is "did it ever start", and zeroing these on teardown would
    /// throw away the answer at exactly the moment the report is read.
    private var lastStartedAt: Date?
    private var updateCount = 0

    func health() -> BoundedCaptureHealth {
        BoundedCaptureHealth(
            startedAt: lastStartedAt,
            holdsSessions: serviceSession != nil && backgroundSession != nil,
            updateCount: updateCount
        )
    }

    func start() {
        guard !isCapturing else { return }
        isCapturing = true
        lastStartedAt = Date.now

        // Declares that the updates below need Always authorization. Created before the
        // sequence so the first update is never dropped for want of a session.
        serviceSession = CLServiceSession(authorization: .always)
        backgroundSession = CLBackgroundActivitySession()

        updatesTask = Task.detached { [weak self] in
            do {
                // Mapped to `LocationFix` inside the loop: `CLLocationUpdate` is not
                // Sendable, so nothing but the domain value crosses back to the main actor.
                for try await update in CLLocationUpdate.liveUpdates(.automotiveNavigation) {
                    if Task.isCancelled {
                        return
                    }
                    await self?.countUpdate()
                    let denied = update.authorizationDenied || update.authorizationDeniedGlobally
                    let fix = update.location.map(LocationFix.init)
                    guard let self else { return }
                    let shouldContinue = await ingest(fix: fix, authorizationDenied: denied)
                    if !shouldContinue {
                        return
                    }
                }
            } catch {
                let nsError = error as NSError
                await self?.report(failure: "\(nsError.domain)(\(nsError.code))")
            }
        }

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

    /// Counted before the fix is examined, so an update that carried nothing still shows
    /// the sequence is alive. A capture with `updateCount == 0` never heard from Core
    /// Location at all, which is a different bug from one whose fixes are all rejected.
    private func countUpdate() {
        updateCount += 1
    }

    func stop() {
        updatesTask?.cancel()
        updatesTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        // Invalidated in the reverse order they were taken, and always both: an
        // invalidated background session with a live service session still holds the
        // authorization open.
        backgroundSession?.invalidate()
        backgroundSession = nil
        serviceSession?.invalidate()
        serviceSession = nil
        isCapturing = false
    }

    func isActive() -> Bool {
        isCapturing
    }

    /// Returns whether the loop should keep iterating.
    private func ingest(fix: LocationFix?, authorizationDenied: Bool) -> Bool {
        guard isCapturing else { return false }
        if authorizationDenied {
            delegate?.captureDidLoseAuthorization()
            return false
        }
        if let fix, fix.isValid {
            delegate?.captureDidProduce(fix)
        }
        return true
    }

    private func report(failure: String) {
        guard isCapturing else { return }
        AppLog.detection.error("driving capture failed: \(failure, privacy: .public)")
        delegate?.captureDidFail(failure)
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

import Foundation

/// Composition root for the detection stack, and the only thing `AppDelegate` talks to
/// (docs/04_IOS_IMPLEMENTATION.md §2: the delegate forwards, it does not decide).
///
/// `@MainActor` because it owns the Core Location adapter; the durable state it drives
/// lives behind `BackgroundCoordinator`'s actor isolation.
@MainActor
final class DetectionRuntime {
    /// The instance `AppDelegate` bootstraps. Dependencies stay injectable so the pieces
    /// can be exercised in isolation.
    static let shared = DetectionRuntime()

    private let monitor: SignificantLocationMonitor
    private let coordinator: BackgroundCoordinator
    private let preference: SmartDetectionPreference

    private(set) var motionAuthorization: MotionAuthorization
    private(set) var isMotionHistoryAvailable: Bool
    private(set) var locationAuthorization: LocationAuthorization
    private(set) var hasBootstrapped = false
    /// Non-nil when the checkpoint file could not even be located, which would otherwise
    /// look like "no checkpoint yet".
    private(set) var storeSetupFailure: String?

    private let motionHistory: any MotionHistoryProviding

    init(
        monitor: SignificantLocationMonitor = SignificantLocationMonitor(),
        motionHistory: any MotionHistoryProviding = CoreMotionHistoryProvider(),
        preference: SmartDetectionPreference = SmartDetectionPreference(),
        checkpointStore: (any DetectionCheckpointStoring)? = nil
    ) {
        self.monitor = monitor
        self.motionHistory = motionHistory
        self.preference = preference

        var setupFailure: String?
        let store: any DetectionCheckpointStoring
        if let checkpointStore {
            store = checkpointStore
        } else {
            do {
                store = try FileDetectionCheckpointStore(fileURL: FileDetectionCheckpointStore.defaultFileURL())
            } catch {
                // Application Support should always be reachable; if it is not, keep the
                // app working on a temporary file and make the degradation visible rather
                // than presenting it as "no checkpoint yet".
                let nsError = error as NSError
                setupFailure = "\(nsError.domain)(\(nsError.code))"
                store = FileDetectionCheckpointStore(
                    fileURL: FileManager.default.temporaryDirectory.appending(path: "checkpoint.json")
                )
            }
        }
        storeSetupFailure = setupFailure

        coordinator = BackgroundCoordinator(checkpointStore: store, motionHistory: motionHistory)
        locationAuthorization = monitor.authorization
        motionAuthorization = motionHistory.authorization
        isMotionHistoryAvailable = motionHistory.isHistoryAvailable
    }

    var isSmartDetectionEnabled: Bool {
        preference.isEnabled
    }

    var isMonitoringSignificantChanges: Bool {
        monitor.isMonitoring
    }

    /// Called from `application(_:didFinishLaunchingWithOptions:)`.
    ///
    /// Everything before the `Task` is synchronous on purpose: docs/04 §3 requires the
    /// manager to exist and the significant-change service to be re-registered before the
    /// launch call returns, or the event that woke us is lost.
    func bootstrap(launchReason: LaunchReason) {
        monitor.delegate = self
        locationAuthorization = monitor.authorization
        startMonitoringIfPermitted()
        hasBootstrapped = true

        AppLog.lifecycle.info("bootstrap reason=\(launchReason.rawValue, privacy: .public)")

        Task { [coordinator] in
            await coordinator.rehydrate(launchReason: launchReason)
        }
    }

    func snapshot() async -> RehydrationSnapshot {
        await coordinator.currentSnapshot()
    }

    func refreshAuthorizationStatuses() {
        locationAuthorization = monitor.authorization
        motionAuthorization = motionHistory.authorization
        isMotionHistoryAvailable = motionHistory.isHistoryAvailable
    }

    /// The contextual request ladder from docs/04 §4. Returns what it asked for, so the
    /// caller can tell "nothing to ask" apart from "asked".
    @discardableResult
    func requestNextLocationPermission() -> LocationPermissionRequest? {
        let request = PermissionRequestPolicy.nextRequest(
            location: locationAuthorization,
            smartDetectionEnabled: preference.isEnabled
        )
        switch request {
        case .whenInUse: monitor.requestWhenInUseAuthorization()
        case .always: monitor.requestAlwaysAuthorization()
        case nil: break
        }
        return request
    }

    /// Motion permission is granted by the first query, so this doubles as the prompt.
    func requestMotionPermission() async {
        let window = MotionHistoryWindowPolicy.window(now: Date(), checkpointDate: nil)
        _ = try? await motionHistory.samples(in: window)
        refreshAuthorizationStatuses()
    }

    func setSmartDetectionEnabled(_ enabled: Bool) {
        preference.isEnabled = enabled
        if enabled {
            requestNextLocationPermission()
            startMonitoringIfPermitted()
        } else {
            monitor.stopMonitoring()
        }
    }

    private func startMonitoringIfPermitted() {
        let shouldMonitor = PermissionRequestPolicy.shouldMonitorSignificantChanges(
            location: locationAuthorization,
            smartDetectionEnabled: preference.isEnabled
        )
        if shouldMonitor {
            monitor.startMonitoring()
        } else {
            monitor.stopMonitoring()
        }
    }
}

extension DetectionRuntime: SignificantLocationMonitorDelegate {
    func monitorDidChangeAuthorization(_ authorization: LocationAuthorization) {
        locationAuthorization = authorization
        startMonitoringIfPermitted()
    }

    func monitorDidReceiveLocation(_ sample: LocationQualitySample) {
        Task { [coordinator] in
            await coordinator.handleSignificantChange(sample)
        }
    }

    func monitorDidFail(_ description: String) {
        Task { [coordinator] in
            await coordinator.recordLocationFailure(description)
        }
    }
}

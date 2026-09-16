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
    private let locationCapture: LiveDrivingLocationCapture
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
    private let diagnosticsStore: (any DiagnosticsReportStoring)?

    init(
        monitor: SignificantLocationMonitor = SignificantLocationMonitor(),
        locationCapture: LiveDrivingLocationCapture = LiveDrivingLocationCapture(),
        motionHistory: any MotionHistoryProviding = CoreMotionHistoryProvider(),
        preference: SmartDetectionPreference = SmartDetectionPreference(),
        checkpointStore: (any DetectionCheckpointStoring)? = nil,
        diagnosticsStore: (any DiagnosticsReportStoring)? = nil
    ) {
        self.monitor = monitor
        self.locationCapture = locationCapture
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

        // Beside the checkpoint, or nowhere. A diagnostics file in an unexpected place is
        // worse than none: it would be stale the moment anyone looked for it.
        self.diagnosticsStore = diagnosticsStore
            ?? (try? FileDiagnosticsReportStore(fileURL: FileDiagnosticsReportStore.defaultFileURL()))

        coordinator = BackgroundCoordinator(
            checkpointStore: store,
            motionHistory: motionHistory,
            locationCapture: locationCapture
        )
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

    var isCapturingDrivingLocation: Bool {
        locationCapture.isCapturing
    }

    /// Called from `application(_:didFinishLaunchingWithOptions:)`.
    ///
    /// Everything before the `Task` is synchronous on purpose: docs/04 §3 requires the
    /// manager to exist and the significant-change service to be re-registered before the
    /// launch call returns, or the event that woke us is lost.
    func bootstrap(launchReason: LaunchReason) {
        monitor.delegate = self
        locationCapture.delegate = self
        locationAuthorization = monitor.authorization
        startMonitoringIfPermitted()
        hasBootstrapped = true

        AppLog.lifecycle.notice("bootstrap reason=\(launchReason.rawValue, privacy: .public)")

        Task { [weak self, coordinator] in
            await coordinator.rehydrate(launchReason: launchReason)
            #if PK_DEV
                // Field-test hook, DEV only. See `startDrivingSessionForFieldTest`.
                if Self.isFieldTestDrivingSessionForced {
                    await coordinator.startDrivingSessionForFieldTest()
                }
            #endif
            await self?.exportDiagnostics()
        }
    }

    #if PK_DEV
        /// Launch with `PK_FORCE_DRIVING_SESSION=1` to open a bounded session immediately:
        ///
        /// ```sh
        /// xcrun devicectl device process launch --device <udid> \
        ///   --environment-variables '{"PK_FORCE_DRIVING_SESSION":"1"}' com.parkingkok.app.dev
        /// ```
        private static var isFieldTestDrivingSessionForced: Bool {
            ProcessInfo.processInfo.environment["PK_FORCE_DRIVING_SESSION"] == "1"
        }
    #endif

    func snapshot() async -> RehydrationSnapshot {
        await coordinator.currentSnapshot()
    }

    func refreshAuthorizationStatuses() {
        locationAuthorization = monitor.authorization
        motionAuthorization = motionHistory.authorization
        isMotionHistoryAvailable = motionHistory.isHistoryAvailable
    }

    /// Writes the diagnostics file that `checkpoint.json` sits beside.
    ///
    /// The single writer: it is the only place that holds both the coordinator's snapshot
    /// and the authorization statuses. Best-effort — a diagnostics write must never break
    /// a detection callback, and a failure shows up as `lastPersistError` in the next
    /// report rather than as a thrown error here.
    func exportDiagnostics() async {
        guard let store = diagnosticsStore else { return }
        let report = await DiagnosticsReport(
            snapshot: coordinator.currentSnapshot(),
            now: Date(),
            locationAuthorization: locationAuthorization,
            motionAuthorization: motionAuthorization,
            isMotionHistoryAvailable: isMotionHistoryAvailable,
            isMonitoringSignificantChanges: monitor.isMonitoring,
            isSmartDetectionEnabled: preference.isEnabled,
            storeSetupFailure: storeSetupFailure
        )
        do {
            try store.write(report)
        } catch {
            let nsError = error as NSError
            AppLog.detection.error(
                "diagnostics export failed: \(nsError.domain, privacy: .public)(\(nsError.code, privacy: .public))"
            )
        }
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
    ///
    /// Routed through the coordinator so a refusal is recorded instead of discarded.
    /// Querying here with `try?` swallowed the one error that explains an unresponsive
    /// button, which is exactly the silent recovery the checkpoint path forbids.
    func requestMotionPermission() async {
        await coordinator.requestMotionHistoryAccess()
        refreshAuthorizationStatuses()
        await exportDiagnostics()
    }

    func setSmartDetectionEnabled(_ enabled: Bool) {
        preference.isEnabled = enabled
        if enabled {
            requestNextLocationPermission()
            startMonitoringIfPermitted()
            Task { await exportDiagnostics() }
        } else {
            monitor.stopMonitoring()
            // The bounded session must not outlive the opt-in that authorized it.
            Task { [weak self, coordinator] in
                await coordinator.stopDrivingSessionForOptOut()
                await self?.exportDiagnostics()
            }
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

    /// The one path that runs while nobody is watching, so it is the one whose evidence
    /// most needs to outlive the process.
    func monitorDidReceiveLocation(_ sample: LocationQualitySample) {
        Task { [weak self, coordinator] in
            await coordinator.handleSignificantChange(sample)
            await self?.exportDiagnostics()
        }
    }

    func monitorDidFail(_ description: String) {
        Task { [weak self, coordinator] in
            await coordinator.recordLocationFailure(description)
            await self?.exportDiagnostics()
        }
    }
}

/// The bounded driving session's callbacks (docs/04_IOS_IMPLEMENTATION.md §3 DRIVING).
///
/// Every one of them hands straight to the coordinator: the fix has to be folded into the
/// durable state under actor isolation, and docs/04 §7 forbids doing anything heavier on
/// a background callback.
extension DetectionRuntime: BoundedLocationCaptureDelegate {
    func captureDidProduce(_ fix: LocationFix) {
        Task { [weak self, coordinator] in
            await coordinator.handleDrivingFix(fix)
            await self?.exportDiagnostics()
        }
    }

    func captureDidLoseAuthorization() {
        Task { [weak self, coordinator] in
            await coordinator.handleCaptureAuthorizationLost()
            await self?.exportDiagnostics()
        }
    }

    func captureDidFail(_ description: String) {
        Task { [weak self, coordinator] in
            await coordinator.handleCaptureFailure(description)
            await self?.exportDiagnostics()
        }
    }

    func captureWatchdogDidTick() {
        Task { [weak self, coordinator] in
            await coordinator.evaluateDrivingTimeouts()
            await self?.exportDiagnostics()
        }
    }
}

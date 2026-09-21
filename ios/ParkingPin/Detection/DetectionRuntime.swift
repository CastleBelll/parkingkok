import Foundation
import UserNotifications

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
    /// docs/05 §3a "The car link". Sampled at each wake rather than observed as an event —
    /// see `AudioRouteCarLinkObserver` for what iOS actually exposes.
    private let carLink: any CarLinkObserving

    private(set) var motionAuthorization: MotionAuthorization
    private(set) var isMotionHistoryAvailable: Bool
    private(set) var locationAuthorization: LocationAuthorization
    private(set) var hasBootstrapped = false
    /// Non-nil when the checkpoint file could not even be located, which would otherwise
    /// look like "no checkpoint yet".
    private(set) var storeSetupFailure: String?

    private let motionHistory: any MotionHistoryProviding
    private let diagnosticsStore: (any DiagnosticsReportStoring)?
    /// The labelling screen reads and writes traces through this; the coordinator writes
    /// them through its own `TraceRecorder`. `nil` when the directory is unavailable, in
    /// which case recording is simply off — never an app failure (docs/05 §9 best-effort).
    private(set) var traceStore: (any TraceStoring)?
    /// Holds the notification delegate for the app's lifetime: the tap can arrive in a
    /// process that was launched for it, and a delegate nobody retains is never called.
    /// P0 instrumentation (docs/05 §9 labelling) — delete with `TraceLabelPrompt`.
    private let labelPromptResponder: TraceLabelPromptResponder?
    /// The pending candidate's file (docs/05 §10a). Exposed because `ParkingComposition`
    /// builds the screen's `CandidateModel` on the same file this writes to — one store,
    /// so a candidate created on a background wake is the one the screen shows.
    /// `nil` when the directory is unavailable, which disables candidates and nothing else.
    private(set) var candidateStore: (any ParkingCandidateStoring)?
    /// The bell's list (docs/10 §7b). Exposed for the same reason `candidateStore` is:
    /// the screen's model and this coordinator must write to the one file, or a
    /// confirmation answered on the lock screen would be missing from the list.
    private(set) var candidateHistoryStore: (any CandidateHistoryStoring)?

    init(
        monitor: SignificantLocationMonitor = SignificantLocationMonitor(),
        locationCapture: LiveDrivingLocationCapture = LiveDrivingLocationCapture(),
        motionHistory: any MotionHistoryProviding = CoreMotionHistoryProvider(),
        preference: SmartDetectionPreference = SmartDetectionPreference(),
        checkpointStore: (any DetectionCheckpointStoring)? = nil,
        diagnosticsStore: (any DiagnosticsReportStoring)? = nil,
        traceStore: (any TraceStoring)? = nil,
        labelPromptDelivery: (any LabelPromptDelivering)? = nil,
        candidateStore: (any ParkingCandidateStoring)? = nil,
        candidateHistory: (any CandidateHistoryStoring)? = nil,
        candidateNotifier: (any CandidateNotifying)? = nil,
        analytics: any AnalyticsRecording = AnalyticsComposition.recorder,
        carLink: any CarLinkObserving = AudioRouteCarLinkObserver()
    ) {
        self.monitor = monitor
        self.carLink = carLink
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

        // Beside the checkpoint, so traces inherit the directory's protection class and
        // come off the device over the same `devicectl copy` path as diagnostics.json.
        let traces = traceStore ?? (try? FileTraceStore(directory: FileTraceStore.defaultDirectoryURL()))
        self.traceStore = traces

        // §9's labelling problem: the in-app screen went unused for three days because the
        // user carries the phone without opening the app. The prompt is posted from
        // whichever process closed the session, so the responder that answers the tap is
        // built here and held for the process's lifetime.
        labelPromptResponder = traces.map(TraceLabelPromptResponder.init(store:))
        let prompter = traces.map { store in
            TraceLabelPrompter(
                delivery: labelPromptDelivery ?? UserNotificationLabelPromptDelivery(),
                onSuppressed: { store.recordLabelPromptSuppressed() }
            )
        }

        // Beside the checkpoint, in the directory that already carries the protection
        // class a locked-device wake needs (docs/05 §10a: the candidate must survive a
        // process that existed only to create it).
        let candidates = candidateStore
            ?? (try? FileParkingCandidateStore(fileURL: FileParkingCandidateStore.defaultFileURL()))
        self.candidateStore = candidates
        // docs/10 §7b. Same directory, same reason: a candidate superseded on a background
        // wake is one the bell still has to be able to account for.
        let history = candidateHistory
            ?? (try? FileCandidateHistoryStore(fileURL: FileCandidateHistoryStore.defaultFileURL()))
        candidateHistoryStore = history

        coordinator = BackgroundCoordinator(
            checkpointStore: store,
            motionHistory: motionHistory,
            locationCapture: locationCapture,
            traceRecorder: traces.map { TraceRecorder(store: $0, prompter: prompter) },
            candidateStore: candidates,
            candidateHistory: history,
            candidateNotifier: candidateNotifier ?? UserNotificationCandidateDelivery(),
            analytics: analytics,
            // §11. Resolved at call time rather than captured: this runtime is built on a
            // background wake that may precede the model container entirely, and only a
            // confirmed departure ever reaches here.
            endActiveParking: { at in
                await MainActor.run { ParkingComposition.shared?.model.endActiveParking(at: at) ?? false }
            }
        )
        locationAuthorization = monitor.authorization
        motionAuthorization = motionHistory.authorization
        isMotionHistoryAvailable = motionHistory.isHistoryAvailable
    }

    var isSmartDetectionEnabled: Bool {
        preference.isEnabled
    }

    /// docs/10 §2a. False exactly once per install, on the launch that asks.
    var isFirstRunAnswered: Bool {
        preference.isFirstRunAnswered
    }

    /// Records that the question was asked, whichever way it was answered.
    func markFirstRunAnswered() {
        preference.isFirstRunAnswered = true
    }

    var isMonitoringSignificantChanges: Bool {
        monitor.isMonitoring
    }

    var monitoringStartedAt: Date? {
        monitor.monitoringStartedAt
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
        // Before anything async, for the same reason the Core Location work is synchronous:
        // a tap that launched this process is delivered as soon as launch returns.
        installNotificationRouter()
        locationAuthorization = monitor.authorization
        startMonitoringIfPermitted()
        hasBootstrapped = true

        AppLog.lifecycle.notice("bootstrap reason=\(launchReason.rawValue, privacy: .public)")

        let isOptedIn = preference.isEnabled
        Task { [weak self, coordinator, carLink] in
            // Before rehydration, because rehydration replays motion history and docs/05 §9
            // records nothing while the user is opted out.
            await coordinator.setTraceRecordingEnabled(isOptedIn)
            await coordinator.rehydrate(launchReason: launchReason)
            await coordinator.handleCarLink(carLink.observe())
            #if PK_DEV
                // Field-test hooks, DEV only. See `startDrivingSessionForFieldTest` and
                // `injectCandidateIfRequestedAtLaunch`.
                if Self.isFieldTestDrivingSessionForced {
                    await coordinator.startDrivingSessionForFieldTest()
                }
                await self?.injectCandidateIfRequestedAtLaunch()
            #endif
            await self?.exportDiagnostics()
        }
    }

    /// Wires the app's one `UNUserNotificationCenterDelegate`.
    ///
    /// Both handlers are registered as providers rather than as objects, because the
    /// candidate handler needs the parking store and opening that is UI-time work this
    /// method must not do: a launch triggered by Core Location has to return before the
    /// event that woke us is lost (docs/04 §3). The provider runs at tap time instead,
    /// which is the only moment the store is actually needed.
    private func installNotificationRouter() {
        guard !hasBootstrapped else { return }
        let router = PKNotificationRouter.shared
        if let labelPromptResponder {
            router.register { labelPromptResponder }
        }
        router.register { ParkingComposition.shared?.candidateResponder }
        UNUserNotificationCenter.current().delegate = router
    }

    #if PK_DEV
        /// Creates a candidate from synthetic evidence, through the real engine path.
        /// See `BackgroundCoordinator.injectCandidateForFieldTest(walking:)`.
        func injectCandidateForFieldTest(walking: Bool) async {
            await coordinator.injectCandidateForFieldTest(walking: walking)
            await exportDiagnostics()
        }

        /// Reproduces the notification → confirmation flow from one command, for the
        /// capture the acceptance criteria ask for:
        ///
        /// ```sh
        /// xcrun simctl launch <udid> com.sjstudioz.parkingpin.dev \
        ///   PK_INJECT_CANDIDATE=medium PK_OPEN_CANDIDATE=1
        /// ```
        ///
        /// `medium` posts, anything else scores `low` and must stay silent, and
        /// `PK_OPEN_CANDIDATE` opens the confirmation screen the notification would have
        /// opened. The second flag exists because a headless simulator has no way to
        /// deliver a tap; it routes through exactly the value a real tap sets, so the
        /// screen it produces is the screen a tap produces.
        private func injectCandidateIfRequestedAtLaunch() async {
            guard let requested = ProcessInfo.processInfo.environment["PK_INJECT_CANDIDATE"] else { return }
            await requestProvisionalNotificationPermission()
            await injectCandidateForFieldTest(walking: requested != "low")

            guard ProcessInfo.processInfo.environment["PK_OPEN_CANDIDATE"] == "1",
                  let candidate = candidateStore?.load(),
                  let candidates = ParkingComposition.shared?.candidates
            else { return }
            candidates.refresh()
            candidates.pendingNavigation = .candidateConfirmation(id: candidate.id)
        }

        /// Provisional, not `.alert`: the machine that runs the capture has no Simulator
        /// window and therefore nobody to answer a permission alert. Provisional
        /// authorization needs no alert, and the foreground presentation the router asks
        /// for is what puts the real notification on the screen to be photographed.
        private func requestProvisionalNotificationPermission() async {
            _ = try? await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .provisional])
        }

        /// Launch with `PK_FORCE_DRIVING_SESSION=1` to open a bounded session immediately:
        ///
        /// ```sh
        /// xcrun devicectl device process launch --device <udid> \
        ///   --environment-variables '{"PK_FORCE_DRIVING_SESSION":"1"}' com.sjstudioz.parkingpin.dev
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
            monitoringStartedAt: monitor.monitoringStartedAt,
            isSmartDetectionEnabled: preference.isEnabled,
            storeSetupFailure: storeSetupFailure,
            traceSummary: traceStore?.summary() ?? .empty
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

    /// Whether an alert may be shown, and what the system currently says.
    ///
    /// The label prompt is the only notification this build posts, so this row is the whole
    /// answer to "why did a weekend of travel produce no labels" (docs/05 §9).
    func notificationAuthorization() async -> String {
        switch await UNUserNotificationCenter.current().notificationSettings().authorizationStatus {
        case .authorized: "authorized"
        case .provisional: "provisional"
        case .ephemeral: "ephemeral"
        case .denied: "denied"
        case .notDetermined: "notDetermined"
        @unknown default: "unknown"
        }
    }

    /// Asked for from the diagnostics screen only, never at launch: a refusal has to stay a
    /// refusal, and the recording path is unaffected either way.
    func requestNotificationPermission() async {
        do {
            _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        } catch {
            let nsError = error as NSError
            AppLog.detection.notice(
                "notification authorization failed: \(nsError.domain, privacy: .public)(\(nsError.code, privacy: .public))"
            )
        }
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
            Task { [weak self, coordinator] in
                await coordinator.setTraceRecordingEnabled(true)
                await self?.exportDiagnostics()
            }
        } else {
            monitor.stopMonitoring()
            // Neither the bounded session nor the open trace may outlive the opt-in that
            // authorized them (docs/05 §9).
            Task { [weak self, coordinator] in
                await coordinator.stopDrivingSessionForOptOut()
                await coordinator.setTraceRecordingEnabled(false)
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

/// docs/05 §3a: leaving `CANDIDATE_PENDING` is a checkpoint write, and the checkpoint is
/// behind the coordinator's actor. The screen and the notification action both arrive on
/// the main actor, so this is the one hop between them.
extension DetectionRuntime: CandidateResolving {
    func resolveCandidate(_ outcome: CandidateOutcome) async {
        await coordinator.resolveCandidate(outcome)
        await exportDiagnostics()
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
        Task { [weak self, coordinator, carLink] in
            await coordinator.handleSignificantChange(sample)
            // The one wake that reliably happens during a drive, so it is where the §3a
            // link edges are derived from consecutive samples of the audio route.
            await coordinator.handleCarLink(carLink.observe())
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
        Task { [weak self, coordinator, carLink] in
            await coordinator.evaluateDrivingTimeouts()
            // While a bounded session is open this ticks far more often than a significant
            // change arrives, which is what makes a disconnect at the destination land in
            // seconds rather than minutes.
            await coordinator.handleCarLink(carLink.observe())
            await self?.exportDiagnostics()
        }
    }
}

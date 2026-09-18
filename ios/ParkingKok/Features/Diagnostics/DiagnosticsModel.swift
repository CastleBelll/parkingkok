import Foundation
import Observation

/// Backing state for the P0 diagnostics screen.
///
/// Pulls from `DetectionRuntime` on demand instead of observing it: this screen is an
/// instrument read at a moment in time, not a live product surface.
@MainActor
@Observable
final class DiagnosticsModel {
    private let runtime: DetectionRuntime

    private(set) var snapshot = RehydrationSnapshot()
    private(set) var locationAuthorization: LocationAuthorization = .notDetermined
    private(set) var motionAuthorization: MotionAuthorization = .notDetermined
    private(set) var isMotionHistoryAvailable = false
    private(set) var isMonitoring = false
    private(set) var storeSetupFailure: String?
    private(set) var traceSummary: TraceSummary = .empty
    /// What the system says about alerts. The label prompt is the only notification this
    /// build posts, so an unauthorized reading here explains an empty label harvest.
    private(set) var notificationAuthorization = "unknown"
    var isSmartDetectionEnabled = false

    init(runtime: DetectionRuntime = .shared) {
        self.runtime = runtime
    }

    var pendingPermissionRequest: LocationPermissionRequest? {
        PermissionRequestPolicy.nextRequest(
            location: locationAuthorization,
            smartDetectionEnabled: isSmartDetectionEnabled
        )
    }

    func refresh() async {
        runtime.refreshAuthorizationStatuses()
        locationAuthorization = runtime.locationAuthorization
        motionAuthorization = runtime.motionAuthorization
        isMotionHistoryAvailable = runtime.isMotionHistoryAvailable
        isMonitoring = runtime.isMonitoringSignificantChanges
        isSmartDetectionEnabled = runtime.isSmartDetectionEnabled
        storeSetupFailure = runtime.storeSetupFailure
        traceSummary = runtime.traceStore?.summary() ?? .empty
        notificationAuthorization = await runtime.notificationAuthorization()
        snapshot = await runtime.snapshot()
    }

    func setSmartDetection(_ enabled: Bool) async {
        runtime.setSmartDetectionEnabled(enabled)
        await refresh()
    }

    func requestLocationPermission() async {
        runtime.requestNextLocationPermission()
        await refresh()
    }

    func requestMotionPermission() async {
        await runtime.requestMotionPermission()
        await refresh()
    }

    func requestNotificationPermission() async {
        await runtime.requestNotificationPermission()
        await refresh()
    }
}

/// Presentation helpers. Kept beside the screen because they exist only for it.
extension RehydrationSnapshot {
    var checkpointDescription: String {
        switch checkpointLoad {
        case let .restored(checkpoint):
            "\(checkpoint.state.rawValue) · rev \(checkpoint.revision)"
        case .absent:
            "없음 (새로 생성)"
        case let .failed(failure):
            failure.diagnosticDescription
        }
    }

    /// The live checkpoint, which after a fresh install is the seeded one rather than
    /// anything the load returned.
    var displayCheckpoint: DetectionCheckpoint? {
        currentCheckpoint
    }

    var isCheckpointFailed: Bool {
        if case .failed = checkpointLoad {
            return true
        }
        return false
    }
}

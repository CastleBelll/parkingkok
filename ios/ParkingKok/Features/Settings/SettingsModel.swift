import Foundation
import Observation
import UIKit

/// What the settings screen can truthfully say about the system right now.
///
/// Reads the detection runtime rather than keeping its own copy, so the Smart Detection
/// switch here and the one on the diagnostics screen cannot disagree.
@MainActor
@Observable
final class SettingsModel {
    private let runtime: DetectionRuntime

    private(set) var locationAuthorization: LocationAuthorization = .notDetermined
    private(set) var motionAuthorization: MotionAuthorization = .notDetermined
    private(set) var notificationAuthorization = "unknown"
    var isSmartDetectionEnabled = false

    init(runtime: DetectionRuntime = .shared) {
        self.runtime = runtime
    }

    func refresh() async {
        runtime.refreshAuthorizationStatuses()
        locationAuthorization = runtime.locationAuthorization
        motionAuthorization = runtime.motionAuthorization
        isSmartDetectionEnabled = runtime.isSmartDetectionEnabled
        notificationAuthorization = await runtime.notificationAuthorization()
    }

    /// docs/04 §4: Always is requested contextually, only after this opt-in.
    func setSmartDetection(_ enabled: Bool) async {
        runtime.setSmartDetectionEnabled(enabled)
        if enabled {
            runtime.requestNextLocationPermission()
        }
        await refresh()
    }

    func requestNotificationPermission() async {
        await runtime.requestNotificationPermission()
        await refresh()
    }

    /// A denial can only be undone in Settings.app — saying so beats a button that
    /// silently does nothing the second time.
    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    var locationStatusText: String {
        switch locationAuthorization {
        case .always: "항상 허용"
        case .whenInUse: "앱 사용 중"
        case .denied: "거부됨"
        case .restricted: "제한됨"
        case .notDetermined: "미설정"
        }
    }

    var motionStatusText: String {
        switch motionAuthorization {
        case .authorized: "허용됨"
        case .denied: "거부됨"
        case .restricted: "제한됨"
        case .notDetermined: "미설정"
        }
    }

    var notificationStatusText: String {
        switch notificationAuthorization {
        case "authorized", "provisional", "ephemeral": "허용됨"
        case "denied": "거부됨"
        case "notDetermined": "미설정"
        default: "알 수 없음"
        }
    }
}

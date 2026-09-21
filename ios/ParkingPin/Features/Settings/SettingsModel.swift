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
    private let analyticsConsent: any AnalyticsConsentStoring
    private let analytics: any AnalyticsRecording

    private(set) var locationAuthorization: LocationAuthorization = .notDetermined
    private(set) var motionAuthorization: MotionAuthorization = .notDetermined
    private(set) var notificationAuthorization = "unknown"
    var isSmartDetectionEnabled = false
    /// docs/07 "동의". Off until the user turns it on, and read back from the store rather
    /// than assumed, so the row cannot claim a consent that was never persisted.
    var isAnalyticsConsentGranted = false

    init(
        runtime: DetectionRuntime = .shared,
        analyticsConsent: any AnalyticsConsentStoring = AnalyticsComposition.consent,
        analytics: any AnalyticsRecording = AnalyticsComposition.recorder
    ) {
        self.runtime = runtime
        self.analyticsConsent = analyticsConsent
        self.analytics = analytics
    }

    func refresh() async {
        runtime.refreshAuthorizationStatuses()
        locationAuthorization = runtime.locationAuthorization
        motionAuthorization = runtime.motionAuthorization
        isSmartDetectionEnabled = runtime.isSmartDetectionEnabled
        isAnalyticsConsentGranted = analyticsConsent.isGranted
        notificationAuthorization = await runtime.notificationAuthorization()
    }

    /// docs/04 §4: Always is requested contextually, only after this opt-in.
    func setSmartDetection(_ enabled: Bool) async {
        runtime.setSmartDetectionEnabled(enabled)
        if enabled {
            runtime.requestNextLocationPermission()
        }
        // docs/17 §2 `smart_detection_enabled` — the detection opt-in, which is a product
        // signal. The analytics opt-in below is not reported at all.
        analytics.record(.smartDetectionEnabled(enabled))
        await refresh()
    }

    /// docs/07 "동의": persisted immediately, and a revocation takes effect on the next
    /// event because `AnalyticsRecorder` re-reads the flag every time.
    ///
    /// Nothing is reported here, in either direction. An event on the grant would be
    /// decided by the state before consent existed, and one on the revocation would be a
    /// transmission after it was withdrawn.
    func setAnalyticsConsent(_ granted: Bool) async {
        analyticsConsent.setGranted(granted)
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

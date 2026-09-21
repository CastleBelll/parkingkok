import AuthenticationServices
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
    private let account: LinkingAccountIdentity
    /// Holds the nonce between the button's two callbacks, so it has to outlive them both.
    private let appleSignIn = AppleSignInRequest()

    private(set) var locationAuthorization: LocationAuthorization = .notDetermined
    private(set) var motionAuthorization: MotionAuthorization = .notDetermined
    private(set) var notificationAuthorization = "unknown"
    var isSmartDetectionEnabled = false
    /// docs/07 "동의". Off until the user turns it on, and read back from the store rather
    /// than assumed, so the row cannot claim a consent that was never persisted.
    var isAnalyticsConsentGranted = false
    /// docs/07 §13a. 회원가입 없음 is the default, so this starts `.none` and stays there
    /// for a user who never signs in.
    private(set) var accountState: AccountState = .none
    private(set) var isAccountBusy = false
    /// Shown under the row, and only after something the user did. Never an SDK message.
    private(set) var accountMessage: String?

    init(
        runtime: DetectionRuntime = .shared,
        analyticsConsent: any AnalyticsConsentStoring = AnalyticsComposition.consent,
        analytics: any AnalyticsRecording = AnalyticsComposition.recorder,
        account: LinkingAccountIdentity = IdentityComposition.account
    ) {
        self.runtime = runtime
        self.analyticsConsent = analyticsConsent
        self.analytics = analytics
        self.account = account
    }

    func refresh() async {
        runtime.refreshAuthorizationStatuses()
        locationAuthorization = runtime.locationAuthorization
        motionAuthorization = runtime.motionAuthorization
        isSmartDetectionEnabled = runtime.isSmartDetectionEnabled
        isAnalyticsConsentGranted = analyticsConsent.isGranted
        notificationAuthorization = await runtime.notificationAuthorization()
        accountState = account.state()
        #if PK_DEV
            // Refreshed here rather than only at launch, so a sign-in can be read off the
            // device the moment it happens — the 2026-09-21 case needed the app's view and
            // the project's view at the same instant to tell which one was lying.
            FirebaseSelfCheck.writeAccountState()
        #endif
    }

    var isSignedIn: Bool {
        if case .linked = accountState {
            true
        } else {
            false
        }
    }

    /// The first half of `SignInWithAppleButton`: scopes and the hashed nonce.
    func prepareAppleRequest(_ request: ASAuthorizationAppleIDRequest) {
        accountMessage = nil
        appleSignIn.prepare(request)
    }

    /// The second half. A cancellation says nothing — the user already knows they
    /// cancelled, and a red line under the button would read as a failure.
    func completeAppleSignIn(_ result: Result<ASAuthorization, any Error>) async {
        guard !isAccountBusy else { return }
        switch appleSignIn.credential(from: result) {
        case .cancelled:
            return
        case .failed:
            accountMessage = Self.linkFailedMessage
        case let .credential(credential):
            isAccountBusy = true
            let outcome = await account.signIn(with: credential)
            isAccountBusy = false
            accountMessage = Self.message(for: outcome)
            await refresh()
        }
    }

    /// Unlinks the provider. The records are untouched, because they were never in the
    /// account (docs/07 §13a) — which is also why this is not called 로그아웃 anywhere the
    /// user can read.
    func signOut() async {
        guard !isAccountBusy else { return }
        isAccountBusy = true
        accountMessage = nil
        await account.signOut()
        isAccountBusy = false
        await refresh()
    }

    #if PK_DEV
        /// The uid and every provider attached to it, for the DEV-only row in Settings.
        var accountDebugSummary: String {
            switch accountState {
            case .none: "no user"
            case let .anonymous(uid): "\(uid) · anonymous"
            case let .linked(uid, provider): "\(uid) · \(provider.rawValue)"
            }
        }
    #endif

    private static let linkFailedMessage = "지금은 연결할 수 없어요. 잠시 후 다시 시도해 주세요."

    private static func message(for result: AccountLinkResult) -> String? {
        switch result {
        case .linked:
            nil
        case .alreadyLinkedElsewhere:
            // docs/07 §13b. The second sentence is the one that matters: a user who reads
            // "이미 사용 중" without it will assume this phone just lost its records.
            "이 Apple 계정은 다른 기기에서 이미 사용 중이에요. 이 기기의 주차 기록은 그대로 있어요."
        case .failed:
            linkFailedMessage
        }
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

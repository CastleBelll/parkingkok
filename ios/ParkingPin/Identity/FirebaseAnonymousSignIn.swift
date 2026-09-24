import FirebaseAuth
import Foundation

/// The Firebase half of `AnonymousIdentityProviding` — thin on purpose.
///
/// Every decision (when to sign in, what a failure means, how concurrent callers are
/// serialised) lives in `LazyAnonymousIdentity`, where a test can reach it. What is left
/// here is the one call that genuinely needs a live `FirebaseApp`.
struct FirebaseAnonymousSignIn: AnonymousSigningIn {
    private let bootstrap: FirebaseBootstrap

    init(bootstrap: FirebaseBootstrap = .shared) {
        self.bootstrap = bootstrap
    }

    /// Deliberately does **not** start Firebase: asking whether we are already signed in
    /// must not be the thing that brings the SDK up (docs/07 "동의").
    func currentUid() -> String? {
        guard bootstrap.isStarted else { return nil }
        return Auth.auth().currentUser?.uid
    }

    /// Signs in **only if `configure()` did not just restore someone**.
    ///
    /// `currentUid()` above cannot answer before Firebase is up, and Firebase is up only
    /// when something needs it — so on every cold launch the first caller arrived here with
    /// `nil` in hand. Minting straight away created a *new* anonymous account each time,
    /// silently abandoning the previous one and any provider linked to it. Two launches on
    /// 2026-09-21 left two anonymous accounts in the project, which is how it was found.
    ///
    /// `configure()` restores the persisted session from the keychain, so the question has
    /// to be asked again once it can be answered.
    func signIn() async throws -> String {
        guard bootstrap.start() else { throw AnonymousSignInError.firebaseUnavailable }
        if let restored = Auth.auth().currentUser {
            return restored.uid
        }
        return try await Auth.auth().signInAnonymously().user.uid
    }
}

enum AnonymousSignInError: Error {
    /// This build shipped no `GoogleService-Info.plist`, so there is no project to sign
    /// in to. Not a defect — CI and fork checkouts are built exactly this way.
    case firebaseUnavailable
}

/// Builds the identity for the running app.
///
/// One instance per process, mirroring `AnalyticsComposition`, so two features cannot each
/// mint their own anonymous account.
///
/// **Nothing constructs this during launch.** `RootView` does not touch it, `AppDelegate`
/// does not touch it, and no screen awaits it — which is what "lazy" has to mean if home is
/// to render on a device with no network (docs/04 §14).
enum IdentityComposition {
    static let anonymous: any AnonymousIdentityProviding = FirebaseBootstrap.shared.isAvailable
        ? LazyAnonymousIdentity(signIn: FirebaseAnonymousSignIn())
        : UnavailableAnonymousIdentity()

    /// The same `anonymous` above, deliberately: linking attaches a provider to *that* uid,
    /// so a second identity here would be the §13a bug this layer exists to prevent.
    static let account = LinkingAccountIdentity(
        anonymous: anonymous,
        linking: FirebaseBootstrap.shared.isAvailable
            ? FirebaseAccountLinking()
            : UnavailableAccountLinking()
    )
}

#if PK_DEV
    /// DEV-only hook for the M5 verification run (`ios/README.md`).
    ///
    /// There is no backend feature yet, so nothing in the product calls
    /// `AnonymousIdentityProviding`, and the simulator offers no way to tap the consent
    /// toggle from a script. This is how both paths get exercised against the real project
    /// without inventing a screen for either — the same kind of hook as
    /// `PK_SEED_SAMPLE_PARKING`, and it is compiled out of STAGING and PROD entirely.
    ///
    /// **Detached, and never awaited.** The point it demonstrates is that a sign-in cannot
    /// delay the first frame (docs/04_IOS_IMPLEMENTATION.md §14).
    enum FirebaseSelfCheck {
        private static let environmentKey = "PK_FIREBASE_SELFCHECK"

        static func runIfRequested(
            identity: any AnonymousIdentityProviding = IdentityComposition.anonymous,
            analytics: any AnalyticsRecording = AnalyticsComposition.recorder
        ) {
            guard ProcessInfo.processInfo.environment[environmentKey] == "1" else { return }
            Task.detached {
                let uid = await identity.uid()
                AppLog.lifecycle.info("selfcheck anonymous uid: \(uid ?? "unavailable", privacy: .public)")
                writeAccountState()
                // Goes through the ordinary recorder, so the consent gate decides this
                // exactly as it decides a real event — which is the half being verified.
                analytics.record(.onboardingCompleted)
            }
        }

        /// Drops what Auth believes about this device into the App Group container, where
        /// `devicectl device copy from` can read it.
        ///
        /// It exists because on 2026-09-21 the Settings screen said the account was linked
        /// and `firebase auth:export` said it was anonymous, and there was no way to check
        /// either: `log collect --device-udid` needs root, and `--console` does not carry
        /// os_log. A uid and a provider id are neither coordinates nor credentials
        /// (docs/09 §11), and this whole enum is compiled out of STAGING and PROD.
        static func writeAccountState(lastLinkOutcome: String? = nil) {
            guard let identifier = Bundle.main
                .object(forInfoDictionaryKey: "PKAppGroupIdentifier") as? String,
                let container = FileManager.default
                .containerURL(forSecurityApplicationGroupIdentifier: identifier)
            else { return }

            guard FirebaseBootstrap.shared.isStarted else { return }
            let user = Auth.auth().currentUser
            let state: [String: Any] = [
                "uid": user?.uid ?? "none",
                "isAnonymous": user?.isAnonymous ?? false,
                "providers": user?.providerData.map(\.providerID) ?? [],
                "capturedAt": ISO8601DateFormatter().string(from: Date()),
                "lastLinkOutcome": lastLinkOutcome ?? "none"
            ]
            // `Library/Application Support` for the same reason the widget projection uses
            // it: it is the only part of a shared container `devicectl` will read.
            let directory = container.appending(
                path: "Library/Application Support/Diagnostics",
                directoryHint: .isDirectory
            )
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            guard let data = try? JSONSerialization.data(withJSONObject: state, options: .prettyPrinted)
            else { return }
            try? data.write(
                to: directory.appending(path: "account.json", directoryHint: .notDirectory),
                options: .atomic
            )
        }
    }
#endif

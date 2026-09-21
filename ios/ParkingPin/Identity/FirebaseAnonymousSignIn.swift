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

    func signIn() async throws -> String {
        guard bootstrap.start() else { throw AnonymousSignInError.firebaseUnavailable }
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
                // Goes through the ordinary recorder, so the consent gate decides this
                // exactly as it decides a real event — which is the half being verified.
                analytics.record(.onboardingCompleted)
            }
        }
    }
#endif

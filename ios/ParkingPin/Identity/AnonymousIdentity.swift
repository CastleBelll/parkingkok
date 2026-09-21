import Foundation

/// The app's technical identity (docs/07 §4, §13).
///
/// The UI stays 회원가입 없음: there is no sign-in screen and never was. This exists only
/// because a backend call needs *some* caller, and it is created at the moment one is
/// actually made — docs/04_IOS_IMPLEMENTATION.md §14: "not necessarily before home can
/// render."
///
/// It answers `nil` rather than throwing because every caller's honest response to a failed
/// sign-in is to do without. docs/00 keeps parking records local, so there is no screen a
/// missing uid is allowed to break, and offline is an ordinary state rather than an error.
protocol AnonymousIdentityProviding: Sendable {
    /// The current anonymous uid, signing in if there is not one yet.
    func uid() async -> String?
}

/// The single Firebase Auth operation this app performs, behind a seam a test can drive.
///
/// `Auth` needs a configured `FirebaseApp`, so keeping the caching and failure policy on
/// this side of the boundary is what makes that policy testable at all — the same layering
/// as Android's `AnonymousSignIn`.
protocol AnonymousSigningIn: Sendable {
    /// The already-signed-in uid, or `nil`. A local read; it never touches the network.
    func currentUid() -> String?
    /// Signs in anonymously and returns the new uid. Throws on any failure.
    func signIn() async throws -> String
}

/// Signs in at most once, on first use.
///
/// An `actor` rather than a lock because the work it serialises is `async`. Sharing one
/// in-flight `Task` is not premature: two features asking for a uid at the same moment would
/// otherwise start two sign-ins, and Firebase would answer with two different anonymous
/// accounts — the second silently replacing the first and taking any server state keyed to
/// it along.
actor LazyAnonymousIdentity: AnonymousIdentityProviding {
    private let signIn: any AnonymousSigningIn
    private var inFlight: Task<String, any Error>?

    init(signIn: any AnonymousSigningIn) {
        self.signIn = signIn
    }

    func uid() async -> String? {
        // The SDK restores a persisted session locally, so the common case never reaches
        // the network or the task below.
        if let restored = signIn.currentUid() {
            return restored
        }

        let task: Task<String, any Error>
        if let existing = inFlight {
            task = existing
        } else {
            task = Task { [signIn] in try await signIn.signIn() }
            inFlight = task
        }

        do {
            return try await task.value
        } catch is CancellationError {
            // Our caller went away. The sign-in itself is unstructured and still running,
            // so the in-flight task stays — clearing it here would let the next caller
            // start a second account alongside it.
            return nil
        } catch {
            // Offline is the common case and not something to show the user. Clearing the
            // task is what lets a later caller try again: offline now is not offline
            // forever. Only the first caller out of the `await` finds its own task here.
            if inFlight == task {
                inFlight = nil
            }
            // The type name only — an auth error message can carry a project or token
            // fragment, and docs/09 §11 keeps that out of the log.
            AppLog.lifecycle
                .info("anonymous sign-in unavailable: \(String(describing: type(of: error)), privacy: .public)")
            return nil
        }
    }
}

/// The identity for a build with no Firebase project.
///
/// Returning `nil` rather than a placeholder uid: a fake identity would be accepted by a
/// caller and then rejected by the backend, which is a worse failure than no identity.
struct UnavailableAnonymousIdentity: AnonymousIdentityProviding {
    func uid() async -> String? {
        nil
    }
}

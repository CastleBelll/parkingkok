import FirebaseCore
import Foundation

/// The only place `FirebaseApp.configure()` is ever called.
///
/// **Firebase does not start with the app.** It starts when something genuinely needs it —
/// analytics consent being granted, or a backend feature asking for an identity. That is
/// what makes "nothing goes out before consent" a structural fact rather than a setting the
/// SDK is trusted to honour: with the toggle off and no backend feature touched, there is
/// no configured `FirebaseApp` for anything to transmit through.
///
/// The `FIREBASE_ANALYTICS_COLLECTION_ENABLED = false` key in `Info.plist` is the second
/// line of defence, for the case this one cannot cover: an anonymous sign-in starts Firebase
/// while analytics consent is still off, and Analytics must stay silent inside a process
/// where Firebase is now running.
///
/// `@unchecked Sendable` with an `NSLock`, the same shape as `FileTraceStore`: the state it
/// guards is the SDK's, not its own, and pinning it to an actor would make every sink send
/// an `await` — a call site that has to await its own telemetry is one that skips it.
final class FirebaseBootstrap: @unchecked Sendable {
    static let shared = FirebaseBootstrap()

    private let lock = NSLock()

    /// Whether this build shipped a `GoogleService-Info.plist`.
    ///
    /// It is never committed (docs/18 / `.gitignore`), so CI and fork checkouts build
    /// without one. Those builds have no Firebase project and nothing here pretends
    /// otherwise — `start()` answers `false` and every caller degrades to doing nothing.
    var isAvailable: Bool {
        Bundle.main.path(forResource: Self.optionsResource, ofType: Self.optionsExtension) != nil
    }

    /// Whether Firebase is already running, without starting it.
    ///
    /// The read that lets a revocation switch collection off without being the thing that
    /// switches Firebase on.
    var isStarted: Bool {
        FirebaseApp.app() != nil
    }

    /// Configures Firebase if it is not configured yet, and reports whether it is up.
    ///
    /// Idempotent and safe from any thread. The SDK's own `FirebaseApp.app()` is the source
    /// of truth rather than a second flag kept here, so the two cannot drift.
    @discardableResult
    func start() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if FirebaseApp.app() != nil {
            return true
        }
        guard isAvailable else { return false }
        FirebaseApp.configure()
        return true
    }

    private static let optionsResource = "GoogleService-Info"
    private static let optionsExtension = "plist"
}

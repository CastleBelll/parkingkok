import Foundation
import Testing
@testable import ParkingKok

// MARK: - Firebase parameter mapping

/// The last hop before an event leaves the device.
///
/// `AnalyticsEventContractTests` already holds the payload against docs/17 §3. This holds
/// the Firebase-shaped copy against it too, because a mapping is exactly where a value can
/// quietly change meaning — or disappear.
struct FirebaseAnalyticsParameterTests {
    private static let occurredAt = Date(timeIntervalSince1970: 1_780_000_000)

    private var allParameters: [[String: Any]] {
        EventSamples.all.map { firebaseParameters(AnalyticsPayload($0, occurredAt: Self.occurredAt)) }
    }

    @Test("Every parameter is a String or an Int — never a Bool")
    func parameterTypes() {
        let values = allParameters.flatMap(\.values)
        #expect(!values.isEmpty)
        for value in values {
            // A Bool would reach Firebase as a number here and be dropped outright on
            // Android, which is the kind of divergence docs/05 parity exists to prevent.
            #expect(!(value is Bool), "\(type(of: value)) is not a Firebase parameter type")
            #expect(value is String || value is Int, "unsupported parameter type \(type(of: value))")
        }
    }

    @Test("Booleans travel as the same strings Android sends")
    func flagsAreStrings() {
        let payload = AnalyticsPayload(.parkingCandidateCreated(EventSamples.detection), occurredAt: Self.occurredAt)
        let parameters = firebaseParameters(payload)

        #expect(parameters[AnalyticsPayload.Key.walkingEvidence] as? String == "true")
        #expect(parameters[AnalyticsPayload.Key.gpsDegradation] as? String == "false")
    }

    @Test("detectorVersion travels as a number, so versions stay comparable")
    func detectorVersionIsNumeric() {
        let payload = AnalyticsPayload(.parkingAutoEnd, occurredAt: Self.occurredAt)

        // docs/17 §4 compares rejection rates per version, which wants a number.
        #expect(firebaseParameters(payload)[AnalyticsPayload.Key.detectorVersion] as? Int == DetectorVersion.current)
    }

    @Test("The mapping invents no key and drops none")
    func keysAreOneToOne() {
        for event in EventSamples.all {
            let payload = AnalyticsPayload(event, occurredAt: Self.occurredAt)
            let mapped = firebaseParameters(payload)

            #expect(Set(mapped.keys) == Set(payload.parameters.keys))
            #expect(AnalyticsPayload.Key.all.isSuperset(of: mapped.keys))
        }
    }

    @Test("occurredAt never becomes a parameter")
    func clockStampStaysMetadata() {
        // docs/17 §3 fixes the allowed property list and a timestamp is not on it.
        let payload = AnalyticsPayload(.parkingManualSaved, occurredAt: Self.occurredAt)
        let parameters = firebaseParameters(payload)

        #expect(parameters.keys.allSatisfy { !$0.contains("time") && !$0.contains("occurred") })
        #expect(!parameters.values.contains { ($0 as? String) == String(describing: Self.occurredAt) })
    }
}

// MARK: - Consent reaching the SDK

/// Remembers every switch flip, in order.
private final class RecordingCollectionControl: AnalyticsCollectionControlling, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [Bool] = []

    var calls: [Bool] {
        lock.withLock { recorded }
    }

    func setEnabled(_ granted: Bool) {
        lock.withLock { recorded.append(granted) }
    }
}

/// **The half of docs/07 "동의" that `AnalyticsConsentGateTests` cannot see.**
///
/// That suite proves no *app* event escapes before consent. Firebase Analytics also reports
/// on its own — `session_start`, `first_open`, `app_update` — and none of that passes
/// through `AnalyticsRecorder`. These hold the SDK's own switch to the same flag.
struct FirebaseGatedConsentStoreTests {
    @Test("Granting consent switches the SDK on in the same call that persists it")
    func grantReachesTheSdk() {
        let base = MutableAnalyticsConsentStore(granted: false)
        let control = RecordingCollectionControl()
        let store = FirebaseGatedAnalyticsConsentStore(base: base, collection: control)

        store.setGranted(true)

        #expect(base.isGranted)
        #expect(control.calls == [true])
    }

    @Test("Revoking consent switches the SDK off without waiting for another event")
    func revocationReachesTheSdk() {
        let base = MutableAnalyticsConsentStore(granted: true)
        let control = RecordingCollectionControl()
        let store = FirebaseGatedAnalyticsConsentStore(base: base, collection: control)

        store.setGranted(false)

        // docs/07: 끄면 즉시 중단한다. Nothing had to be reported for the SDK to hear it.
        #expect(!base.isGranted)
        #expect(control.calls == [false])
    }

    @Test("The flag is persisted even if the SDK call is the thing that fails")
    func persistenceComesFirst() {
        let base = MutableAnalyticsConsentStore(granted: false)
        let store = FirebaseGatedAnalyticsConsentStore(base: base, collection: RecordingCollectionControl())

        store.setGranted(true)

        // The stored flag is the truth; `applyStoredConsent()` re-reads it next launch.
        #expect(base.isGranted)
    }

    @Test("Reading consent never touches the SDK")
    func readsAreFree() {
        let control = RecordingCollectionControl()
        let store = FirebaseGatedAnalyticsConsentStore(
            base: MutableAnalyticsConsentStore(granted: true),
            collection: control
        )

        _ = store.isGranted
        _ = store.isGranted

        // A read that switched something on would make `AnalyticsRecorder`'s gate — which
        // reads the flag before every single event — the leak it exists to prevent.
        #expect(control.calls.isEmpty)
    }
}

// MARK: - Anonymous identity

/// A scripted sign-in that counts how often it was actually asked, and remembers the uid
/// afterwards the way the SDK's persisted session does.
private final class FakeAnonymousSignIn: AnonymousSigningIn, @unchecked Sendable {
    private let lock = NSLock()
    private var uid: String?
    private var attempts = 0
    private let result: @Sendable (Int) throws -> String
    private let gate: (@Sendable () async -> Void)?

    init(gate: (@Sendable () async -> Void)? = nil, result: @escaping @Sendable (Int) throws -> String) {
        self.result = result
        self.gate = gate
    }

    var signInCount: Int {
        lock.withLock { attempts }
    }

    func currentUid() -> String? {
        lock.withLock { uid }
    }

    func signIn() async throws -> String {
        let attempt = lock.withLock { attempts += 1; return attempts }
        await gate?()
        let value = try result(attempt)
        lock.withLock { uid = value }
        return value
    }
}

/// **docs/07 §4 / §13.** Anonymous Auth happens when a backend feature needs a caller — not
/// at launch, not once per call, and never in a way that can take a screen down with it.
struct LazyAnonymousIdentityTests {
    @Test("Constructing the identity signs nobody in")
    func constructionIsFree() {
        let signIn = FakeAnonymousSignIn { _ in "uid-1" }

        _ = LazyAnonymousIdentity(signIn: signIn)

        // docs/04_IOS §14: "not necessarily before home can render". Nothing here reaches
        // the network until a caller asks.
        #expect(signIn.signInCount == 0)
    }

    @Test("The first caller signs in and gets the uid")
    func firstCallerSignsIn() async {
        let signIn = FakeAnonymousSignIn { _ in "uid-1" }
        let identity = LazyAnonymousIdentity(signIn: signIn)

        #expect(await identity.uid() == "uid-1")
        #expect(signIn.signInCount == 1)
    }

    @Test("Later callers reuse the session instead of signing in again")
    func sessionIsReused() async {
        let signIn = FakeAnonymousSignIn { _ in "uid-1" }
        let identity = LazyAnonymousIdentity(signIn: signIn)

        for _ in 0 ..< 5 {
            _ = await identity.uid()
        }

        // A second sign-in would mint a second anonymous account and silently abandon
        // whatever server state was keyed to the first.
        #expect(signIn.signInCount == 1)
    }

    @Test("Concurrent callers produce one account, not two")
    func concurrentCallersShareOneSignIn() async {
        // A sign-in that cannot finish until every caller is provably inside `uid()`.
        let barrier = SignInBarrier()
        let signIn = FakeAnonymousSignIn(gate: { await barrier.wait() }) { _ in "uid-1" }
        let identity = LazyAnonymousIdentity(signIn: signIn)

        async let first = identity.uid()
        async let second = identity.uid()
        await barrier.release()

        #expect(await first == "uid-1")
        #expect(await second == "uid-1")
        #expect(signIn.signInCount == 1)
    }

    @Test("A failed sign-in answers nil rather than throwing at the caller")
    func failureDegrades() async {
        let identity = LazyAnonymousIdentity(signIn: FakeAnonymousSignIn { _ in throw TestSignInError.offline })

        // Offline is an ordinary state: every parking feature is local, so nothing on
        // screen may depend on this succeeding.
        #expect(await identity.uid() == nil)
    }

    @Test("A failure is not cached — the next caller tries again")
    func failureIsRetried() async {
        let signIn = FakeAnonymousSignIn { attempt in
            if attempt == 1 {
                throw TestSignInError.offline
            }
            return "uid-1"
        }
        let identity = LazyAnonymousIdentity(signIn: signIn)

        #expect(await identity.uid() == nil)
        #expect(await identity.uid() == "uid-1")
        #expect(signIn.signInCount == 2)
    }

    @Test("A restored session needs no sign-in at all")
    func restoredSessionSkipsTheNetwork() async {
        // The SDK restores a persisted session locally, with no network call.
        let signIn = FakeAnonymousSignIn { _ in "uid-new" }
        _ = try? await signIn.signIn()
        let identity = LazyAnonymousIdentity(signIn: signIn)

        #expect(await identity.uid() == "uid-new")
        #expect(signIn.signInCount == 1)
    }

    @Test("The unavailable identity answers nil without pretending to have a uid")
    func unavailableIdentityIsHonest() async {
        // The build with no `GoogleService-Info.plist` — CI, fork checkouts.
        #expect(await UnavailableAnonymousIdentity().uid() == nil)
    }
}

private enum TestSignInError: Error {
    case offline
}

/// Holds every sign-in inside `signIn()` until the test releases them, so "concurrent"
/// means concurrent rather than "fast enough to overlap".
private actor SignInBarrier {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false

    func wait() async {
        if isOpen {
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        isOpen = true
        let pending = waiters
        waiters.removeAll()
        pending.forEach { $0.resume() }
    }
}

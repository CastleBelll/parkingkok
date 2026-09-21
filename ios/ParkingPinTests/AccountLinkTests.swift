import AuthenticationServices
import Foundation
import Testing
@testable import ParkingPin

/// One Auth, behind both halves.
///
/// The anonymous provider and the linking adapter share this store because production
/// shares one `FirebaseAuth`. Android's first attempt at these tests gave each fake its own
/// state, and every test passed while describing an app that could not exist: linking there
/// could not see the anonymous user it was supposed to link to.
final class FakeAuthStore: @unchecked Sendable {
    private let lock = NSLock()
    private var uid: String?
    private var provider: AccountProvider?
    private var anonymousSignIns = 0
    private var linkAttempts = 0

    /// The credential already belongs to another user (docs/07 §13b).
    var credentialIsTaken = false
    /// Offline, or no Firebase project in this build.
    var anonymousSignInFails = false

    var signInCount: Int {
        lock.withLock { anonymousSignIns }
    }

    var linkCount: Int {
        lock.withLock { linkAttempts }
    }

    var currentUid: String? {
        lock.withLock { uid }
    }

    func signInAnonymously() -> String? {
        lock.withLock {
            if anonymousSignInFails {
                return nil
            }
            if let uid {
                return uid
            }
            anonymousSignIns += 1
            uid = "uid-\(anonymousSignIns)"
            return uid
        }
    }

    func state() -> AccountState {
        lock.withLock {
            guard let uid else { return .none }
            guard let provider else { return .anonymous(uid: uid) }
            return .linked(uid: uid, provider: provider)
        }
    }

    func link(_ credential: AccountCredential) -> AccountLinkResult {
        lock.withLock {
            linkAttempts += 1
            guard let uid else { return .failed(reason: "no current user") }
            // §13b's shapes are not one shape: a credential owned by someone else is a
            // refusal, and a provider already on *this* account is nothing at all.
            if provider != nil {
                return .alreadyLinkedToThisAccount
            }
            if credentialIsTaken {
                return .alreadyLinkedElsewhere
            }
            provider = credential.provider
            // The uid is deliberately unchanged. A fake that minted a new one here would be
            // modelling `signIn(with:)`, which is the bug the real tests are about.
            return .linked(uid: uid, previousUid: uid)
        }
    }

    func signOut() {
        lock.withLock {
            uid = nil
            provider = nil
        }
    }
}

private struct StoreAnonymousIdentity: AnonymousIdentityProviding {
    let store: FakeAuthStore
    func uid() async -> String? {
        store.signInAnonymously()
    }
}

private struct StoreAccountLinking: AccountLinking {
    let store: FakeAuthStore
    func currentState() -> AccountState {
        store.state()
    }

    func link(_ credential: AccountCredential) async -> AccountLinkResult {
        store.link(credential)
    }

    func signOut() async {
        store.signOut()
    }
}

private func appleCredential() -> AccountCredential {
    .apple(idToken: "token", rawNonce: "raw-nonce")
}

/// **docs/07 §13a.** Signing in attaches a provider to the uid this device already has. It
/// is not a second sign-in, not a backup and not a restore.
struct LinkingAccountIdentityTests {
    @Test("Signing in keeps the uid the device already had")
    func linkPreservesTheUid() async throws {
        let store = FakeAuthStore()
        let anonymousUid = store.signInAnonymously()
        let identity = LinkingAccountIdentity(
            anonymous: StoreAnonymousIdentity(store: store),
            linking: StoreAccountLinking(store: store)
        )

        let result = await identity.signIn(with: appleCredential())

        // The measurement that settled it on the Android device, as an assertion: same uid
        // before and after, one account.
        #expect(try result == .linked(uid: #require(anonymousUid), previousUid: #require(anonymousUid)))
        #expect(store.currentUid == anonymousUid)
        #expect(store.signInCount == 1)
        #expect(try identity.state() == .linked(uid: #require(anonymousUid), provider: .apple))
    }

    @Test("A device that never had an anonymous uid gets one first, then links to it")
    func anonymousComesFirst() async {
        let store = FakeAuthStore()
        let identity = LinkingAccountIdentity(
            anonymous: StoreAnonymousIdentity(store: store),
            linking: StoreAccountLinking(store: store)
        )

        let result = await identity.signIn(with: appleCredential())

        // The shortcut this rules out is signing in with the provider directly when there
        // is no anonymous user: harmless today, and the habit that breaks §13a later.
        #expect(store.signInCount == 1)
        #expect(result == .linked(uid: "uid-1", previousUid: "uid-1"))
    }

    @Test("With no identity available, nothing is linked and nothing is claimed")
    func offlineDoesNotLink() async {
        let store = FakeAuthStore()
        store.anonymousSignInFails = true
        let identity = LinkingAccountIdentity(
            anonymous: StoreAnonymousIdentity(store: store),
            linking: StoreAccountLinking(store: store)
        )

        let result = await identity.signIn(with: appleCredential())

        #expect(result == .failed(reason: "no identity to link"))
        // Not attempted at all: a link with no current user is the one call that could
        // silently become a sign-in.
        #expect(store.linkCount == 0)
    }

    @Test("A credential owned by another account is refused, and this device keeps its uid")
    func conflictKeepsThisDevicesAccount() async throws {
        let store = FakeAuthStore()
        let anonymousUid = store.signInAnonymously()
        store.credentialIsTaken = true
        let identity = LinkingAccountIdentity(
            anonymous: StoreAnonymousIdentity(store: store),
            linking: StoreAccountLinking(store: store)
        )

        let result = await identity.signIn(with: appleCredential())

        #expect(result == .alreadyLinkedElsewhere)
        // docs/07 §13b: refusing is the point. Switching would abandon this uid, and the
        // records on this phone would look deleted to the person holding it.
        #expect(try store.state() == .anonymous(uid: #require(anonymousUid)))
    }

    @Test("Linking the same provider twice says so, and does not read as someone else's account")
    func secondLinkIsNotAConflict() async {
        let store = FakeAuthStore()
        _ = store.signInAnonymously()
        let identity = LinkingAccountIdentity(
            anonymous: StoreAnonymousIdentity(store: store),
            linking: StoreAccountLinking(store: store)
        )

        _ = await identity.signIn(with: appleCredential())
        let second = await identity.signIn(with: appleCredential())

        // The screen was simply behind. Telling this user their account is in use on
        // another device would send them looking for a phone that does not exist.
        #expect(second == .alreadyLinkedToThisAccount)
    }

    @Test("Signing out drops the provider without touching anything else")
    func signOutClearsTheAccount() async {
        let store = FakeAuthStore()
        _ = store.signInAnonymously()
        let identity = LinkingAccountIdentity(
            anonymous: StoreAnonymousIdentity(store: store),
            linking: StoreAccountLinking(store: store)
        )
        _ = await identity.signIn(with: appleCredential())

        await identity.signOut()

        #expect(identity.state() == .none)
        // The records were never in the account, so there is nothing here to restore and
        // nothing that signing out could have taken away.
    }

    @Test("A build with no Firebase project says so instead of pretending")
    func unavailableLinkingIsHonest() async {
        let linking = UnavailableAccountLinking()

        #expect(linking.currentState() == .none)
        #expect(await linking.link(appleCredential()) == .failed(reason: "Firebase is not configured in this build"))
    }
}

/// The Firebase adapter, at the one boundary a fake cannot stand in for.
struct FirebaseAccountLinkingTests {
    @Test("Reading the account state before Firebase has started answers none instead of trapping")
    func stateBeforeStartIsSafe() throws {
        // `Auth.auth()` does not return nil on an unconfigured app — it raises, and the
        // process dies. The identity is lazy (docs/04_IOS §14), so on an ordinary launch
        // nothing has started Firebase and this is the *first* thing the settings screen
        // asks: opening 설정 crashed the app on a real device until the guard existed.
        //
        // `isStarted: { false }` rather than a real bootstrap: `FirebaseApp.app()` is
        // process-global, and on a phone where the host app has already started Firebase
        // this test used to fail for a reason that had nothing to do with the guard.
        #expect(FirebaseAccountLinking(isStarted: { false }).currentState() == .none)
    }

    @Test("Signing out before Firebase has started is a no-op, not a crash")
    func signOutBeforeStartIsSafe() async {
        await FirebaseAccountLinking(isStarted: { false }).signOut()
    }

    @Test("Linking before Firebase has started fails instead of trapping")
    func linkBeforeStartIsSafe() async {
        let result = await FirebaseAccountLinking(isStarted: { false }).link(appleCredential())

        #expect(result == .failed(reason: "no current user"))
    }
}

/// The nonce, which is the part of Sign in with Apple that is easy to get subtly wrong.
struct AppleSignInRequestTests {
    @Test("The request carries the hash and the credential carries the raw value")
    func nonceIsHashedForAppleOnly() {
        let nonce = SignInNonce.generate()

        #expect(nonce.hashed == SignInNonce.sha256(nonce.raw))
        #expect(nonce.hashed != nonce.raw)
        // SHA-256 as lowercase hex, which is the encoding Firebase re-derives.
        #expect(nonce.hashed.count == 64)
        #expect(nonce.hashed.allSatisfy { $0.isHexDigit && !$0.isUppercase })
    }

    @Test("Every attempt gets its own nonce")
    func nonceIsFreshPerAttempt() {
        // A reused nonce would let a token captured from one sign-in be replayed into
        // another, which is the only thing the nonce is there for.
        #expect(SignInNonce.generate().raw != SignInNonce.generate().raw)
    }

    @Test("Preparing a request asks for the email and not the name")
    @MainActor
    func requestAsksForTheEmailOnly() {
        let request = ASAuthorizationAppleIDProvider().createRequest()

        AppleSignInRequest().prepare(request)

        // The relay address is what can answer "restore my subscription" later, and Apple
        // hands it over only at the first authorization — so it is asked for now or not at
        // all. A name is not asked for: docs/09 §1, nothing displays one.
        #expect(request.requestedScopes == [.email])
        #expect(request.nonce?.count == 64)
    }

    @Test("A cancelled sheet is not a failure")
    @MainActor
    func cancellationIsSilent() {
        let cancelled = NSError(
            domain: ASAuthorizationError.errorDomain,
            code: ASAuthorizationError.canceled.rawValue
        )

        let result = AppleSignInRequest().credential(from: .failure(cancelled))

        // The user knows they cancelled; a line of red text under the button would read as
        // something having gone wrong.
        #expect(result == .cancelled)
    }

    @Test("A real failure is reported by code, never by message")
    @MainActor
    func failureCarriesNoMessage() {
        let failure = NSError(
            domain: ASAuthorizationError.errorDomain,
            code: ASAuthorizationError.failed.rawValue,
            userInfo: [NSLocalizedDescriptionKey: "sensitive@example.com could not sign in"]
        )

        let result = AppleSignInRequest().credential(from: .failure(failure))

        // docs/09 §11: an auth message can carry an email or a token fragment.
        #expect(result == .failed(reason: "apple(\(ASAuthorizationError.failed.rawValue))"))
    }
}

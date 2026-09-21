import Foundation

/// Which sign-in provider a credential came from.
///
/// Deliberately not the SDK's provider-id strings: those are stable, but they are the SDK's,
/// and this layer is the one a test drives. Mirrors Android's `AccountProvider`.
enum AccountProvider: String, Sendable, Equatable, CaseIterable {
    case google
    case apple
}

/// A provider credential the UI has already obtained, in the shape Firebase needs it.
///
/// Only Apple today: iOS carries no Google SDK, and App Store Guideline 4.8 makes Sign in
/// with Apple the one option an iOS build cannot ship without. The consequence is written
/// down in docs/07 §13c — an account created on the iPhone cannot be reached from the
/// Android phone, and the reverse — because it is a product gap, not an oversight.
enum AccountCredential: Sendable, Equatable {
    /// `rawNonce` is the **un-hashed** nonce. Apple signed `sha256(rawNonce)` into the token
    /// and Firebase re-derives the hash to compare, so handing it the hash fails.
    case apple(idToken: String, rawNonce: String)

    var provider: AccountProvider {
        switch self {
        case .apple: .apple
        }
    }
}

/// What the app knows about who is signed in (docs/07 §13a).
enum AccountState: Sendable, Equatable {
    /// No Firebase identity yet. Ordinary — one is created lazily, on first backend use.
    case none
    /// A technical identity with no provider attached. The default, and 회원가입 없음.
    case anonymous(uid: String)
    /// The **same uid**, with a provider attached. That sameness is the whole point.
    case linked(uid: String, provider: AccountProvider)
}

/// What `AccountLinking.link` can answer.
enum AccountLinkResult: Sendable, Equatable {
    /// The provider is attached and the uid is unchanged from `previousUid`.
    case linked(uid: String, previousUid: String)

    /// docs/07 §13b: this Apple/Google account is already attached to another Firebase user.
    ///
    /// v1 refuses rather than switching. Switching abandons this device's anonymous uid and
    /// everything keyed to it, silently and unrecoverably, and the app has no server state
    /// yet that would make the trade worth it.
    case alreadyLinkedElsewhere

    /// docs/07 §13b: **a different collision, and it was being reported as the one above.**
    ///
    /// The provider is attached to nobody — the *email address* behind it is already used by
    /// another account with a different sign-in method. It is what a Firebase project with
    /// "one account per email address" answers when the same person signs in with Apple on
    /// one phone and Google on another, which is not an error the user can act on by
    /// finding their other device.
    case emailBelongsToAnotherAccount

    /// The provider is already on *this* user. Nothing to do, and not a failure: the screen
    /// is simply behind, so the caller refreshes and says 연결됨.
    case alreadyLinkedToThisAccount

    /// Offline, cancelled at the provider sheet, anything else. Nothing changed.
    case failed(reason: String)
}

/// The Firebase-facing half, kept small enough that the policy above it is testable.
///
/// Implementations **must** use `link(with:)` on the current user, never `signIn(with:)` —
/// docs/07 §13a. The difference is invisible today and expensive the moment anything is
/// keyed to the uid.
protocol AccountLinking: Sendable {
    func currentState() -> AccountState
    /// Attaches a provider credential, already obtained by the UI layer, to the *current* user.
    func link(_ credential: AccountCredential) async -> AccountLinkResult
    func signOut() async
}

/// Signs in anonymously first when there is no user yet, then links (docs/07 §13a).
///
/// The order is the reason this type exists. Linking needs a current user; with none, the
/// honest thing is to create the anonymous one *first* and link to it, so the uid the user
/// ends up with is the one this device would have had anyway. The tempting shortcut —
/// signing in with the provider directly when there is no anonymous user — produces a
/// different uid on a device that had never touched the backend, which is harmless today and
/// is the habit that breaks §13a later.
///
/// An `actor` for the same reason `LazyAnonymousIdentity` is one: two taps on the sign-in
/// row must not race into two accounts.
actor LinkingAccountIdentity {
    private let anonymous: any AnonymousIdentityProviding
    private let linking: any AccountLinking

    init(anonymous: any AnonymousIdentityProviding, linking: any AccountLinking) {
        self.anonymous = anonymous
        self.linking = linking
    }

    nonisolated func state() -> AccountState {
        linking.currentState()
    }

    func signIn(with credential: AccountCredential) async -> AccountLinkResult {
        // Establishes the anonymous uid if this device has never needed one. `nil` means
        // offline or no Firebase — the link would fail anyway, and saying so here is clearer
        // than letting the SDK phrase it.
        guard let existing = await anonymous.uid() else {
            return .failed(reason: "no identity to link")
        }
        let result = await linking.link(credential)
        if case let .linked(uid, _) = result, uid != existing {
            // Should be impossible with `link(with:)`, and worth shouting about if it
            // happens: it means the implementation signed in instead, and the previous
            // account is now orphaned.
            AppLog.identity.error("link changed the uid — docs/07 §13a says it must not")
        }
        return result
    }

    func signOut() async {
        await linking.signOut()
    }
}

/// The account layer for a build with no Firebase project.
struct UnavailableAccountLinking: AccountLinking {
    func currentState() -> AccountState {
        .none
    }

    func link(_ credential: AccountCredential) async -> AccountLinkResult {
        .failed(reason: "Firebase is not configured in this build")
    }

    func signOut() async {}
}

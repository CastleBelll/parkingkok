import FirebaseAuth
import Foundation

/// The one Firebase Auth call that attaches a provider to the account this device already
/// has (docs/07 §13a).
///
/// **`link(with:)`, never `signIn(with:)`.** Linking keeps the uid and attaches a provider
/// to it; signing in mints a new uid and abandons the anonymous one together with anything
/// keyed to it. There is no server state to lose today, which is exactly why the rule is
/// enforced here rather than remembered later — the failure is invisible until it is
/// expensive. Android's counterpart was measured doing this correctly on a real device:
/// same uid before and after, one user in the project.
///
/// Thin by design, like `FirebaseAnonymousSignIn` beside it: the ordering and the
/// uid-changed check live in `LinkingAccountIdentity`, where a test can reach them.
struct FirebaseAccountLinking: AccountLinking {
    func currentState() -> AccountState {
        guard let user = Auth.auth().currentUser else { return .none }
        let provider = user.providerData
            .lazy
            .compactMap { AccountProvider(providerId: $0.providerID) }
            .first
        guard let provider else { return .anonymous(uid: user.uid) }
        return .linked(uid: user.uid, provider: provider)
    }

    func link(_ credential: AccountCredential) async -> AccountLinkResult {
        guard let user = Auth.auth().currentUser else { return .failed(reason: "no current user") }
        let previousUid = user.uid
        let authCredential: AuthCredential = switch credential {
        case let .apple(idToken, rawNonce):
            // `fullName` stays nil on purpose: the request never asks for it (docs/09 §1).
            OAuthProvider.appleCredential(withIDToken: idToken, rawNonce: rawNonce, fullName: nil)
        }
        do {
            let result = try await user.link(with: authCredential)
            return .linked(uid: result.user.uid, previousUid: previousUid)
        } catch let error as NSError where error.code == AuthErrorCode.credentialAlreadyInUse.rawValue
            || error.code == AuthErrorCode.accountExistsWithDifferentCredential.rawValue
            || error.code == AuthErrorCode.providerAlreadyLinked.rawValue {
            // docs/07 §13b. Refused rather than switched: the alternative abandons this
            // device's uid silently, and v1 has nothing on the server worth that.
            return .alreadyLinkedElsewhere
        } catch {
            // The domain and code only — an auth message can carry a token fragment or an
            // email, and docs/09 §11 keeps both out of the log.
            let nsError = error as NSError
            AppLog.identity.info("link failed: \(nsError.domain, privacy: .public)(\(nsError.code, privacy: .public))")
            return .failed(reason: "\(nsError.domain)(\(nsError.code))")
        }
    }

    func signOut() async {
        try? Auth.auth().signOut()
    }
}

/// The SDK's provider ids, mapped in exactly one place.
extension AccountProvider {
    var providerId: String {
        switch self {
        case .google: "google.com"
        case .apple: "apple.com"
        }
    }

    init?(providerId: String) {
        guard let match = AccountProvider.allCases.first(where: { $0.providerId == providerId })
        else { return nil }
        self = match
    }
}

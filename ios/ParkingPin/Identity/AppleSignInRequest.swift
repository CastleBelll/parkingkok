import AuthenticationServices
import CryptoKit
import Foundation

/// What the account layer needs from a provider: a credential, or a reason there is none.
enum CredentialResult: Sendable, Equatable {
    case credential(AccountCredential)
    /// The user dismissed the sheet. Not a failure and never reported as one.
    case cancelled
    case failed(reason: String)
}

/// The two halves of Sign in with Apple that SwiftUI's `SignInWithAppleButton` hands out
/// separately: configuring the request, and reading the result.
///
/// It is a type rather than two closures in the view because of the nonce, which has to
/// survive from the first callback to the second.
///
/// ## The nonce, and why there are two of them
/// Apple signs a **hashed** nonce into the identity token; Firebase re-derives the hash and
/// compares. So the request carries `sha256(raw)` and the credential carries the **raw**
/// one. Sending the same value to both is the classic mistake, and it fails with an
/// invalid-credential error that says nothing about nonces.
///
/// The nonce is what stops a token captured from one sign-in being replayed into another,
/// which is why it is generated per attempt and never stored.
///
/// ## Why Apple and not Google on iOS
/// App Store Review Guideline 4.8: an app offering a third-party sign-in must also offer an
/// equivalent private option. Apple alone satisfies it, needs no extra SDK, and is the one
/// sign-in an iPhone user already has. The cost is docs/07 §13c.
@MainActor
final class AppleSignInRequest {
    private var nonce: SignInNonce?

    func prepare(_ request: ASAuthorizationAppleIDRequest) {
        let nonce = SignInNonce.generate()
        self.nonce = nonce
        // Deliberately not `.fullName` or `.email`: the app has no use for either, and
        // docs/09 §1 keeps what is not needed out of the app entirely.
        request.requestedScopes = []
        request.nonce = nonce.hashed
    }

    func credential(from result: Result<ASAuthorization, any Error>) -> CredentialResult {
        // Spent either way: reusing a nonce would defeat the replay protection it exists for.
        let raw = nonce?.raw
        nonce = nil

        switch result {
        case let .success(authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                  let tokenData = credential.identityToken,
                  let idToken = String(data: tokenData, encoding: .utf8),
                  let raw
            else {
                return .failed(reason: "no identity token")
            }
            return .credential(.apple(idToken: idToken, rawNonce: raw))

        case let .failure(error):
            if (error as? ASAuthorizationError)?.code == .canceled {
                return .cancelled
            }
            // The code only. An authorization error can carry an account identifier, and
            // docs/09 §11 keeps that out of the log.
            let code = (error as NSError).code
            AppLog.identity.info("apple sign-in failed: \(code, privacy: .public)")
            return .failed(reason: "apple(\(code))")
        }
    }
}

/// A nonce and the hash Apple is asked to sign, generated together.
///
/// One type rather than two strings because the pair is the part that goes wrong: the
/// request must carry `hashed` and the credential must carry `raw`, and swapping them fails
/// with an invalid-credential error that mentions no nonce at all.
struct SignInNonce: Sendable, Equatable {
    let raw: String
    let hashed: String

    static func generate() -> SignInNonce {
        let raw = randomNonce()
        return SignInNonce(raw: raw, hashed: sha256(raw))
    }

    static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Unreserved URL characters, so the value survives the round trip unchanged.
    private static func randomNonce(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._")
        var bytes = [UInt8](repeating: 0, count: length)
        _ = SecRandomCopyBytes(kSecRandomDefault, length, &bytes)
        return String(bytes.map { charset[Int($0) % charset.count] })
    }
}

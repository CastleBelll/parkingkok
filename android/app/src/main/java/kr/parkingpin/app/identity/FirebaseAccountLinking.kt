package kr.parkingpin.app.identity

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The one Firebase Auth call that attaches a provider to the account this device already has
 * (docs/07 §13a).
 *
 * **`linkWithCredential`, never `signInWithCredential`.** Linking keeps the uid and attaches
 * a provider to it; signing in mints a new uid and abandons the anonymous one together with
 * anything keyed to it. There is no server state to lose today, which is exactly why the
 * rule is enforced here rather than remembered later — the failure is invisible until it is
 * expensive.
 *
 * Thin by design, like [FirebaseAnonymousSignIn] beside it: the ordering, the mutex and the
 * uid-changed check live in [LinkingAccountIdentity], where a JVM test can reach them.
 */
class FirebaseAccountLinking(private val auth: FirebaseAuth) : AccountLinking {

    override fun currentState(): AccountState {
        val user = auth.currentUser ?: return AccountState.None
        val provider = user.providerData
            .firstNotNullOfOrNull { AccountProvider.of(it.providerId) }
            ?: return AccountState.Anonymous(user.uid)
        return AccountState.Linked(user.uid, provider)
    }

    override suspend fun link(provider: AccountProvider, credential: String): AccountLinkResult {
        val user = auth.currentUser ?: return AccountLinkResult.Failed("no current user")
        val previousUid = user.uid
        return try {
            val linked = user.linkWithCredential(provider.credential(credential)).await()
            val uid = linked.user?.uid ?: previousUid
            AccountLinkResult.Linked(uid = uid, previousUid = previousUid)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (collision: FirebaseAuthUserCollisionException) {
            // docs/07 §13b, and three situations rather than one. Refused rather than
            // switched in every case — the alternative abandons this device's uid silently,
            // and v1 has nothing on the server worth that — but a user told their account
            // is "in use on another device" when the real problem is their email goes
            // looking for a phone that has nothing to do with it.
            Log.i(TAG, "link refused: ${collision.errorCode}")
            when (collision.errorCode) {
                ERROR_PROVIDER_ALREADY_LINKED -> AccountLinkResult.AlreadyLinkedToThisAccount
                ERROR_EMAIL_ALREADY_IN_USE,
                ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
                -> AccountLinkResult.EmailBelongsToAnotherAccount
                else -> AccountLinkResult.AlreadyLinkedElsewhere
            }
        } catch (failure: Exception) {
            // Class name only — an auth message can carry a project or a token fragment,
            // and docs/09 §11 keeps those out of the log.
            Log.i(TAG, "link failed: ${failure.javaClass.simpleName}")
            AccountLinkResult.Failed(failure.javaClass.simpleName)
        }
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    private companion object {
        const val TAG = "PkAccount"

        // `FirebaseAuthUserCollisionException.errorCode`, which is a string rather than the
        // numeric code the iOS SDK reports. The two platforms answer the same three cases.
        const val ERROR_PROVIDER_ALREADY_LINKED = "ERROR_PROVIDER_ALREADY_LINKED"
        const val ERROR_EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
        const val ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL =
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"
    }
}

/**
 * The SDK's provider ids, mapped in exactly one place.
 *
 * Apple arrives as a generic OAuth credential rather than a dedicated provider class, which
 * is why the two branches do not look alike.
 */
internal fun AccountProvider.credential(token: String) = when (this) {
    AccountProvider.GOOGLE -> GoogleAuthProvider.getCredential(token, null)
    AccountProvider.APPLE -> OAuthProvider.newCredentialBuilder(APPLE_PROVIDER_ID)
        .setIdToken(token)
        .build()
}

internal fun AccountProvider.Companion.of(providerId: String): AccountProvider? = when (providerId) {
    GoogleAuthProvider.PROVIDER_ID -> AccountProvider.GOOGLE
    APPLE_PROVIDER_ID -> AccountProvider.APPLE
    else -> null
}

private const val APPLE_PROVIDER_ID = "apple.com"

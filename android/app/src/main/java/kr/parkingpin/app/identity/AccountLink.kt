package kr.parkingpin.app.identity

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which sign-in provider a credential came from.
 *
 * Deliberately not the SDK's provider-id strings: those are stable but they are the SDK's,
 * and this layer is the one a JVM test drives.
 */
enum class AccountProvider {
    GOOGLE,
    APPLE,
    ;

    companion object
}

/** What the app knows about who is signed in (docs/07 §13a). */
sealed interface AccountState {

    /** No Firebase identity yet. Ordinary — one is created lazily, on first backend use. */
    data object None : AccountState

    /** A technical identity with no provider attached. The default, and 회원가입 없음. */
    data class Anonymous(val uid: String) : AccountState

    /** The **same uid**, with a provider attached to it. That sameness is the whole point. */
    data class Linked(val uid: String, val provider: AccountProvider) : AccountState
}

/** What [AccountLinking.link] can answer. */
sealed interface AccountLinkResult {

    /** The provider is attached and the uid is unchanged from [previousUid]. */
    data class Linked(val uid: String, val previousUid: String) : AccountLinkResult

    /**
     * docs/07 §13b: the credential already belongs to another Firebase user.
     *
     * v1 refuses rather than switching. Switching abandons this device's anonymous uid and
     * everything keyed to it, silently and unrecoverably, and the app has no server state
     * yet that would make the trade worth it.
     */
    data object AlreadyLinkedElsewhere : AccountLinkResult

    /** Offline, cancelled at the provider sheet, anything else. Nothing changed. */
    data class Failed(val reason: String) : AccountLinkResult
}

/**
 * The Firebase-facing half, kept small enough that the policy above it is testable.
 *
 * Implementations **must** use `linkWithCredential` on the current user, never
 * `signInWithCredential` — docs/07 §13a. The difference is invisible today and expensive
 * the moment anything is keyed to the uid.
 */
interface AccountLinking {

    fun currentState(): AccountState

    /**
     * Attaches [credential] to the *current* user.
     *
     * @param credential the provider token, already obtained by the UI layer.
     */
    suspend fun link(provider: AccountProvider, credential: String): AccountLinkResult

    suspend fun signOut()
}

/**
 * Signs in anonymously first when there is no user yet, then links (docs/07 §13a).
 *
 * The order matters and is the reason this class exists. `linkWithCredential` needs a
 * current user; with none, the honest thing is to create the anonymous one *first* and link
 * to it, so the uid the user ends up with is the one this device would have had anyway. The
 * tempting shortcut — sign in with the provider directly when there is no anonymous user —
 * produces a different uid on a device that had never touched the backend, which is
 * harmless today and is the habit that breaks §13a later.
 *
 * The mutex is the same bargain [LazyAnonymousIdentity] makes: two taps on the sign-in
 * button must not race into two accounts.
 */
class LinkingAccountIdentity(
    private val anonymous: AnonymousIdentity,
    private val linking: AccountLinking,
) {

    private val mutex = Mutex()

    fun state(): AccountState = linking.currentState()

    suspend fun signIn(provider: AccountProvider, credential: String): AccountLinkResult =
        mutex.withLock {
            // Establishes the anonymous uid if this device has never needed one. Null means
            // offline or no Firebase — link would fail anyway, and saying so here is clearer
            // than letting the SDK phrase it.
            val existing = anonymous.uid()
                ?: return@withLock AccountLinkResult.Failed("no identity to link")
            when (val result = linking.link(provider, credential)) {
                is AccountLinkResult.Linked -> {
                    if (result.uid != existing) {
                        // Should be impossible with `linkWithCredential`, and worth shouting
                        // about if it ever happens: it means the implementation signed in
                        // instead of linking, and the previous account is now orphaned.
                        Log.e(TAG, "link changed the uid — docs/07 §13a says it must not")
                    }
                    result
                }
                else -> result
            }
        }

    suspend fun signOut() {
        mutex.withLock { linking.signOut() }
    }

    private companion object {
        const val TAG = "PkAccount"
    }
}

/** The account layer for a build with no Firebase project. */
object UnavailableAccountLinking : AccountLinking {

    override fun currentState(): AccountState = AccountState.None

    override suspend fun link(provider: AccountProvider, credential: String): AccountLinkResult =
        AccountLinkResult.Failed("Firebase is not configured in this build")

    override suspend fun signOut() = Unit
}

package kr.parkingpin.app.identity

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * The Firebase half of [AnonymousIdentity] — thin on purpose.
 *
 * Every decision (when to sign in, what a failure means, how concurrent callers are
 * serialised) lives in [LazyAnonymousIdentity], where a JVM test can reach it. What is left
 * here is the one call that genuinely needs a live `FirebaseAuth`.
 */
class FirebaseAnonymousSignIn(private val auth: FirebaseAuth) : AnonymousSignIn {

    /** Local read of the persisted session; the SDK restores it without a network call. */
    override fun currentUid(): String? = auth.currentUser?.uid

    override suspend fun signIn(): String =
        requireNotNull(auth.signInAnonymously().await().user?.uid) {
            "Firebase returned a successful anonymous sign-in with no user"
        }
}

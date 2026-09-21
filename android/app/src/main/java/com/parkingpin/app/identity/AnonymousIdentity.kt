package com.parkingpin.app.identity

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's technical identity (docs/07 §4, §13).
 *
 * The UI stays 회원가입 없음: there is no sign-in screen and never was. This exists only
 * because a backend call needs *some* caller, and it is created at the moment one is
 * actually made.
 *
 * **Lazy by construction, not by convention.** Nothing calls this during startup and no
 * screen awaits it, so a launch — or a home screen — cannot be delayed by it. The contract
 * is written as "returns null" rather than "throws" because every caller's honest answer to
 * a failed sign-in is to do without: docs/00 keeps parking records local, so there is no
 * screen that a missing uid should be allowed to break.
 */
interface AnonymousIdentity {

    /**
     * The current anonymous uid, signing in if there is not one yet.
     *
     * Null when Firebase is absent or the sign-in did not succeed — offline, for instance.
     * A caller treats that as "not now", never as a failure to report to the user.
     */
    suspend fun uid(): String?
}

/**
 * The single Firebase Auth operation this app performs, behind a seam a JVM test can drive.
 *
 * `FirebaseAuth` is final and needs a live `FirebaseApp`, so keeping the retry/caching
 * policy on this side of the boundary is what makes that policy testable at all
 * ([LazyAnonymousIdentity]). The Firebase-facing half stays small enough to read.
 */
interface AnonymousSignIn {

    /** The already-signed-in uid, or null. Local read — never touches the network. */
    fun currentUid(): String?

    /** Signs in anonymously and returns the new uid. Throws on any failure. */
    suspend fun signIn(): String
}

/**
 * Signs in at most once, on first use.
 *
 * The mutex is not premature: two features asking for a uid at the same moment would
 * otherwise start two sign-ins, and Firebase would answer with two different anonymous
 * accounts — the second silently replacing the first, taking any server state keyed to it
 * along. Serialising here costs one uncontended lock.
 */
class LazyAnonymousIdentity(private val signIn: AnonymousSignIn) : AnonymousIdentity {

    private val mutex = Mutex()

    override suspend fun uid(): String? {
        signIn.currentUid()?.let { return it }
        return mutex.withLock {
            // Re-checked inside the lock: whoever held it before us may have just signed in,
            // and a second sign-in would discard their account.
            signIn.currentUid() ?: trySignIn()
        }
    }

    private suspend fun trySignIn(): String? = try {
        signIn.signIn()
    } catch (cancellation: CancellationException) {
        // The caller went away. Not a failure, and swallowing it would break cancellation.
        throw cancellation
    } catch (failure: Exception) {
        // Offline is the common case and is not an error the user needs to see: docs/07
        // keeps every parking feature local, so nothing on screen depends on this.
        // The class name only — an auth message can carry a project or token fragment,
        // and docs/09 §11 keeps that out of the log.
        Log.i(TAG, "anonymous sign-in unavailable: ${failure.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "PkIdentity"
    }
}

/**
 * The identity for a build with no Firebase project.
 *
 * Returning null rather than a placeholder uid: a fake identity would be accepted by a
 * caller and then rejected by the backend, which is a worse failure than no identity.
 */
object UnavailableAnonymousIdentity : AnonymousIdentity {

    override suspend fun uid(): String? = null
}

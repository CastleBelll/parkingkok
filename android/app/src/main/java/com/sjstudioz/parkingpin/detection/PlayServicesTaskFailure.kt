package com.sjstudioz.parkingpin.detection

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.tasks.await

/**
 * Awaits a Play services subscription Task and maps a rejection to a short,
 * coordinate-free reason string, or null when it succeeded.
 *
 * **`SecurityException` is deliberately not caught here.** A revoked permission can throw
 * synchronously from the call that *starts* the Task, before this ever runs, so only the
 * call site can catch both — and it has to, visibly, for lint's MissingPermission check to
 * see that the revocation is handled. Coroutine cancellation is never swallowed.
 */
internal suspend fun Task<Void>.failureReason(): String? =
    try {
        await()
        null
    } catch (error: ApiException) {
        "ApiException statusCode=${error.statusCode}"
    }

/** The reason recorded when the OS refuses a call because the permission was revoked. */
internal const val PERMISSION_REVOKED_REASON = "SecurityException"

package com.sjstudioz.parkingpin.detection

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.sjstudioz.parkingpin.domain.registration.TransitionRegistrationSpec
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the Play services Activity Transition subscription.
 *
 * The PendingIntent is intentionally stable — same request code, same explicit component,
 * same action, `FLAG_UPDATE_CURRENT`. Play services keys subscriptions by PendingIntent
 * equality, so re-requesting replaces the previous subscription instead of stacking a
 * second one. That is the structural half of duplicate-registration prevention; the
 * bookkeeping half lives in RegistrationReconciler.
 *
 * `FLAG_MUTABLE` is required: Play services fills the transition result into the intent.
 */
class ActivityTransitionRegistrar(private val context: Context) : TransitionRegistrar {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun register(): String? {
        if (!hasPermission()) return "ACTIVITY_RECOGNITION permission not granted"
        val request = ActivityTransitionRequest(
            TransitionRegistrationSpec.subscriptions.map { subscription ->
                ActivityTransition.Builder()
                    .setActivityType(SdkTransitionCodec.toDetectedActivityType(subscription.activity))
                    .setActivityTransition(SdkTransitionCodec.toTransitionType(subscription.transition))
                    .build()
            },
        )
        return failureReasonOf {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pendingIntent())
        }
    }

    override suspend fun unregister(): String? =
        failureReasonOf {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent())
        }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
            .setAction(ActivityTransitionReceiver.ACTION_TRANSITION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * Runs a Play services Task and maps a rejection to a short, coordinate-free reason
     * string. Coroutine cancellation is never swallowed.
     */
    private suspend fun failureReasonOf(start: () -> Task<Void>): String? =
        try {
            suspendCancellableCoroutine { continuation ->
                start()
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
            null
        } catch (error: com.google.android.gms.common.api.ApiException) {
            "ApiException statusCode=${error.statusCode}"
        } catch (error: SecurityException) {
            "SecurityException"
        }

    private companion object {
        /** Fixed so the PendingIntent stays equal across process restarts and app updates. */
        const val REQUEST_CODE = 0xA1
    }
}

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
        if (!hasPermission()) return PERMISSION_MISSING_REASON
        val request = ActivityTransitionRequest(
            TransitionRegistrationSpec.subscriptions.map { subscription ->
                ActivityTransition.Builder()
                    .setActivityType(SdkTransitionCodec.toDetectedActivityType(subscription.activity))
                    .setActivityTransition(SdkTransitionCodec.toTransitionType(subscription.transition))
                    .build()
            },
        )
        return try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pendingIntent())
                .failureReason()
        } catch (revoked: SecurityException) {
            // Checked above, but it can be revoked in Settings between the check and the call.
            PERMISSION_REVOKED_REASON
        }
    }

    override suspend fun unregister(): String? {
        // Removal is gated by the same permission; without it the OS refuses the call anyway.
        if (!hasPermission()) return PERMISSION_MISSING_REASON
        return try {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent())
                .failureReason()
        } catch (revoked: SecurityException) {
            PERMISSION_REVOKED_REASON
        }
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

    private companion object {
        /** Fixed so the PendingIntent stays equal across process restarts and app updates. */
        const val REQUEST_CODE = 0xA1
        const val PERMISSION_MISSING_REASON = "ACTIVITY_RECOGNITION permission not granted"
    }
}

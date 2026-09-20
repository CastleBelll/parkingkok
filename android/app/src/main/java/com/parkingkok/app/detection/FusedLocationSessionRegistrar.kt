package com.parkingkok.app.detection

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import com.parkingkok.app.domain.location.LocationAccuracyTier
import com.parkingkok.app.domain.location.LocationSessionConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the bounded Fused Location subscription
 * (docs/04_ANDROID_IMPLEMENTATION.md §2 conceptual modes).
 *
 * **Why the PendingIntent form and not a `LocationCallback`.** A callback needs a live
 * process for the whole drive. Without a foreground service the app gets throttled to a
 * handful of background updates an hour and can be killed at any moment — so a callback
 * design would force the permanent FGS §4 rules out at P0. Handing Play services a
 * PendingIntent moves ownership of the subscription out of this process entirely: it
 * survives process death, wakes us per batch, and stops on an explicit removal or on its
 * own `durationMillis`.
 *
 * The PendingIntent is stable — fixed request code, explicit component, fixed action,
 * `FLAG_UPDATE_CURRENT` — because Play services keys subscriptions by PendingIntent
 * equality. Re-requesting therefore *replaces* the previous request rather than stacking a
 * second one, which is the structural half of "the session must not leak"; the bookkeeping
 * half is [com.parkingkok.app.domain.location.LocationSessionPlanner].
 *
 * `FLAG_MUTABLE` is required: Play services fills the location result into the intent.
 */
class FusedLocationSessionRegistrar(private val context: Context) : LocationSessionRegistrar {

    override fun hasForegroundLocationPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun hasBackgroundLocationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // Below Android 10 foreground permission already covers background delivery.
            hasForegroundLocationPermission()
        }

    override suspend fun request(config: LocationSessionConfig): String? {
        if (!hasForegroundLocationPermission()) return "location permission not granted"
        // Raised *before* the request, so the process is already foreground when Play
        // services decides what rate this subscription is entitled to. See
        // `DrivingLocationService` for the measurement that put it here — a PendingIntent
        // survives process death but is still throttled to nothing without this.
        DrivingLocationService.start(context)
        return failureReasonOf {
            LocationServices.getFusedLocationProviderClient(context)
                .requestLocationUpdates(config.toLocationRequest(), pendingIntent())
        }.also { failure -> if (failure != null) DrivingLocationService.stop(context) }
    }

    override suspend fun remove(): String? {
        // Lowered first and unconditionally. A removal that fails still must not leave the
        // service standing: its whole justification is a live capture, and CLAUDE.md's rule
        // is about a service that outlives one.
        DrivingLocationService.stop(context)
        return failureReasonOf {
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(pendingIntent())
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, LocationUpdateReceiver::class.java)
            .setAction(LocationUpdateReceiver.ACTION_LOCATION_UPDATE)
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
        } catch (error: ApiException) {
            "ApiException statusCode=${error.statusCode}"
        } catch (error: SecurityException) {
            "SecurityException"
        }

    private companion object {
        /** Fixed so the PendingIntent stays equal across process restarts and app updates. */
        const val REQUEST_CODE = 0xA2
    }
}

/**
 * The single place domain request shapes become Play services ones.
 *
 * `setDurationMillis` is the load-bearing call: Play services expires the request on its
 * own when it elapses, so a session cannot outlive its budget even if this process is
 * never scheduled again.
 */
internal fun LocationSessionConfig.toLocationRequest(): LocationRequest {
    val priority = when (tier) {
        LocationAccuracyTier.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationAccuracyTier.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
    }
    return LocationRequest.Builder(priority, intervalMillis)
        .setMinUpdateIntervalMillis(minUpdateIntervalMillis)
        .setMaxUpdateDelayMillis(maxUpdateDelayMillis)
        .setDurationMillis(durationMillis)
        .apply { maxUpdates?.let { setMaxUpdates(it) } }
        .setWaitForAccurateLocation(false)
        .build()
}

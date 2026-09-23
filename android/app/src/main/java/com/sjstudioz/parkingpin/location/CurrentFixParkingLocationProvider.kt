package com.sjstudioz.parkingpin.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingLocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Asks the OS for a fix, because the user just pressed 주차 위치 저장 (FR-001).
 *
 * **The old behaviour was to read the detection checkpoint and nothing else**, which meant a
 * button named "save parking location" saved no location at all on a phone that had not
 * driven with detection on — a fresh install, detection switched on this morning, a trip
 * taken before the opt-in. Reported from the device: the record saved with the floor and the
 * memo and no coordinate.
 *
 * The reasoning behind the old shape conflated two rules. FR-001's "위치 권한 없이도 저장
 * 가능" means the save must survive a refusal; it does not mean the app may never ask. And
 * pressing this button is the clearest location request a user can make.
 *
 * **The record is written before this is asked.** Waiting for a fix inside the save made the
 * button look broken — up to eight silent seconds with nothing on screen but a disabled
 * button, reported from the device as "저장 눌러도 반응은 없는데 저장은 되고". So the save
 * takes [lastReliableLocation], which is the checkpoint and returns at once, and the caller
 * attaches [currentFix] to the record afterwards.
 *
 * What each answer is worth:
 * * **A fix now** — the car is here, at this moment. Nothing else can say that.
 * * **The checkpoint** ([CheckpointParkingLocationProvider]) — underground, where a one-shot
 *   fix will not come, the drive that just ended is the better answer anyway.
 * * **Nothing**, and the record keeps no coordinates. FR-001 intact.
 */
class CurrentFixParkingLocationProvider(
    private val context: Context,
    private val fallback: ParkingLocationProvider,
) : ParkingLocationProvider {

    override suspend fun lastReliableLocation(): ParkingLocation? = fallback.lastReliableLocation()

    override suspend fun currentFix(): ParkingLocation? {
        if (!hasForegroundLocationPermission()) return null
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                // A fix from the last half minute is this parking; older than that and the
                // phone may have been somewhere else when it was taken.
                .setMaxUpdateAgeMillis(MAX_FIX_AGE_MILLIS)
                // Bounds the wait, not the success: the save proceeds without a location.
                .setDurationMillis(TIMEOUT_MILLIS)
                .build()
            val location = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(request, null)
                .await()
                ?: return null
            if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) return null
            ParkingLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracyM = location.accuracy,
                capturedAtMillis = location.time,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Class name only — a location failure message has carried provider and account
            // detail before, and docs/09 §11 keeps that out of the log. Never a coordinate.
            Log.i(TAG, "current fix unavailable: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun hasForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "PkParkingLocation"
        const val TIMEOUT_MILLIS = 8_000L
        const val MAX_FIX_AGE_MILLIS = 30_000L

        /**
         * A save is not a drive: the engine's 35 m gate (docs/05 §6) exists to stop a poor
         * sample becoming `lastReliableLocation` mid-drive, where a better one is a second
         * away. Here there is no second fix coming, and a 60 m pin still answers "which
         * building". Past this it answers nothing, and FR-008 forbids presenting it as where
         * the car is.
         */
        const val MAX_ACCURACY_METERS = 100f
    }
}

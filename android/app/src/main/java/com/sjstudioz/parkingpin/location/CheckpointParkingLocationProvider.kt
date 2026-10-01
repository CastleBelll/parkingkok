package com.sjstudioz.parkingpin.location

import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingLocationProvider

/**
 * Serves the detection engine's `lastReliableLocation` to the parking feature.
 *
 * Nothing here asks the OS for a fix. The checkpoint only ever holds a location the
 * detection engine already accepted, so a manual save reuses work that has been done
 * rather than starting a location request the user never asked for — and when the engine
 * has nothing, because permission was refused or detection is switched off, this returns
 * null and FR-001's permission-free save proceeds without coordinates.
 */
class CheckpointParkingLocationProvider(
    private val stateStore: DetectionStateStore,
    private val clock: Clock,
) : ParkingLocationProvider {

    override suspend fun lastReliableLocation(): ParkingLocation? {
        val reliable = stateStore.readCheckpointOnce()?.lastReliableLocation ?: return null
        // Only a fix from the last few minutes says where the car is now; an older one is
        // where it was (audit 2026-10-01). No coordinate beats a wrong one.
        val age = clock.nowEpochMillis() - reliable.capturedAtMillis
        if (age > ParkingLocationProvider.MAX_SAVED_LOCATION_AGE_MILLIS) return null
        return ParkingLocation(
            latitude = reliable.latitude,
            longitude = reliable.longitude,
            horizontalAccuracyM = reliable.horizontalAccuracyM,
            capturedAtMillis = reliable.capturedAtMillis,
        )
    }
}

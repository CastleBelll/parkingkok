package kr.parkingpin.app.location

import kr.parkingpin.app.data.DetectionStateStore
import kr.parkingpin.app.domain.parking.ParkingLocation
import kr.parkingpin.app.domain.parking.ParkingLocationProvider

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
) : ParkingLocationProvider {

    override suspend fun lastReliableLocation(): ParkingLocation? {
        val reliable = stateStore.readCheckpointOnce()?.lastReliableLocation ?: return null
        return ParkingLocation(
            latitude = reliable.latitude,
            longitude = reliable.longitude,
            horizontalAccuracyM = reliable.horizontalAccuracyM,
            capturedAtMillis = reliable.capturedAtMillis,
        )
    }
}

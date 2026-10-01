package com.sjstudioz.parkingpin.location

import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.detection.DetectionCheckpoint
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.ReliableLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingLocationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Audit 2026-10-01: a manual save took a days-old fix from somewhere else. */
class CheckpointParkingLocationProviderTest {

    private val now = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = now)
    private val store = DetectionStateStore(InMemoryPreferencesDataStore())
    private val provider = CheckpointParkingLocationProvider(store, clock)

    private suspend fun storeFix(capturedAtMillis: Long) {
        store.writeCheckpoint(
            DetectionCheckpoint(
                state = DetectionState.PARKED,
                stateEnteredAtMillis = capturedAtMillis,
                lastReliableLocation = ReliableLocation(37.5, 127.0, 8f, capturedAtMillis),
            ),
        )
    }

    @Test
    fun `a fix from a few minutes ago is where the car is`() = runTest {
        storeFix(capturedAtMillis = now - 3 * 60_000L)

        assertEquals(now - 3 * 60_000L, provider.lastReliableLocation()?.capturedAtMillis)
    }

    @Test
    fun `last week's fix is not offered for today's parking`() = runTest {
        storeFix(capturedAtMillis = now - 7 * 24 * 3_600_000L)

        assertNull(provider.lastReliableLocation())
    }

    @Test
    fun `the bound is the shared maximum age`() = runTest {
        storeFix(capturedAtMillis = now - ParkingLocationProvider.MAX_SAVED_LOCATION_AGE_MILLIS - 1)

        assertNull(provider.lastReliableLocation())
    }
}

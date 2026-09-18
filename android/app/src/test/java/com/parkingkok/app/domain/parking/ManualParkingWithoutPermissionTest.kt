package com.parkingkok.app.domain.parking

import com.parkingkok.app.data.parking.ParkingDatabase
import com.parkingkok.app.data.parking.RoomParkingRepository
import com.parkingkok.app.data.parking.createTestParkingDatabase
import com.parkingkok.app.detection.MutableTestClock
import com.parkingkok.app.domain.parking.usecase.ManualParkingInput
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingResult
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FR-001, the promise CLAUDE.md states as "Manual parking은 항상 가능해야 한다".
 *
 * Every permission the app can ask for is refused here, and a save must still write a
 * record that reads back. The three ways a refusal reaches this layer are covered
 * separately because they fail differently: a provider that returns null (permission never
 * granted), one that throws `SecurityException` (permission revoked while the app ran),
 * and one that is absent entirely (detection switched off).
 *
 * Notification and motion permission need no stand-in: this path never calls them, which
 * is itself the guarantee — a save cannot be blocked by a subsystem it does not touch.
 */
class ManualParkingWithoutPermissionTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository

    private val clock = MutableTestClock()

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun useCase(provider: ParkingLocationProvider) = SaveManualParkingUseCase(
        repository = repository,
        locationProvider = provider,
        clock = clock,
        idGenerator = { "record-1" },
    )

    /** Location permission was never granted, so the engine has no fix to offer. */
    private val permissionNeverGranted = ParkingLocationProvider { null }

    /** Permission was revoked in Settings; the platform answers with a SecurityException. */
    private val permissionRevoked = ParkingLocationProvider {
        throw SecurityException("android.permission.ACCESS_FINE_LOCATION denied")
    }

    @Test
    fun `saves and reads back with no location permission`() = runTest {
        val result = useCase(permissionNeverGranted)(
            ManualParkingInput(floorRaw = "B3", zone = "A구역", spot = "142", memo = "기둥 옆"),
        )

        assertTrue(result is SaveManualParkingResult.Saved)

        val stored = repository.observeActive().first()
        assertNotNull(stored)
        assertEquals("B3", stored?.floor?.displayLabel)
        assertEquals("A구역", stored?.zone)
        assertEquals("142", stored?.spot)
        assertEquals("기둥 옆", stored?.memo)
        assertEquals(ParkingSource.MANUAL, stored?.source)
        assertTrue(stored?.isActive ?: false)
    }

    @Test
    fun `the saved record simply has no coordinates`() = runTest {
        useCase(permissionNeverGranted)(ManualParkingInput(floorRaw = "B3"))

        assertNull(repository.observeActive().first()?.location)
    }

    @Test
    fun `a revoked permission costs the coordinates, not the record`() = runTest {
        val result = useCase(permissionRevoked)(ManualParkingInput(floorRaw = "B2"))

        assertTrue(result is SaveManualParkingResult.Saved)
        val stored = repository.observeActive().first()
        assertEquals("B2", stored?.floor?.displayLabel)
        assertNull(stored?.location)
    }

    @Test
    fun `an empty form still starts a parking session`() = runTest {
        // Nothing typed and nothing granted: the user tapped save and walked away. The
        // start time alone is worth keeping.
        val result = useCase(permissionNeverGranted)(ManualParkingInput())

        assertTrue(result is SaveManualParkingResult.Saved)
        val stored = repository.observeActive().first()
        assertNotNull(stored)
        assertNull(stored?.floor)
        assertNull(stored?.zone)
        assertEquals(clock.nowEpochMillis(), stored?.startedAtMillis)
    }

    @Test
    fun `a granted permission attaches the location it can vouch for`() = runTest {
        val provider = ParkingLocationProvider {
            ParkingLocation(37.1234, 127.5678, horizontalAccuracyM = 18f, capturedAtMillis = 1_699_999_999_000L)
        }

        useCase(provider)(ManualParkingInput(floorRaw = "B3"))

        val location = repository.observeActive().first()?.location
        assertEquals(37.1234, location?.latitude ?: 0.0, 0.000_001)
        assertEquals(18f, location?.horizontalAccuracyM)
    }
}

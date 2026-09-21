package kr.parkingpin.app.map

import android.content.ActivityNotFoundException
import kr.parkingpin.app.domain.parking.ParkingLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `길찾기`, including the two ways it does not happen: a record with no coordinate, and a
 * device with no maps app. Neither is an app failure (CLAUDE.md).
 */
class ExternalMapOpenerTest {

    private var started: String? = null

    private val location = ParkingLocation(
        latitude = 37.566_535,
        longitude = 126.977_969,
        horizontalAccuracyM = 18f,
        capturedAtMillis = 1_700_000_000_000L,
    )

    private fun opener(starter: MapUriStarter) = ExternalMapOpener(starter, pinLabel = "주차 위치")

    private val recording = MapUriStarter { started = it }

    @Test
    fun `hands the stored coordinate to the maps app`() {
        val result = opener(recording).openDirections(location)

        assertEquals(MapOpenResult.OPENED, result)
        assertTrue(started!!.startsWith("geo:37.5665350,126.9779690?q=37.5665350,126.9779690"))
    }

    @Test
    fun `a record with no coordinate starts nothing`() {
        val result = opener(recording).openDirections(null)

        assertEquals(MapOpenResult.NO_LOCATION, result)
        assertNull(started)
    }

    /** The whole point of catching rather than pre-checking: the app must not crash. */
    @Test
    fun `no installed maps app is reported, not thrown`() {
        val result = opener { throw ActivityNotFoundException() }.openDirections(location)

        assertEquals(MapOpenResult.NO_MAPS_APP, result)
    }

    @Test
    fun `a corrupt stored coordinate is treated as no location`() {
        val corrupt = location.copy(latitude = 1_000.0)

        val result = opener(recording).openDirections(corrupt)

        assertEquals(MapOpenResult.NO_LOCATION, result)
        assertNull(started)
    }
}

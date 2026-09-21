package com.parkingpin.app.domain.map

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-008 on Android is an external maps intent, so "the map works" reduces to "the URI
 * carries the right point". These are the ways it could silently carry the wrong one.
 */
class ParkingMapLinkTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `carries the coordinate as the pin and as the query`() {
        val uri = ParkingMapLink.directionsUri(37.566_535, 126.977_969, "주차 위치")

        assertEquals(
            "geo:37.5665350,126.9779690?q=37.5665350,126.9779690(%EC%A3%BC%EC%B0%A8%20%EC%9C%84%EC%B9%98)",
            uri,
        )
    }

    @Test
    fun `keeps the sign of southern and western coordinates`() {
        val uri = ParkingMapLink.directionsUri(-33.868_820, -151.209_290, "P")

        assertTrue(uri!!.contains("-33.8688200,-151.2092900"))
    }

    /**
     * A decimal comma would send the maps app to a different continent, and
     * `String.format` takes the default locale unless told otherwise.
     */
    @Test
    fun `formats coordinates the same under a comma-decimal locale`() {
        Locale.setDefault(Locale.GERMANY)

        val uri = ParkingMapLink.directionsUri(37.5, 127.0, "P")

        assertTrue(uri!!.contains("37.5000000,127.0000000"))
    }

    @Test
    fun `refuses a point that is not on Earth`() {
        assertNull(ParkingMapLink.directionsUri(91.0, 127.0, "P"))
        assertNull(ParkingMapLink.directionsUri(37.5, 181.0, "P"))
        assertNull(ParkingMapLink.directionsUri(Double.NaN, 127.0, "P"))
        assertNull(ParkingMapLink.directionsUri(37.5, Double.POSITIVE_INFINITY, "P"))
    }

    @Test
    fun `the exact antipodal bounds are still points`() {
        assertTrue(ParkingMapLink.directionsUri(-90.0, -180.0, "P")!!.startsWith("geo:"))
        assertTrue(ParkingMapLink.directionsUri(90.0, 180.0, "P")!!.startsWith("geo:"))
    }

    @Test
    fun `an empty label leaves the pin unlabelled rather than emitting empty parentheses`() {
        val uri = ParkingMapLink.directionsUri(37.5, 127.0, "   ")

        assertEquals("geo:37.5000000,127.0000000?q=37.5000000,127.0000000", uri)
    }

    @Test
    fun `a label cannot break out of the parentheses or grow without bound`() {
        val uri = ParkingMapLink.directionsUri(37.5, 127.0, "a)(b ".repeat(20))

        val label = uri!!.substringAfter("(").substringBeforeLast(")")
        assertTrue(label.length <= ParkingMapLink.MAX_LABEL_LENGTH * ENCODED_EXPANSION_LIMIT)
        assertTrue(!label.contains("(") && !label.contains(")"))
    }

    /** Percent-encoding turns one character into at most `%XX` per UTF-8 byte. */
    private companion object {
        const val ENCODED_EXPANSION_LIMIT = 9
    }
}

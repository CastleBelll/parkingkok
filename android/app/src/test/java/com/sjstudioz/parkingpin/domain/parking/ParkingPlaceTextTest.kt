package com.sjstudioz.parkingpin.domain.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The zone/spot line the home hero, the detail screen and the departure question share.
 * Mirrors iOS `ParkingSession.placeText(zone:spot:)` string for string.
 */
class ParkingPlaceTextTest {

    @Test
    fun `zone and spot are joined with a middle dot`() {
        // Arrange / Act
        val text = ParkingPlaceText.zoneAndSpot(zone = "A구역", spot = "142")

        // Assert
        assertEquals("A구역 · 142", text)
    }

    @Test
    fun `a zone alone is shown as written`() {
        assertEquals("A구역", ParkingPlaceText.zoneAndSpot(zone = "A구역", spot = null))
    }

    @Test
    fun `a spot alone takes the word 번`() {
        assertEquals("142번", ParkingPlaceText.zoneAndSpot(zone = null, spot = "142"))
    }

    @Test
    fun `a spot that already ends in 번 is not given a second one`() {
        assertEquals("01번", ParkingPlaceText.zoneAndSpot(zone = null, spot = "01번"))
    }

    @Test
    fun `a spot beside a zone keeps its bare number`() {
        assertEquals("A구역 · 03", ParkingPlaceText.zoneAndSpot(zone = "A구역", spot = "03"))
    }

    @Test
    fun `neither is no line at all`() {
        assertNull(ParkingPlaceText.zoneAndSpot(zone = null, spot = null))
    }
}

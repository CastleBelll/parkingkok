package com.sjstudioz.parkingpin.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/04_ANDROID §12: a build with no Maps key must draw the block plan, never the grey
 * box the SDK draws for a key it cannot use.
 */
class MapsKeyTest {

    @Test
    fun `a key injected at build time is usable`() {
        // Arrange
        val injected = "AIzaSyExampleExampleExampleExample0000"

        // Act
        val usable = MapsKey.isUsable(injected)

        // Assert
        assertTrue(usable)
    }

    @Test
    fun `a build without PK_MAPS_API_KEY has no key`() {
        assertFalse(MapsKey.isUsable(""))
        assertFalse(MapsKey.isUsable("   "))
        assertFalse(MapsKey.isUsable(null))
    }

    @Test
    fun `a placeholder that was never expanded is not a key`() {
        assertFalse(MapsKey.isUsable("\${mapsApiKey}"))
    }

    @Test
    fun `a map point never prints its coordinate`() {
        // Arrange
        val point = MapPoint(latitude = 37.5, longitude = 126.9)

        // Act
        val printed = "$point"

        // Assert
        assertEquals("MapPoint(redacted)", printed)
    }
}

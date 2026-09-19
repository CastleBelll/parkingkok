package com.parkingkok.app.domain.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/05_PARKING_DETECTION_ENGINE.md §3a "Platform reality": the two Bluetooth device
 * classes that mean *car*, and nothing else.
 */
class CarLinkPolicyTest {

    @Test
    fun `car audio and handsfree are the car`() {
        assertTrue(CarLinkPolicy.isCarAudioDevice(CarLinkPolicy.DEVICE_CLASS_AUDIO_VIDEO_CAR_AUDIO))
        assertTrue(CarLinkPolicy.isCarAudioDevice(CarLinkPolicy.DEVICE_CLASS_AUDIO_VIDEO_HANDSFREE))
    }

    @Test
    fun `everything else a phone pairs with is not`() {
        // Headphones, a portable speaker, a watch, a keyboard. Treating any of these as a
        // car would open a driving session on the walk to the bus stop.
        listOf(0x0404, 0x041C, 0x0704, 0x0540, 0x0000).forEach { deviceClass ->
            assertFalse("0x%04X".format(deviceClass), CarLinkPolicy.isCarAudioDevice(deviceClass))
        }
    }

    @Test
    fun `an unreadable device class is not a car`() {
        // The ordinary shape of a denied BLUETOOTH_CONNECT on API 31+. §3a makes the link
        // optional, so refusing to guess costs accuracy and never correctness.
        assertFalse(CarLinkPolicy.isCarAudioDevice(null))
    }
}

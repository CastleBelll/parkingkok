package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.domain.parking.FloorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/02 §5: the floor typed into the notification's inline `층 입력`. */
class ParkingCandidateReceiverFloorTest {

    @Test
    fun `a typed floor is parsed the way the confirmation screen parses it`() {
        // Act
        val floor = ParkingCandidateReceiver.floorAnswer(" B3 ").floor

        // Assert
        assertEquals("B3", floor?.raw)
        assertEquals(FloorKind.BASEMENT, floor?.kind)
        assertEquals(3, floor?.number)
    }

    @Test
    fun `an empty answer still confirms, with no floor`() {
        // "Yes, I parked" with nothing typed: FR-006 makes the floor optional (iOS parity).
        assertNull(ParkingCandidateReceiver.floorAnswer("").floor)
        assertNull(ParkingCandidateReceiver.floorAnswer("   ").floor)
    }

    @Test
    fun `free text survives as typed`() {
        val floor = ParkingCandidateReceiver.floorAnswer("주차타워 2").floor

        assertEquals("주차타워 2", floor?.raw)
        assertEquals(FloorKind.FREE_TEXT, floor?.kind)
    }

    @Test
    fun `a pasted paragraph is capped at 40 characters`() {
        val floor = ParkingCandidateReceiver.floorAnswer("가".repeat(100)).floor

        assertEquals(40, floor?.raw?.length)
    }
}

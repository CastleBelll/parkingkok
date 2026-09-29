package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The departure question's copy (docs/05 §11a): a guess, then a question about this parking. */
class ParkingEndProposalNoticeTest {

    @Test
    fun `the title states a guess, never a departure`() {
        assertEquals("출발한 것 같아요", ParkingEndProposalNotice.TITLE)
        assertFalse(ParkingEndProposalNotice.TITLE.contains("출발했어요"))
    }

    @Test
    fun `the body names the floor zone and spot the record holds`() {
        // Arrange
        val record = record(floor = "B3", zone = "A구역", spot = "142")

        // Act
        val body = ParkingEndProposalNotice.body(record)

        // Assert
        assertEquals("B3 · A구역 · 142 주차를 종료할까요?", body)
    }

    @Test
    fun `the body leaves out what is missing`() {
        assertEquals("B3 · A구역 주차를 종료할까요?", ParkingEndProposalNotice.body(record(floor = "B3", zone = "A구역")))
        assertEquals("A구역 · 142 주차를 종료할까요?", ParkingEndProposalNotice.body(record(zone = "A구역", spot = "142")))
    }

    // The spot-only rule is the home hero's (ParkingPlaceText), and the strings below are
    // the ones iOS ParkingEndProposalCopy pins: the same record reads the same on both.

    @Test
    fun `a floor and a spot with no zone give the spot its 번`() {
        // Arrange
        val record = record(floor = "B3", spot = "142")

        // Act
        val body = ParkingEndProposalNotice.body(record)

        // Assert
        assertEquals("B3 · 142번 주차를 종료할까요?", body)
    }

    @Test
    fun `a spot alone gives the spot its 번`() {
        assertEquals("142번 주차를 종료할까요?", ParkingEndProposalNotice.body(record(spot = "142")))
    }

    @Test
    fun `a spot that already ends in 번 is not given a second one`() {
        assertEquals("B3 · 142번 주차를 종료할까요?", ParkingEndProposalNotice.body(record(floor = "B3", spot = "142번")))
    }

    @Test
    fun `a record with no place asks the bare question`() {
        assertEquals("주차를 종료할까요?", ParkingEndProposalNotice.body(record()))
        assertEquals("주차를 종료할까요?", ParkingEndProposalNotice.body(null))
    }

    /**
     * The same eight rows iOS `ParkingEndProposalCopyParityTests` pins, in the same order, so
     * the two platforms cannot drift on the question's wording (docs/05 §11a).
     */
    @Test
    fun `the body names the place the way the home hero does`() {
        // Arrange
        val cases = listOf(
            Triple(record(floor = "B3", zone = "A구역", spot = "142"), "B3 · A구역 · 142 주차를 종료할까요?", "all three"),
            Triple(record(floor = "B3", zone = "A구역"), "B3 · A구역 주차를 종료할까요?", "floor and zone"),
            Triple(record(floor = "B3", spot = "142"), "B3 · 142번 주차를 종료할까요?", "floor and spot"),
            Triple(record(spot = "142"), "142번 주차를 종료할까요?", "spot alone"),
            Triple(record(floor = "B3", spot = "01번"), "B3 · 01번 주차를 종료할까요?", "spot already ends in 번"),
            Triple(record(zone = "A구역", spot = "142"), "A구역 · 142 주차를 종료할까요?", "zone and spot"),
            Triple(record(floor = "B3"), "B3 주차를 종료할까요?", "floor alone"),
            Triple(record(), "주차를 종료할까요?", "nothing"),
        )

        for ((record, expected, name) in cases) {
            // Act
            val body = ParkingEndProposalNotice.body(record)

            // Assert
            assertEquals(name, expected, body)
        }
    }

    @Test
    fun `the two answers are 주차 종료 and 아직 주차 중`() {
        assertEquals("주차 종료", ParkingEndProposalNotice.ACTION_END)
        assertEquals("아직 주차 중", ParkingEndProposalNotice.ACTION_KEEP)
    }

    private fun record(floor: String? = null, zone: String? = null, spot: String? = null) = ParkingRecord(
        id = "r1",
        startedAtMillis = 1_000L,
        endedAtMillis = null,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = null,
        floor = floor?.let(FloorParser::parse),
        zone = zone,
        spot = spot,
        memo = null,
        photoRelativePath = null,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        revision = 1,
    )
}

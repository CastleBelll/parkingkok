package com.sjstudioz.parkingpin.domain.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `위치 보내기` (docs/01 §5a). The same rows iOS `ParkingShareTextTests` pins, so a family
 * member gets the same message from either phone.
 */
class ParkingShareTextTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val parkedAt = ZonedDateTime.of(2026, 10, 1, 14, 35, 0, 0, seoul).toInstant().toEpochMilli()

    @Test
    fun `a full record sends its place, its time and a map link`() {
        // Arrange
        val record = record(floor = "B3", zone = "A구역", spot = "142", location = location())

        // Act
        val text = ParkingShareText.text(record, seoul)

        // Assert
        assertEquals(
            "B3 · A구역 · 142에 주차했어요\n" +
                "10월 1일 오후 2:35\n" +
                "마지막으로 확인된 위치: https://www.google.com/maps/search/?api=1&query=37.49790,127.02760",
            text,
        )
    }

    @Test
    fun `a record with no location sends no map line`() {
        val text = ParkingShareText.text(record(floor = "B3"), seoul)

        assertEquals("B3에 주차했어요\n10월 1일 오후 2:35", text)
    }

    @Test
    fun `a record with no place still says when`() {
        val text = ParkingShareText.text(record(), seoul)

        assertEquals("주차했어요\n10월 1일 오후 2:35", text)
    }

    @Test
    fun `a spot alone takes 번, as on the home screen`() {
        val text = ParkingShareText.text(record(floor = "B3", spot = "142"), seoul)

        assertEquals("B3 · 142번에 주차했어요\n10월 1일 오후 2:35", text)
    }

    @Test
    fun `a morning time reads 오전`() {
        val morning = ZonedDateTime.of(2026, 10, 1, 9, 5, 0, 0, seoul).toInstant().toEpochMilli()

        val text = ParkingShareText.text(record(startedAtMillis = morning), seoul)

        assertEquals("주차했어요\n10월 1일 오전 9:05", text)
    }

    @Test
    fun `the memo never leaves the phone`() {
        val text = ParkingShareText.text(record(floor = "B3", memo = "트렁크에 우산"), seoul)

        assertFalse("트렁크" in text)
    }

    @Test
    fun `the coordinate is written with a dot whatever the device locale`() {
        // A comma-decimal locale would otherwise break the URL.
        val url = ParkingShareText.mapUrl(ParkingLocation(-33.8688, 151.2093, 10f, 0L))

        assertEquals("https://www.google.com/maps/search/?api=1&query=-33.86880,151.20930", url)
    }

    private fun location() = ParkingLocation(
        latitude = 37.4979,
        longitude = 127.0276,
        horizontalAccuracyM = 12f,
        capturedAtMillis = parkedAt,
    )

    private fun record(
        floor: String? = null,
        zone: String? = null,
        spot: String? = null,
        memo: String? = null,
        location: ParkingLocation? = null,
        startedAtMillis: Long = parkedAt,
    ) = ParkingRecord(
        id = "r1",
        startedAtMillis = startedAtMillis,
        endedAtMillis = null,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = location,
        floor = floor?.let(FloorParser::parse),
        zone = zone,
        spot = spot,
        memo = memo,
        photoRelativePath = null,
        createdAtMillis = startedAtMillis,
        updatedAtMillis = startedAtMillis,
        revision = 1,
    )
}

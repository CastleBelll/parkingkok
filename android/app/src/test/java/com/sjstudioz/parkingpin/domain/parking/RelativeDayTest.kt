package com.sjstudioz.parkingpin.domain.parking

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RelativeDayTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, seoul).toInstant().toEpochMilli()

    @Test
    fun `the same calendar day is today`() {
        val now = at(2026, 9, 18, 14, 0)

        assertEquals(RelativeDay.TODAY, RelativeDayCalculator.of(at(2026, 9, 18, 0, 1), now, seoul))
        assertEquals(RelativeDay.TODAY, RelativeDayCalculator.of(now, now, seoul))
    }

    @Test
    fun `twenty minutes across midnight is yesterday, not today`() {
        // The label is about the calendar, not about hours elapsed.
        val now = at(2026, 9, 18, 0, 10)

        assertEquals(
            RelativeDay.YESTERDAY,
            RelativeDayCalculator.of(at(2026, 9, 17, 23, 50), now, seoul),
        )
    }

    @Test
    fun `anything older gets a date`() {
        val now = at(2026, 9, 18, 14, 0)

        assertEquals(RelativeDay.OLDER, RelativeDayCalculator.of(at(2026, 9, 16, 23, 59), now, seoul))
    }

    @Test
    fun `the answer follows the time zone`() {
        // 2026-09-18 00:30 in Seoul is still 2026-09-17 in UTC.
        val parked = at(2026, 9, 18, 0, 30)
        val now = at(2026, 9, 18, 9, 0)

        assertEquals(RelativeDay.TODAY, RelativeDayCalculator.of(parked, now, seoul))
        assertEquals(
            RelativeDay.YESTERDAY,
            RelativeDayCalculator.of(parked, now, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `a record stamped in the future is still today`() {
        // A clock correction can put a record slightly ahead of now; it must not read as OLDER.
        val now = at(2026, 9, 18, 14, 0)

        assertEquals(RelativeDay.TODAY, RelativeDayCalculator.of(at(2026, 9, 18, 15, 0), now, seoul))
    }
}

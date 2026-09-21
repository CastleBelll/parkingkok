package com.parkingpin.app.domain.parking

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTimeTest {

    private val start = 1_700_000_000_000L

    private fun minutes(count: Long) = count * 60_000L

    @Test
    fun `splits into hours and minutes`() {
        val elapsed = ElapsedTime.since(start, start + minutes(84))

        assertEquals(84, elapsed.totalMinutes)
        assertEquals(0, elapsed.days)
        assertEquals(1, elapsed.hours)
        assertEquals(24, elapsed.minutes)
    }

    @Test
    fun `floors to the minute`() {
        val elapsed = ElapsedTime.since(start, start + minutes(3) + 59_999L)

        assertEquals(3, elapsed.totalMinutes)
    }

    @Test
    fun `is zero at the moment of parking`() {
        val elapsed = ElapsedTime.since(start, start)

        assertEquals(0, elapsed.totalMinutes)
        assertEquals(0, elapsed.hours)
        assertEquals(0, elapsed.minutes)
    }

    @Test
    fun `exactly one hour reports zero minutes, not sixty`() {
        val elapsed = ElapsedTime.since(start, start + minutes(60))

        assertEquals(1, elapsed.hours)
        assertEquals(0, elapsed.minutes)
    }

    @Test
    fun `rolls into days past twenty four hours`() {
        val elapsed = ElapsedTime.since(start, start + minutes(60 * 26 + 5))

        assertEquals(1, elapsed.days)
        assertEquals(2, elapsed.hours)
        assertEquals(5, elapsed.minutes)
    }

    @Test
    fun `a clock that moved backwards reads zero, never negative`() {
        // An NTP correction or a restored backup can put `now` before `startedAt`.
        val elapsed = ElapsedTime.since(start, start - minutes(180))

        assertEquals(0, elapsed.totalMinutes)
        assertEquals(0, elapsed.days)
        assertEquals(0, elapsed.hours)
        assertEquals(0, elapsed.minutes)
    }
}

package com.parkingkok.app.domain.trace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The §9 gap measurement, at the edges that decide whether the 30-minute threshold ever
 * moves.
 *
 * These numbers exist to retire a guess: the idle gap was set from a single day, and that
 * day both nearly cut a real subway ride in two and failed by 66 seconds to split an
 * office wait off a commute. Getting the counting wrong here would put the eventual retune
 * on a distribution that never happened.
 */
class TraceGapStatsTest {

    private val startMillis = 1_789_530_905_483L

    private fun eventsAt(vararg offsetsMillis: Long): List<TraceEvent> =
        offsetsMillis.map { TraceEvent.location(startMillis + it, 10f, null, null) }

    @Test
    fun `a gap is counted only once it passes the threshold, not when it reaches it`() {
        // Arrange — exactly ten minutes, and one millisecond past it.
        val exactly = TraceGapStats().recording(TraceGapStats.TEN_MINUTES_MILLIS)
        val past = TraceGapStats().recording(TraceGapStats.TEN_MINUTES_MILLIS + 1)

        // Assert — the buckets are "over 10 minutes", so the boundary value is not over it.
        assertEquals(0, exactly.gapsOver10MinCount)
        assertEquals(1, past.gapsOver10MinCount)
        assertEquals(TraceGapStats.TEN_MINUTES_MILLIS, exactly.maxGapMillis)
    }

    @Test
    fun `a gap past twenty minutes is counted in both buckets`() {
        // Arrange — the buckets bracket the region rather than partitioning it, so a long
        // silence has to appear in both or the over-10 count understates the tail.
        val stats = TraceGapStats().recording(TraceGapStats.TWENTY_MINUTES_MILLIS + 1)

        // Assert
        assertEquals(1, stats.gapsOver10MinCount)
        assertEquals(1, stats.gapsOver20MinCount)

        // And exactly twenty minutes is over ten, but not over twenty.
        val atBoundary = TraceGapStats().recording(TraceGapStats.TWENTY_MINUTES_MILLIS)
        assertEquals(1, atBoundary.gapsOver10MinCount)
        assertEquals(0, atBoundary.gapsOver20MinCount)
    }

    @Test
    fun `a negative delta is clamped rather than trusted`() {
        // Arrange — the boundary policy rotates on a clock that went backwards, so this
        // should not arrive. A negative maximum would read as "no gap observed" and
        // quietly poison the aggregate the retune is built on.
        val stats = TraceGapStats().recording(-5_000L)

        // Assert
        assertEquals(0L, stats.maxGapMillis)
        assertEquals(0, stats.gapsOver10MinCount)
    }

    @Test
    fun `measuring a finished event list matches folding the same gaps one at a time`() {
        // Arrange — the two paths that produce stats must agree, because a fragment is
        // measured in one pass while the session it came from was measured per append.
        val elevenMinutes = 11L * 60L * 1_000L
        val twentyOneMinutes = 21L * 60L * 1_000L
        val events = eventsAt(0L, elevenMinutes, elevenMinutes + 1_000L, elevenMinutes + 1_000L + twentyOneMinutes)

        // Act
        val measured = TraceGapStats.of(events)
        val folded = events.zipWithNext()
            .fold(TraceGapStats()) { stats, (a, b) -> stats.recording(b.atMillis - a.atMillis) }

        // Assert
        assertEquals(folded, measured)
        assertEquals(twentyOneMinutes, measured.maxGapMillis)
        assertEquals(2, measured.gapsOver10MinCount)
        assertEquals(1, measured.gapsOver20MinCount)
    }

    @Test
    fun `a session of fewer than two events has observed no gap at all`() {
        // Arrange — zero here means "nothing to measure", which is why the field is
        // absent rather than zero on a session recorded before measurement landed.

        // Act & Assert
        assertEquals(TraceGapStats(), TraceGapStats.of(emptyList()))
        assertEquals(TraceGapStats(), TraceGapStats.of(eventsAt(0L)))
    }

    @Test
    fun `appending folds the gap in without walking the events again`() {
        // Arrange — the append path is inside a detection callback, so measurement has to
        // be incremental. The observable proof is that the result matches a full pass.
        val sixteenMinutes = 16L * 60L * 1_000L
        var session = TraceSession.opening("session-a", TraceDeviceInfo("SM-G996N", "15", "0.1.0 (1)"), startMillis)

        // Act
        session = session.appending(TraceEvent.location(startMillis, 10f, null, null))
        session = session.appending(TraceEvent.location(startMillis + sixteenMinutes, 10f, null, null))
        session = session.appending(TraceEvent.location(startMillis + sixteenMinutes + 1_000L, 10f, null, null))

        // Assert
        assertEquals(TraceGapStats.of(session.events), session.gapStats)
        assertEquals(sixteenMinutes, requireNotNull(session.gapStats).maxGapMillis)
        assertEquals(1, requireNotNull(session.gapStats).gapsOver10MinCount)
    }

    @Test
    fun `a session recorded before measurement landed is measured once when it is reopened`() {
        // Arrange — an unmeasured session read back off disk. Starting its tally from zero
        // halfway through the trip would understate exactly the tail the retune reads.
        val elevenMinutes = 11L * 60L * 1_000L
        val unmeasured = TraceSession(
            sessionId = "session-legacy",
            deviceModel = "SM-G996N",
            osVersion = "15 (SDK 35)",
            appVersion = "0.1.0 (1)",
            startedAt = startMillis,
            endedAt = startMillis + elevenMinutes,
            events = eventsAt(0L, elevenMinutes),
            gapStats = null,
        )

        // Act
        val reopened = unmeasured.appending(TraceEvent.location(startMillis + elevenMinutes + 1_000L, 10f, null, null))

        // Assert — the silence that predates this build is still in the maximum.
        assertEquals(elevenMinutes, requireNotNull(reopened.gapStats).maxGapMillis)
        assertEquals(1, requireNotNull(reopened.gapStats).gapsOver10MinCount)
    }
}

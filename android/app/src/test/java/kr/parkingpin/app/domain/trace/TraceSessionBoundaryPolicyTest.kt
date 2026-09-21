package kr.parkingpin.app.domain.trace

import kr.parkingpin.app.domain.trace.TraceSessionBoundaryPolicy.RotationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where one recorded trip ends and the next begins.
 *
 * The boundary is what decides whether a trace can become a fixture at all: a session
 * holding a commute and three bus rides describes nothing, and a session split mid-drive
 * loses the vehicle evidence the candidate rule needs. Every case here is decided from
 * event timestamps alone — no clock is read and nothing sleeps
 * (docs/16_CODING_STANDARDS.md §8).
 */
class TraceSessionBoundaryPolicyTest {

    private val startMillis = 1_700_000_000_000L

    private fun session(
        startedAt: Long = startMillis,
        endedAt: Long = startMillis,
        eventCount: Int = 1,
    ) = TraceSession(
        sessionId = "session",
        deviceModel = "SM-G996N",
        osVersion = "15 (SDK 35)",
        appVersion = "0.1.0 (1)",
        startedAt = startedAt,
        endedAt = endedAt,
        events = List(eventCount) { TraceEvent.location(startedAt, 10f, null, null) },
    )

    @Test
    fun `nothing open is not a rotation`() {
        // Arrange — the first event ever recorded.

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(open = null, eventAtMillis = startMillis)

        // Assert — the caller opens a session; it did not close one.
        assertNull(reason)
    }

    @Test
    fun `a gap shorter than the background delivery throttle keeps one session`() {
        // Arrange — background location on the reference S21+ was measured throttled to
        // roughly one batch per 10 minutes. A sparse trace is the OS, not a new trip.
        val open = session(endedAt = startMillis)
        val tenMinutesLater = startMillis + 10L * 60L * 1_000L

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(open, tenMinutesLater)

        // Assert
        assertNull(reason)
    }

    @Test
    fun `silence for the idle gap ends the trip`() {
        // Arrange
        val open = session(endedAt = startMillis)

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(
            open,
            startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS,
        )

        // Assert
        assertEquals(RotationReason.IDLE_GAP, reason)
    }

    @Test
    fun `the idle gap is measured from the last event, not from the session start`() {
        // Arrange — a two-hour drive that ended a minute ago is still the same trip.
        val open = session(
            startedAt = startMillis,
            endedAt = startMillis + 2L * 60L * 60L * 1_000L,
        )
        val aMinuteLater = open.endedAt + 60_000L

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(open, aMinuteLater)

        // Assert
        assertNull(reason)
    }

    @Test
    fun `a session that never falls silent is still capped by duration`() {
        // Arrange — the case the rolling cap cannot handle on its own: one unbounded
        // session cannot be partly evicted.
        val open = session(
            startedAt = startMillis,
            endedAt = startMillis + TraceSessionBoundaryPolicy.MAX_SESSION_MILLIS - 1_000L,
        )
        val atCap = startMillis + TraceSessionBoundaryPolicy.MAX_SESSION_MILLIS

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(open, atCap)

        // Assert
        assertEquals(RotationReason.MAX_DURATION, reason)
    }

    @Test
    fun `a pathological event rate is capped by count`() {
        // Arrange — inside the duration bound, so only the count can fire.
        val open = session(
            endedAt = startMillis,
            eventCount = TraceSessionBoundaryPolicy.MAX_EVENTS_PER_SESSION,
        )

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(open, startMillis + 1_000L)

        // Assert
        assertEquals(RotationReason.MAX_EVENTS, reason)
    }

    @Test
    fun `a wall-clock jump backwards splits rather than corrupting the recording`() {
        // Arrange — an NTP correction mid-trip. The converter turns atMillis into relative
        // seconds from the first event, so a negative offset is an unreplayable fixture.
        val open = session(startedAt = startMillis, endedAt = startMillis + 60_000L)
        val beforeTheSessionStarted = startMillis - 60_000L

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(open, beforeTheSessionStarted)

        // Assert
        assertEquals(RotationReason.CLOCK_WENT_BACKWARDS, reason)
    }

    @Test
    fun `ordinary clock skew is not a clock jump`() {
        // Arrange — an event stamped a hair before the session opened is routine; OEM
        // timestamps and the wall clock do not agree to the millisecond.
        val open = session(startedAt = startMillis, endedAt = startMillis)

        // Act
        val reason = TraceSessionBoundaryPolicy.rotationReason(open, startMillis - 1_000L)

        // Assert
        assertNull(reason)
    }
}

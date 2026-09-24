package com.sjstudioz.parkingpin.domain.trace

import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cut §9 leaves to a person ("사람이 세션을 나눈다").
 *
 * The rules under test are the ones that make a fragment usable: it is a whole session,
 * not an annotation, it carries its own measurement, it remembers where it came from, and
 * a cut that would produce a session §9 discards is refused rather than half-applied.
 */
class TraceSessionSplitTest {

    private val startMillis = 1_789_530_905_483L
    private val minute = 60L * 1_000L

    private fun session(eventCount: Int, note: String? = null, mode: TraceMode = TraceMode.SUBWAY) = TraceSession(
        sessionId = "parent-session",
        deviceModel = "SM-G996N",
        osVersion = "15 (SDK 35)",
        appVersion = "0.1.0 (1)",
        startedAt = startMillis,
        endedAt = startMillis + (eventCount - 1) * minute,
        label = TraceLabel(mode = mode, parked = true, note = note),
        events = List(eventCount) { TraceEvent.location(startMillis + it * minute, 10f, null, null) },
        gapStats = TraceGapStats(maxGapMillis = minute, gapsOver10MinCount = 0, gapsOver20MinCount = 0),
    )

    private var nextId = 0

    private val idFactory: () -> String = { "fragment-${nextId++}" }

    private fun splitOf(session: TraceSession, index: Int): TraceSplitResult.Fragments {
        val result = TraceSessionSplit.split(session, index, idFactory)
        assertTrue("expected a split, got $result", result is TraceSplitResult.Fragments)
        return result as TraceSplitResult.Fragments
    }

    @Test
    fun `the chosen event becomes the first event of the second fragment`() {
        // Arrange — six events, cut at the fourth.
        val parent = session(eventCount = 6)

        // Act
        val (leading, trailing) = splitOf(parent, index = 3)

        // Assert
        assertEquals(parent.events.subList(0, 3), leading.events)
        assertEquals(parent.events.subList(3, 6), trailing.events)
        assertEquals(parent.events.size, leading.events.size + trailing.events.size)
    }

    @Test
    fun `each fragment is a whole session with its own identity and boundaries`() {
        // Arrange
        val parent = session(eventCount = 6)

        // Act
        val (leading, trailing) = splitOf(parent, index = 3)

        // Assert — new ids, never the parent's; the converter must be able to read either
        // fragment without knowing it was part of something longer.
        assertNotEquals(parent.sessionId, leading.sessionId)
        assertNotEquals(parent.sessionId, trailing.sessionId)
        assertNotEquals(leading.sessionId, trailing.sessionId)

        assertEquals(parent.startedAt, leading.startedAt)
        assertEquals(leading.events.last().atMillis, leading.endedAt)
        assertEquals(trailing.events.first().atMillis, trailing.startedAt)
        assertEquals(trailing.events.last().atMillis, trailing.endedAt)

        // The device it was recorded on does not change by being cut.
        assertEquals(parent.deviceModel, leading.deviceModel)
        assertEquals(parent.osVersion, trailing.osVersion)
        assertEquals(parent.appVersion, trailing.appVersion)
        assertEquals(TraceSession.PLATFORM, trailing.platform)
    }

    @Test
    fun `both fragments carry the same provenance, so the pair survives the parent`() {
        // Arrange — §9's `splitFrom`. The rolling cap will eventually evict the parent's
        // id from the directory; the pairing has to outlive that.
        val parent = session(eventCount = 6)

        // Act
        val (leading, trailing) = splitOf(parent, index = 3)

        // Assert
        val origin = TraceSplitOrigin(
            parentSessionId = parent.sessionId,
            atMillis = parent.events[3].atMillis,
        )
        assertEquals(origin, leading.splitFrom)
        assertEquals(origin, trailing.splitFrom)
    }

    @Test
    fun `each fragment is measured again, over its own events only`() {
        // Arrange — a long silence in the leading half, a short one in the trailing half.
        val parent = session(eventCount = 4).let { base ->
            val events = listOf(
                TraceEvent.location(startMillis, 10f, null, null),
                TraceEvent.location(startMillis + 25L * minute, 10f, null, null),
                TraceEvent.location(startMillis + 26L * minute, 10f, null, null),
                TraceEvent.location(startMillis + 27L * minute, 10f, null, null),
            )
            base.copy(events = events, endedAt = events.last().atMillis, gapStats = TraceGapStats.of(events))
        }

        // Act
        val (leading, trailing) = splitOf(parent, index = 2)

        // Assert — the parent's 25-minute gap belongs to the leading half alone.
        assertEquals(25L * minute, requireNotNull(leading.gapStats).maxGapMillis)
        assertEquals(1, requireNotNull(leading.gapStats).gapsOver20MinCount)
        assertEquals(minute, requireNotNull(trailing.gapStats).maxGapMillis)
        assertEquals(0, requireNotNull(trailing.gapStats).gapsOver10MinCount)
    }

    @Test
    fun `fragments come back unlabelled but keep the free-text note`() {
        // Arrange — §9: labelling each half separately is the reason to split at all. The
        // parent's label was a claim about a mixture, and the converter treats a label as
        // ground truth, so handing `subway` to both halves would assert it twice as
        // precisely as it was ever meant.
        val parent = session(eventCount = 6, note = "지하 3층, 진입 후 GPS 소실", mode = TraceMode.SUBWAY)

        // Act
        val (leading, trailing) = splitOf(parent, index = 3)

        // Assert
        listOf(leading, trailing).forEach { fragment ->
            assertEquals(TraceMode.UNKNOWN, fragment.label.mode)
            assertNull(fragment.label.parked)
            // The parent file is about to be replaced and a person cannot retype what they
            // observed, so the note is the one thing that carries.
            assertEquals("지하 3층, 진입 후 GPS 소실", fragment.label.note)
        }
    }

    @Test
    fun `a cut leaving a one-event fragment is refused, on either side`() {
        // Arrange — §9 "조각도 비생존 규칙을 따른다". Producing only the viable half would
        // silently delete the events on the other side.
        val parent = session(eventCount = 3)

        // Act
        val leadingTooSmall = TraceSessionSplit.split(parent, 1, idFactory)
        val trailingTooSmall = TraceSessionSplit.split(parent, 2, idFactory)

        // Assert — both counts are reported, so the screen can say which side was small.
        assertEquals(TraceSplitResult.FragmentNotViable(1, 2), leadingTooSmall)
        assertEquals(TraceSplitResult.FragmentNotViable(2, 1), trailingTooSmall)
    }

    @Test
    fun `the smallest splittable session is four events, cut down the middle`() {
        // Arrange — the boundary of the viability rule, from the other direction.
        val parent = session(eventCount = 4)

        // Act
        val (leading, trailing) = splitOf(parent, index = 2)

        // Assert
        assertEquals(TraceSessionBoundaryPolicy.MINIMUM_VIABLE_EVENT_COUNT, leading.events.size)
        assertEquals(TraceSessionBoundaryPolicy.MINIMUM_VIABLE_EVENT_COUNT, trailing.events.size)
    }

    @Test
    fun `a cut outside the events is refused rather than clamped`() {
        // Arrange — a cut at 0 or at the end would leave one side empty, which is not a
        // split. Clamping it to something legal would move the boundary the person chose.
        val parent = session(eventCount = 6)

        // Act & Assert
        assertEquals(TraceSplitResult.IndexOutOfRange, TraceSessionSplit.split(parent, 0, idFactory))
        assertEquals(TraceSplitResult.IndexOutOfRange, TraceSessionSplit.split(parent, -1, idFactory))
        assertEquals(TraceSplitResult.IndexOutOfRange, TraceSessionSplit.split(parent, 6, idFactory))
        assertEquals(TraceSplitResult.IndexOutOfRange, TraceSessionSplit.split(parent, 7, idFactory))
    }

    @Test
    fun `the office and travel halves of a mixed session come apart at a motion edge`() {
        // Arrange — the shape §9 was written against: a stationary stretch, a long
        // silence, then the ride. The gap that separates them is near enough the threshold
        // that no automatic rule could have found it, which is the whole point.
        val events = listOf(
            TraceEvent.motion(MotionEventKind.BECAME_STATIONARY, startMillis),
            TraceEvent.location(startMillis + minute, 12f, null, null),
            TraceEvent.motion(MotionEventKind.STARTED_WALKING, startMillis + 29L * minute),
            TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, startMillis + 47L * minute),
            TraceEvent.location(startMillis + 48L * minute, 9f, 8.4f, 320.0),
        )
        val parent = session(eventCount = 5).copy(events = events, endedAt = events.last().atMillis)

        // Act — cut at the walk that starts the journey.
        val (office, ride) = splitOf(parent, index = 2)

        // Assert
        assertEquals(listOf(TraceEventType.STATIONARY_ENTER, TraceEventType.LOCATION), office.events.map { it.type })
        assertEquals(
            listOf(TraceEventType.WALKING_ENTER, TraceEventType.VEHICLE_ENTER, TraceEventType.LOCATION),
            ride.events.map { it.type },
        )
        assertEquals(startMillis + 29L * minute, requireNotNull(ride.splitFrom).atMillis)
    }
}

package com.parkingkok.app.trace

import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.data.InMemoryPreferencesDataStore
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.MotionEventKind
import com.parkingkok.app.domain.trace.LocationQualityBucket
import com.parkingkok.app.domain.trace.TraceDeviceInfo
import com.parkingkok.app.domain.trace.TraceEventType
import com.parkingkok.app.domain.trace.TraceLabel
import com.parkingkok.app.domain.trace.TraceMode
import com.parkingkok.app.domain.trace.TraceSession
import com.parkingkok.app.domain.trace.TraceSessionBoundaryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The recorder end to end against a real traces directory, with no device and no sleeps
 * (docs/16_CODING_STANDARDS.md §8).
 *
 * The properties under test are the ones a field run depends on: a trip lands in one file,
 * a new trip lands in a new one, the cap's losses are counted, and — the one that cannot
 * be got wrong — a motion event is stamped with when it *happened*, not when the OEM got
 * round to delivering it.
 */
class TraceRecorderTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val startMillis = 1_700_000_000_000L

    private class Fixture(
        val store: FileTraceStore,
        val stateStore: DetectionStateStore,
        val recorder: TraceRecorder,
    )

    private fun fixture(maxSessions: Int = FileTraceStore.DEFAULT_MAX_SESSIONS): Fixture {
        val store = FileTraceStore(temporaryFolder.newFolder("traces"), maxSessions = maxSessions)
        val stateStore = DetectionStateStore(InMemoryPreferencesDataStore())
        var nextId = 0
        return Fixture(
            store = store,
            stateStore = stateStore,
            recorder = TraceRecorder(
                store = store,
                stateStore = stateStore,
                device = TraceDeviceInfo("SM-G996N", "15 (SDK 35)", "0.1.0 (1)"),
                sessionIdFactory = { "session-${nextId++}" },
            ),
        )
    }

    private fun motion(kind: MotionEventKind, atMillis: Long, receivedAtMillis: Long = atMillis) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = receivedAtMillis)

    private fun onlySession(f: Fixture): TraceSession = f.store.list().single()

    @Test
    fun `a motion event is stamped when it happened, not when it was delivered`() = runTest {
        // Arrange — the measured Galaxy S21+ delivery lag. Recording the receipt time
        // would shift every motion event in the trace by the OEM's latency, and the
        // fixture converted from it would describe a drive that never happened.
        val f = fixture()
        val deliveryDelayMillis = 8_772L

        // Act
        f.recorder.recordMotion(
            motion(
                MotionEventKind.STARTED_WALKING,
                atMillis = startMillis,
                receivedAtMillis = startMillis + deliveryDelayMillis,
            ),
        )

        // Assert
        val event = onlySession(f).events.single()
        assertEquals(startMillis, event.atMillis)
        assertNotEquals(startMillis + deliveryDelayMillis, event.atMillis)
        assertEquals(TraceEventType.WALKING_ENTER, event.type)
    }

    @Test
    fun `one trip lands in one session`() = runTest {
        // Arrange
        val f = fixture()

        // Act — a drive, then a walk to the lift.
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordLocation(startMillis + 30_000L, accuracyM = 8f, speedMps = 9.2f, distanceFromPreviousM = null)
        f.recorder.recordLocation(startMillis + 60_000L, accuracyM = 9f, speedMps = 8.1f, distanceFromPreviousM = 41.0)
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + 90_000L))
        f.recorder.recordMotion(motion(MotionEventKind.STARTED_WALKING, startMillis + 95_000L))

        // Assert
        val session = onlySession(f)
        assertEquals(
            listOf(
                TraceEventType.VEHICLE_ENTER,
                TraceEventType.LOCATION,
                TraceEventType.LOCATION,
                TraceEventType.VEHICLE_EXIT,
                TraceEventType.WALKING_ENTER,
            ),
            session.events.map { it.type },
        )
        assertEquals(startMillis, session.startedAt)
        assertEquals(startMillis + 95_000L, session.endedAt)
        assertEquals(41.0, requireNotNull(session.events[2].distanceFromPreviousM), 0.001)
    }

    @Test
    fun `silence past the idle gap starts a new session`() = runTest {
        // Arrange
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Act — the return trip, hours later.
        val nextTrip = startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, nextTrip))

        // Assert
        val sessions = f.store.list()
        assertEquals(2, sessions.size)
        assertEquals(nextTrip, sessions.first().startedAt)
        assertTrue(sessions.all { it.events.size == 1 })
    }

    @Test
    fun `turning detection off closes the session so the next trip gets its own file`() = runTest {
        // Arrange — the user-facing boundary, which must not wait out the idle gap.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Act
        f.recorder.closeOpenSession()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis + 1_000L))

        // Assert
        assertEquals(2, f.store.list().size)
        assertNull(f.stateStore.readTraceOpenSessionIdOnce().takeIf { it == "session-0" })
    }

    @Test
    fun `worsening accuracy emits a degradation event, improving accuracy does not`() = runTest {
        // Arrange — §2 names the event for the direction it reports.
        val f = fixture()

        // Act — good, then poor (a tunnel), then good again.
        f.recorder.recordLocation(startMillis, accuracyM = 8f, speedMps = null, distanceFromPreviousM = null)
        f.recorder.recordLocation(startMillis + 15_000L, accuracyM = 120f, speedMps = null, distanceFromPreviousM = 200.0)
        f.recorder.recordLocation(startMillis + 30_000L, accuracyM = 9f, speedMps = null, distanceFromPreviousM = 150.0)

        // Assert
        val events = onlySession(f).events
        assertEquals(
            listOf(
                TraceEventType.LOCATION,
                TraceEventType.LOCATION,
                TraceEventType.LOCATION_QUALITY_DEGRADED,
                TraceEventType.LOCATION,
            ),
            events.map { it.type },
        )
        val degraded = events[2]
        assertEquals(LocationQualityBucket.GOOD, degraded.fromBucket)
        assertEquals(LocationQualityBucket.POOR, degraded.toBucket)
        assertEquals(startMillis + 15_000L, degraded.atMillis)
    }

    @Test
    fun `an invalid accuracy is recorded but never given a bucket`() = runTest {
        // Arrange — a negative accuracy is Fused Location saying "not a fix", and it is
        // filtered at the adapter boundary. Bucketing it would hide an adapter defect.
        val f = fixture()

        // Act
        f.recorder.recordLocation(startMillis, accuracyM = 8f, speedMps = null, distanceFromPreviousM = null)
        f.recorder.recordLocation(startMillis + 1_000L, accuracyM = -1f, speedMps = null, distanceFromPreviousM = null)

        // Assert — visible in the trace, absent from the quality vocabulary.
        val events = onlySession(f).events
        assertEquals(listOf(TraceEventType.LOCATION, TraceEventType.LOCATION), events.map { it.type })
        assertEquals(-1f, requireNotNull(events[1].accuracy), 0f)
    }

    @Test
    fun `bucket comparison never crosses a session boundary`() = runTest {
        // Arrange — the previous trip ending in a car park must not be reported as this
        // trip's quality degrading.
        val f = fixture()
        f.recorder.recordLocation(startMillis, accuracyM = 8f, speedMps = null, distanceFromPreviousM = null)

        // Act
        val nextTrip = startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L
        f.recorder.recordLocation(nextTrip, accuracyM = 120f, speedMps = null, distanceFromPreviousM = null)

        // Assert
        val newest = f.store.list().first()
        assertEquals(listOf(TraceEventType.LOCATION), newest.events.map { it.type })
    }

    @Test
    fun `distance is measured inside one recording, never across a boundary`() = runTest {
        // Arrange — observed on a Galaxy S21+: a capture restarted after the boundary and
        // the opening fix was measured against the previous trip's last reliable fix.
        // Parked at home and driven to the office, that opening event would claim the
        // whole commute as movement inside the new session.
        val f = fixture()
        f.recorder.recordLocation(startMillis, accuracyM = 8f, speedMps = null, distanceFromPreviousM = null)
        f.recorder.closeOpenSession()

        // Act — the caller still offers a distance; it belongs to the previous recording.
        f.recorder.recordLocation(
            startMillis + 1_000L,
            accuracyM = 9f,
            speedMps = null,
            distanceFromPreviousM = 31_400.0,
        )
        f.recorder.recordLocation(
            startMillis + 2_000L,
            accuracyM = 9f,
            speedMps = null,
            distanceFromPreviousM = 12.5,
        )

        // Assert
        val newest = f.store.list().first()
        assertNull(newest.events.first().distanceFromPreviousM)
        assertEquals(12.5, requireNotNull(newest.events[1].distanceFromPreviousM), 0.001)
    }

    @Test
    fun `the rolling cap counts what it discarded`() = runTest {
        // Arrange — §9 requires the cap; a cap that eats evidence silently is worse than
        // none, because a thin field run looks like a thin field run.
        val f = fixture(maxSessions = 2)

        // Act — four trips, each separated by more than the idle gap.
        repeat(4) { trip ->
            val at = startMillis + trip * (TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L)
            f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, at))
        }

        // Assert
        assertEquals(2, f.store.list().size)
        assertEquals(2, f.stateStore.readTraceDiscardedSessionCountOnce())
        assertEquals(2, f.recorder.summary().discardedSessionCount)
    }

    @Test
    fun `a label is applied to the recorded session and counted in the summary`() = runTest {
        // Arrange — §9 leaves the label to a person; until then the converter cannot use
        // the recording.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        val sessionId = onlySession(f).sessionId

        // Act
        val before = f.recorder.summary()
        val failure = f.recorder.setLabel(sessionId, TraceLabel(TraceMode.BUS, parked = false, note = "환승 1회"))
        val after = f.recorder.summary()

        // Assert
        assertNull(failure)
        assertEquals(1, before.unlabelledSessionCount)
        assertEquals(0, after.unlabelledSessionCount)
        val labelled = onlySession(f)
        assertEquals(TraceMode.BUS, labelled.label.mode)
        assertEquals(false, labelled.label.parked)
        assertEquals("환승 1회", labelled.label.note)
        // Labelling must not disturb what was recorded.
        assertEquals(1, labelled.events.size)
    }

    @Test
    fun `labelling a session that is not there reports it instead of creating one`() = runTest {
        // Arrange
        val f = fixture()

        // Act
        val failure = f.recorder.setLabel("never-recorded", TraceLabel(TraceMode.CAR))

        // Assert
        assertEquals("NotFound", failure)
        assertTrue(f.store.list().isEmpty())
    }

    @Test
    fun `the summary reports what a field run is judged on`() = runTest {
        // Arrange — session count, total events, discards, and how much is still unlabelled.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordLocation(startMillis + 1_000L, 8f, null, null)
        f.recorder.closeOpenSession()
        f.recorder.recordMotion(motion(MotionEventKind.STARTED_WALKING, startMillis + 2_000L))

        // Act
        val summary = f.recorder.summary()

        // Assert
        assertEquals(2, summary.sessionCount)
        assertEquals(3, summary.eventCount)
        assertEquals(0, summary.discardedSessionCount)
        assertEquals(2, summary.unlabelledSessionCount)
        assertNull(summary.lastFailure)
    }
}

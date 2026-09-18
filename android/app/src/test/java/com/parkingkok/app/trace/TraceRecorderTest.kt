package com.parkingkok.app.trace

import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.data.InMemoryPreferencesDataStore
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.MotionEventKind
import com.parkingkok.app.domain.trace.LocationQualityBucket
import com.parkingkok.app.domain.trace.TraceDeviceInfo
import com.parkingkok.app.domain.trace.TraceEventType
import com.parkingkok.app.domain.trace.TraceGapStats
import com.parkingkok.app.domain.trace.TraceLabel
import com.parkingkok.app.domain.trace.TraceMode
import com.parkingkok.app.domain.trace.TraceSession
import com.parkingkok.app.domain.trace.TraceSessionBoundaryPolicy
import com.parkingkok.app.domain.trace.TraceSplitResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    /**
     * `prune` orders by file modification time, which the filesystem reports at a coarser
     * resolution than this test writes at. Written explicitly so the oldest session is
     * unambiguously the oldest, the same way `FileTraceStoreTest` does it.
     */
    private fun ageFile(sessionId: String, ageMillis: Long) {
        File(temporaryFolder.root, "traces/$sessionId.json")
            .setLastModified(System.currentTimeMillis() - ageMillis)
    }

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
        // Arrange — two events per trip, because §9 discards a session that rotates away
        // holding a single edge.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + 90_000L))

        // Act — the return trip, hours later. The silence is measured from the trip's last
        // event, not its first.
        val nextTrip = startMillis + 90_000L + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, nextTrip))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, nextTrip + 90_000L))

        // Assert
        val sessions = f.store.list()
        assertEquals(2, sessions.size)
        assertEquals(nextTrip, sessions.first().startedAt)
        assertTrue(sessions.all { it.events.size == 2 })
    }

    @Test
    fun `turning detection off closes the session so the next trip gets its own file`() = runTest {
        // Arrange — the user-facing boundary, which must not wait out the idle gap.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + 90_000L))

        // Act
        f.recorder.closeOpenSession()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis + 91_000L))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + 92_000L))

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
        f.recorder.recordLocation(startMillis + 15_000L, accuracyM = 8f, speedMps = null, distanceFromPreviousM = 90.0)

        // Act
        val nextTrip = startMillis + 15_000L + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L
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
        f.recorder.recordLocation(startMillis + 15_000L, accuracyM = 8f, speedMps = null, distanceFromPreviousM = 90.0)
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

        // Act — four trips, each separated by more than the idle gap, each holding enough
        // events to survive §9's viability rule.
        repeat(4) { trip ->
            val at = startMillis + trip * (TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 91_000L)
            f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, at))
            f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, at + 90_000L))
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

    // MARK: - §9 비생존 세션은 버린다

    @Test
    fun `a session rotating away with one event is discarded, and counted separately`() = runTest {
        // Arrange — the shape §9 was written against: a lone motion edge with half an hour
        // of silence either side. It cannot be replayed as a §8 fixture and tells the
        // engine nothing, so it must not reach the disk permanently.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.BECAME_STATIONARY, startMillis))

        // Act — the next trip, past the idle gap, forces the rotation that judges it.
        val nextTrip = startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, nextTrip))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, nextTrip + 90_000L))

        // Assert
        assertEquals(1, f.store.list().size)
        assertEquals(nextTrip, onlySession(f).startedAt)
        val summary = f.recorder.summary()
        assertEquals(1, summary.nonViableDropCount)
        // Never folded into the rolling-cap count: the two numbers mean opposite things.
        assertEquals(0, summary.discardedSessionCount)
    }

    @Test
    fun `two events are enough to survive rotation`() = runTest {
        // Arrange — the other side of the same boundary. One is not a sequence; two is.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + 90_000L))

        // Act
        val nextTrip = startMillis + 90_000L + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, nextTrip))

        // Assert — the first trip is still on disk; the second is open and unjudged.
        assertEquals(2, f.store.list().size)
        assertEquals(0, f.recorder.summary().nonViableDropCount)
        assertEquals(TraceSessionBoundaryPolicy.MINIMUM_VIABLE_EVENT_COUNT, 2)
    }

    @Test
    fun `an open session holding one event is never judged`() = runTest {
        // Arrange — §9: "열려 있는 세션은 아직 더 붙을 수 있다." Every session passes
        // through one event on its way to two, and on Android the open session lives on
        // disk between two PendingIntent deliveries. Judging it early would cut a trip
        // into first events that were each discarded in turn.
        val f = fixture()

        // Act
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Assert
        assertEquals(1, f.store.list().size)
        assertEquals(1, onlySession(f).events.size)
        assertEquals(0, f.recorder.summary().nonViableDropCount)
        assertEquals(onlySession(f).sessionId, f.stateStore.readTraceOpenSessionIdOnce())
    }

    @Test
    fun `turning detection off judges the session it closes`() = runTest {
        // Arrange — opting out is a rotation in every sense the rule cares about: nothing
        // more can join the session, so "더 붙을 수 있다" stops applying to it.
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Act
        f.recorder.closeOpenSession()

        // Assert
        assertTrue(f.store.list().isEmpty())
        assertEquals(1, f.recorder.summary().nonViableDropCount)
        assertNull(f.stateStore.readTraceOpenSessionIdOnce())
    }

    @Test
    fun `discarding a non-viable session never rewinds duplicate suppression`() = runTest {
        // Arrange — §9: "버려도 watermark는 전진시킨다." On Android the duplicate guards
        // are the persisted checkpoint and the location session's `lastSampleAtMillis`,
        // which drives the NOT_NEWER drop. Rewinding either would let Fused Location's
        // cached fixes walk back in and rebuild the session just thrown away.
        val f = fixture()
        val checkpoint = DetectionCheckpoint.initial(startMillis).copy(lastLocationAtMillis = startMillis)
        f.stateStore.writeCheckpoint(checkpoint)
        f.stateStore.updateLocationSessionState {
            it.copy(counters = it.counters.copy(lastSampleAtMillis = startMillis))
        }
        f.recorder.recordMotion(motion(MotionEventKind.BECAME_STATIONARY, startMillis))

        // Act — rotation discards the one-event session.
        val nextTrip = startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, nextTrip))

        // Assert — the file is gone, the guards have not moved backwards, and the only
        // pointer that named the deleted file now names the session that replaced it.
        assertEquals(1, f.recorder.summary().nonViableDropCount)
        assertEquals(checkpoint, f.stateStore.readCheckpointOnce())
        assertEquals(startMillis, f.stateStore.readLocationSessionStateOnce().counters.lastSampleAtMillis)
        assertEquals(onlySession(f).sessionId, f.stateStore.readTraceOpenSessionIdOnce())
    }

    // MARK: - §9 gap 계측

    @Test
    fun `each session carries the gaps observed inside it`() = runTest {
        // Arrange — the measurement that has to accumulate before the 30-minute threshold
        // is allowed to move.
        val f = fixture()
        val elevenMinutes = 11L * 60L * 1_000L
        val twentyOneMinutes = 21L * 60L * 1_000L

        // Act
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + elevenMinutes))
        f.recorder.recordMotion(
            motion(MotionEventKind.STARTED_WALKING, startMillis + elevenMinutes + twentyOneMinutes),
        )

        // Assert
        val stats = requireNotNull(onlySession(f).gapStats)
        assertEquals(twentyOneMinutes, stats.maxGapMillis)
        assertEquals(2, stats.gapsOver10MinCount)
        assertEquals(1, stats.gapsOver20MinCount)
    }

    @Test
    fun `gap measurement survives the process dying between two events`() = runTest {
        // Arrange — the open session is re-read from disk on every append, because
        // transitions and location batches arrive by PendingIntent into a process that
        // routinely dies in between. The measurement has to come back with it.
        val f = fixture()
        val sixteenMinutes = 16L * 60L * 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + sixteenMinutes))

        // Act — a fresh recorder over the same store and state, as after a process death.
        val revived = TraceRecorder(
            store = f.store,
            stateStore = f.stateStore,
            device = TraceDeviceInfo("SM-G996N", "15 (SDK 35)", "0.1.0 (1)"),
            sessionIdFactory = { "session-revived" },
        )
        revived.recordMotion(motion(MotionEventKind.STARTED_WALKING, startMillis + sixteenMinutes + 1_000L))

        // Assert
        val stats = requireNotNull(onlySession(f).gapStats)
        assertEquals(sixteenMinutes, stats.maxGapMillis)
        assertEquals(1, stats.gapsOver10MinCount)
    }

    @Test
    fun `the summary aggregates gaps over the sessions still on disk`() = runTest {
        // Arrange — two trips, one with a long silence in it.
        val f = fixture()
        val twentyFiveMinutes = 25L * 60L * 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + twentyFiveMinutes))
        val nextTrip = startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + twentyFiveMinutes + 1_000L
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, nextTrip))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, nextTrip + 60_000L))

        // Act
        val summary = f.recorder.summary()

        // Assert — the count is a sample size, not a claim about every trace on disk.
        assertEquals(2, summary.sessionCount)
        assertEquals(2, summary.measuredSessionCount)
        assertEquals(twentyFiveMinutes, summary.maxGapMillis)
        assertEquals(1, summary.sessionsOver10MinGapCount)
        assertEquals(1, summary.sessionsOver20MinGapCount)
    }

    // MARK: - §9 사람이 세션을 나눈다

    /** A closed session of [eventCount] events, one minute apart, ready to be cut. */
    private suspend fun recordClosedSession(f: Fixture, firstEventAtMillis: Long, eventCount: Int) {
        repeat(eventCount) { index ->
            f.recorder.recordLocation(
                firstEventAtMillis + index * 60_000L,
                accuracyM = 10f,
                speedMps = null,
                distanceFromPreviousM = if (index == 0) null else 80.0,
            )
        }
        f.recorder.closeOpenSession()
    }

    @Test
    fun `a closed session is replaced by two fragments that each carry their provenance`() = runTest {
        // Arrange
        val f = fixture()
        recordClosedSession(f, startMillis, eventCount = 6)
        val parentId = onlySession(f).sessionId
        val cutAtMillis = onlySession(f).events[3].atMillis

        // Act
        val refusal = f.recorder.splitSession(parentId, atEventIndex = 3)

        // Assert — the original is gone, replaced by two whole sessions.
        assertNull(refusal)
        val fragments = f.store.list().sortedBy { it.startedAt }
        assertEquals(2, fragments.size)
        assertNull(f.store.read(parentId))
        assertEquals(listOf(3, 3), fragments.map { it.events.size })
        fragments.forEach { fragment ->
            assertEquals(parentId, requireNotNull(fragment.splitFrom).parentSessionId)
            assertEquals(cutAtMillis, requireNotNull(fragment.splitFrom).atMillis)
            // A fragment is indistinguishable from a recorded session apart from splitFrom.
            assertEquals(fragment.events.first().atMillis, fragment.startedAt)
            assertEquals(fragment.events.last().atMillis, fragment.endedAt)
            assertEquals(TraceMode.UNKNOWN, fragment.label.mode)
            assertEquals(TraceGapStats.of(fragment.events), fragment.gapStats)
        }
    }

    @Test
    fun `the session still being recorded cannot be cut`() = runTest {
        // Arrange — §9 allows cutting a closed session only: more events may still join an
        // open one, and replacing the file would pull it out from under the recorder.
        val f = fixture()
        repeat(4) { index ->
            f.recorder.recordLocation(startMillis + index * 60_000L, 10f, null, null)
        }
        val openId = onlySession(f).sessionId

        // Act
        val refusal = f.recorder.splitSession(openId, atEventIndex = 2)

        // Assert — nothing moved.
        assertEquals(TraceSplitResult.SessionIsOpen, refusal)
        assertEquals(1, f.store.list().size)
        assertEquals(4, onlySession(f).events.size)
    }

    @Test
    fun `a cut that would leave a one-event fragment is refused and changes nothing`() = runTest {
        // Arrange — §9: "조각도 비생존 규칙을 따른다." Writing the viable half alone would
        // silently delete the events on the other side.
        val f = fixture()
        recordClosedSession(f, startMillis, eventCount = 3)
        val parentId = onlySession(f).sessionId

        // Act
        val refusal = f.recorder.splitSession(parentId, atEventIndex = 1)

        // Assert — and the reason names both sides, so the screen can say which was small.
        assertEquals(TraceSplitResult.FragmentNotViable(1, 2), refusal)
        assertEquals(1, f.store.list().size)
        assertEquals(parentId, onlySession(f).sessionId)
        assertEquals(3, onlySession(f).events.size)
    }

    @Test
    fun `cutting outside the events, or a session that is gone, is refused`() = runTest {
        // Arrange
        val f = fixture()
        recordClosedSession(f, startMillis, eventCount = 4)
        val parentId = onlySession(f).sessionId

        // Act & Assert
        assertEquals(TraceSplitResult.IndexOutOfRange, f.recorder.splitSession(parentId, atEventIndex = 0))
        assertEquals(TraceSplitResult.IndexOutOfRange, f.recorder.splitSession(parentId, atEventIndex = 4))
        assertEquals(TraceSplitResult.SessionNotFound, f.recorder.splitSession("never-recorded", atEventIndex = 1))
        assertEquals(1, f.store.list().size)
    }

    @Test
    fun `splitting re-applies the rolling cap, because one session became two`() = runTest {
        // Arrange — at the cap, with an unambiguously oldest session. Splitting evicts
        // nothing by itself; the cap decides that, and counts it the way it counts every
        // other eviction.
        val f = fixture(maxSessions = 2)
        recordClosedSession(f, startMillis, eventCount = 4)
        val oldestId = onlySession(f).sessionId
        val secondTrip = startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 600_000L
        recordClosedSession(f, secondTrip, eventCount = 4)
        val splitTargetId = f.store.list().first { it.sessionId != oldestId }.sessionId
        ageFile(oldestId, ageMillis = 10L * 60L * 1_000L)

        // Act
        val refusal = f.recorder.splitSession(splitTargetId, atEventIndex = 2)

        // Assert
        assertNull(refusal)
        val remaining = f.store.list()
        assertEquals(2, remaining.size)
        assertNull(f.store.read(oldestId))
        assertTrue(remaining.all { it.splitFrom != null })
        assertEquals(1, f.recorder.summary().discardedSessionCount)
    }
}

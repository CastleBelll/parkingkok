package kr.parkingpin.app.trace

import kr.parkingpin.app.data.DetectionStateStore
import kr.parkingpin.app.data.InMemoryPreferencesDataStore
import kr.parkingpin.app.domain.detection.MotionDomainEvent
import kr.parkingpin.app.domain.detection.MotionEventKind
import kr.parkingpin.app.domain.trace.TraceDeviceInfo
import kr.parkingpin.app.domain.trace.TraceEvent
import kr.parkingpin.app.domain.trace.TraceEventType
import kr.parkingpin.app.domain.trace.TraceGapStats
import kr.parkingpin.app.domain.trace.TraceSessionBoundaryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The September 2026 Android field set, replayed against §9's session-quality rules.
 *
 * Four sessions came off the reference Galaxy S21+ (`R3CR40VEC8P`) and they are what these
 * rules have to be judged against on this platform, the way
 * `FieldTraceSessionQualityTests` judges them on iOS. Transcribed rather than read from
 * disk so the suite stays hermetic, and reduced to what the rules actually read: event
 * **types** and **times**. Accuracy, speed and distance are deliberately absent — no rule
 * under test looks at them.
 *
 * **What this set says, and what it does not.** Three of the four sessions are runs of
 * `location` fixes at the ~17.7 s foreground cadence, and the fourth is a lone
 * `stationary_enter`. So the viability rule has something to bite on here — one session in
 * four is discarded — while the gap measurement has almost nothing: the largest silence
 * anywhere in the set is 17.8 seconds, three orders of magnitude below the 30-minute
 * threshold. The Android traces contribute no evidence for the retune yet, and this test
 * records that as a measured fact rather than leaving it to be assumed.
 */
class FieldTraceSessionQualityTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    /** One recorded session, as its event types and the silences between them. */
    private data class FieldSession(
        val id: String,
        val mode: String,
        val startedAt: Long,
        val types: List<TraceEventType>,
        val gapsMillis: List<Long>,
    )

    private companion object {

        private fun locations(count: Int) = List(count) { TraceEventType.LOCATION }

        /** All four, in recording order. */
        val SESSIONS: List<FieldSession> = listOf(
            FieldSession(
                id = "90a008e7-db5b-4b8e-9283-de91bcd6db46",
                mode = "bus",
                startedAt = 1_789_537_825_241L,
                types = locations(8),
                gapsMillis = listOf(17_753L, 17_803L, 17_746L, 17_713L, 17_759L, 17_754L, 17_769L),
            ),
            FieldSession(
                id = "df7a392e-6d82-4d14-b09a-5ed24e788186",
                mode = "unknown",
                startedAt = 1_789_538_155_169L,
                types = locations(6),
                gapsMillis = listOf(17_736L, 17_749L, 17_754L, 17_738L, 17_743L),
            ),
            FieldSession(
                id = "22137178-a7f4-452f-86b2-db18afb7db48",
                mode = "unknown",
                startedAt = 1_789_538_328_033L,
                types = locations(4),
                gapsMillis = listOf(17_748L, 17_736L, 17_775L),
            ),
            FieldSession(
                id = "c5f78aea-d55c-4c17-9586-bf3ea09b37dc",
                mode = "unknown",
                startedAt = 1_789_603_319_321L,
                types = listOf(TraceEventType.STATIONARY_ENTER),
                gapsMillis = emptyList(),
            ),
        )
    }

    /** Absolute event times, rebuilt from the session start and the recorded gaps. */
    private fun FieldSession.eventTimes(): List<Long> =
        gapsMillis.runningFold(startedAt) { previous, gap -> previous + gap }

    private fun FieldSession.events(): List<TraceEvent> =
        types.zip(eventTimes()).map { (type, atMillis) -> TraceEvent(type = type, atMillis = atMillis) }

    @Test
    fun `one of the four recorded sessions is a lone edge the viability rule discards`() {
        // Arrange — §9's rule, applied to what the device actually produced.

        // Act
        val viable = SESSIONS.filter { TraceSessionBoundaryPolicy.isViable(it.types.size) }
        val discarded = SESSIONS - viable.toSet()

        // Assert — 4 sessions / 19 events in, 3 sessions / 18 events kept.
        assertEquals(4, SESSIONS.size)
        assertEquals(19, SESSIONS.sumOf { it.types.size })
        assertEquals(3, viable.size)
        assertEquals(18, viable.sumOf { it.types.size })
        assertEquals(listOf("c5f78aea-d55c-4c17-9586-bf3ea09b37dc"), discarded.map { it.id })
        // The one that goes is a motion edge with nothing either side of it — exactly the
        // shape that cannot be replayed as a §8 fixture.
        assertEquals(listOf(TraceEventType.STATIONARY_ENTER), discarded.single().types)
    }

    @Test
    fun `no silence in the Android set comes anywhere near the 30-minute threshold`() {
        // Arrange — the measurement exists to justify moving that threshold. This set does
        // not move it, and the numbers are here so that stays a finding rather than a
        // guess: the iOS set reached 28.85 minutes, this one reaches 17.8 seconds.

        // Act
        val stats = SESSIONS.associate { it.id to TraceGapStats.of(it.events()) }

        // Assert
        assertEquals(17_803L, stats.getValue("90a008e7-db5b-4b8e-9283-de91bcd6db46").maxGapMillis)
        assertEquals(17_754L, stats.getValue("df7a392e-6d82-4d14-b09a-5ed24e788186").maxGapMillis)
        assertEquals(17_775L, stats.getValue("22137178-a7f4-452f-86b2-db18afb7db48").maxGapMillis)
        assertEquals(0L, stats.getValue("c5f78aea-d55c-4c17-9586-bf3ea09b37dc").maxGapMillis)
        stats.values.forEach { measured ->
            assertEquals(0, measured.gapsOver10MinCount)
            assertEquals(0, measured.gapsOver20MinCount)
        }
        // Nothing here is within two orders of magnitude of the boundary, so no session in
        // this set was at risk of being cut or of running on.
        assertTrue(
            stats.values.all { it.maxGapMillis < TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS / 100 },
        )
    }

    @Test
    fun `replayed through the recorder, the set lands as three sessions and one drop`() = runTest {
        // Arrange — the rules in combination, driven through the real write path rather
        // than evaluated on paper. Each session is fed in order and closed, which is the
        // boundary the recorder is asked to judge at.
        val store = FileTraceStore(temporaryFolder.newFolder("traces"))
        val stateStore = DetectionStateStore(InMemoryPreferencesDataStore())
        var nextId = 0
        val recorder = TraceRecorder(
            store = store,
            stateStore = stateStore,
            device = TraceDeviceInfo("SM-G996N", "15 (SDK 35)", "0.1.0 (1)"),
            sessionIdFactory = { "replayed-${nextId++}" },
        )

        // Act
        SESSIONS.forEach { session ->
            session.events().forEach { event ->
                recorder.recordLocationOrMotion(event)
            }
            recorder.closeOpenSession()
        }

        // Assert
        val summary = recorder.summary()
        assertEquals(3, summary.sessionCount)
        assertEquals(18, summary.eventCount)
        assertEquals(1, summary.nonViableDropCount)
        assertEquals(0, summary.discardedSessionCount)
        // Every surviving session is measured, and the aggregate matches the per-session
        // maximum above.
        assertEquals(3, summary.measuredSessionCount)
        assertEquals(17_803L, summary.maxGapMillis)
        assertEquals(0, summary.sessionsOver10MinGapCount)
        assertEquals(0, summary.sessionsOver20MinGapCount)
        assertNull(summary.lastFailure)
    }

    /**
     * Feeds one transcribed event through the entry point its type belongs to, so the
     * replay goes through the same code a delivery does.
     *
     * The field set holds only `location` and `stationary_enter`; anything else would mean
     * the transcription drifted from the files it was taken from.
     */
    private suspend fun TraceRecorder.recordLocationOrMotion(event: TraceEvent) {
        when (event.type) {
            TraceEventType.LOCATION -> recordLocation(
                atMillis = event.atMillis,
                accuracyM = 10f,
                speedMps = null,
                distanceFromPreviousM = null,
            )
            TraceEventType.STATIONARY_ENTER -> recordMotion(
                MotionDomainEvent(
                    kind = MotionEventKind.BECAME_STATIONARY,
                    atMillis = event.atMillis,
                    receivedAtMillis = event.atMillis,
                ),
            )
            else -> error("unexpected field event type ${event.type}")
        }
    }
}

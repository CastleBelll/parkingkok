package kr.parkingpin.app.trace

import kr.parkingpin.app.data.DetectionStateStore
import kr.parkingpin.app.data.InMemoryPreferencesDataStore
import kr.parkingpin.app.domain.detection.MotionDomainEvent
import kr.parkingpin.app.domain.detection.MotionEventKind
import kr.parkingpin.app.domain.trace.TraceDeviceInfo
import kr.parkingpin.app.domain.trace.TraceEvent
import kr.parkingpin.app.domain.trace.TraceLabel
import kr.parkingpin.app.domain.trace.TraceLabelPrompt
import kr.parkingpin.app.domain.trace.TraceMode
import kr.parkingpin.app.domain.trace.TraceSession
import kr.parkingpin.app.domain.trace.TraceSessionBoundaryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

/**
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 labelling: the in-app screen was never used,
 * so the label is asked for from the lock screen the moment a session closes.
 *
 * These state the rules that decide whether anything is asked at all, and what one tap
 * writes. The same rules `TraceLabelPromptTests.swift` states, so the two platforms ask the
 * same question about the same recording.
 */
class TraceLabelPromptTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val startMillis = 1_700_000_000_000L

    /** A fixed zone so the body text is a value the test can state, not the machine's. */
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    /** Records what the recorder asked a label for, without the notification system. */
    private class RecordingPrompter : TraceLabelPrompting {
        val prompts = mutableListOf<TraceLabelPrompt>()
        override suspend fun requestPrompt(prompt: TraceLabelPrompt) {
            prompts += prompt
        }
    }

    /** Stands in for [NotificationLabelPromptDelivery], which needs a real Context. */
    private class StubDelivery(private val authorized: Boolean) : LabelPromptDelivering {
        val posted = mutableListOf<TraceLabelPrompt>()
        override fun isAuthorized(): Boolean = authorized
        override fun post(prompt: TraceLabelPrompt) {
            posted += prompt
        }
    }

    private class Fixture(
        val store: FileTraceStore,
        val stateStore: DetectionStateStore,
        val recorder: TraceRecorder,
        val prompter: RecordingPrompter,
    )

    private fun fixture(): Fixture {
        val store = FileTraceStore(temporaryFolder.newFolder("traces"))
        val stateStore = DetectionStateStore(InMemoryPreferencesDataStore())
        val prompter = RecordingPrompter()
        var nextId = 0
        return Fixture(
            store = store,
            stateStore = stateStore,
            prompter = prompter,
            recorder = TraceRecorder(
                store = store,
                stateStore = stateStore,
                device = TraceDeviceInfo("SM-G996N", "15 (SDK 35)", "0.1.0 (1)"),
                sessionIdFactory = { "session-${nextId++}" },
                prompter = prompter,
            ),
        )
    }

    private fun motion(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis)

    // region When a prompt is asked for

    /** The change itself: a trip that stopped growing is asked about, once, by its own id. */
    @Test
    fun `rotation asks for a label on the session that just closed`() = runTest {
        // Arrange
        val f = fixture()

        // Act — a drive, then silence past the idle gap, then a new trip.
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.EXITED_VEHICLE, startMillis + 600_000L))
        f.recorder.recordMotion(
            motion(
                MotionEventKind.BECAME_STATIONARY,
                startMillis + 600_000L + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L,
            ),
        )

        // Assert
        assertEquals(listOf("session-0"), f.prompter.prompts.map { it.sessionId })
        assertEquals(2, f.prompter.prompts.single().eventCount)
    }

    /**
     * §9 closes the open session when the user opts out, and a session that can never grow
     * again is exactly as worth labelling as a rotated one.
     */
    @Test
    fun `switching recording off asks for a label on the open session`() = runTest {
        // Arrange
        val f = fixture()
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.STARTED_WALKING, startMillis + 300_000L))

        // Act
        f.recorder.closeOpenSession()

        // Assert
        assertEquals(listOf("session-0"), f.prompter.prompts.map { it.sessionId })
    }

    /**
     * The condition that keeps the prompt answerable. A session of location fixes holds
     * nothing a person could recognise: they were not told anything was recording, and no
     * fix distinguishes a bus from a desk. Ask only what can be answered.
     */
    @Test
    fun `a session with no motion event is never prompted for`() = runTest {
        // Arrange
        val f = fixture()

        // Act — three fixes, then silence past the idle gap.
        for (index in 0..2) {
            f.recorder.recordLocation(
                atMillis = startMillis + index * 30_000L,
                accuracyM = 12f,
                speedMps = null,
                distanceFromPreviousM = null,
            )
        }
        assertEquals(3, f.store.list().single().events.size)
        f.recorder.recordLocation(
            atMillis = startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 120_000L,
            accuracyM = 12f,
            speedMps = null,
            distanceFromPreviousM = null,
        )

        // Assert
        assertTrue(f.prompter.prompts.isEmpty())
    }

    /**
     * A non-viable session is deleted at rotation (§9 "비생존 세션은 버린다"), so a label
     * tapped onto it would have nowhere to land.
     */
    @Test
    fun `a session discarded as non-viable is never prompted for`() = runTest {
        // Arrange
        val f = fixture()

        // Act — one lone edge, then a rotation.
        f.recorder.recordMotion(motion(MotionEventKind.BECAME_STATIONARY, startMillis))
        f.recorder.recordMotion(
            motion(
                MotionEventKind.ENTERED_VEHICLE,
                startMillis + TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS + 1_000L,
            ),
        )

        // Assert
        assertEquals(1, f.stateStore.readTraceNonViableDropCountOnce())
        assertTrue(f.prompter.prompts.isEmpty())
    }

    /** §9 judges a session only at rotation; an open one may still grow. */
    @Test
    fun `an open session is not prompted for while it can still grow`() = runTest {
        // Arrange
        val f = fixture()

        // Act
        f.recorder.recordMotion(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.recorder.recordMotion(motion(MotionEventKind.STARTED_WALKING, startMillis + 60_000L))

        // Assert
        assertTrue(f.prompter.prompts.isEmpty())
    }

    // endregion

    // region Permission

    @Test
    fun `a permitted prompt is posted and nothing is counted`() = runTest {
        // Arrange
        val stateStore = DetectionStateStore(InMemoryPreferencesDataStore())
        val delivery = StubDelivery(authorized = true)
        val prompt = requireNotNull(TraceLabelPrompt.of(driveSession()))

        // Act
        TraceLabelPrompter(delivery, stateStore).requestPrompt(prompt)

        // Assert
        assertEquals(listOf(prompt.sessionId), delivery.posted.map { it.sessionId })
        assertEquals(0, stateStore.readTraceLabelPromptSuppressedCountOnce())
    }

    /**
     * The denial path §9 requires to be visible: nothing is shown, nothing is retried, and
     * the count is the only thing that says a field run had no chance of collecting labels.
     */
    @Test
    fun `without notification permission the prompt is skipped and counted`() = runTest {
        // Arrange
        val stateStore = DetectionStateStore(InMemoryPreferencesDataStore())
        val delivery = StubDelivery(authorized = false)
        val prompt = requireNotNull(TraceLabelPrompt.of(driveSession()))

        // Act
        TraceLabelPrompter(delivery, stateStore).requestPrompt(prompt)

        // Assert
        assertTrue(delivery.posted.isEmpty())
        assertEquals(1, stateStore.readTraceLabelPromptSuppressedCountOnce())
    }

    /** The count has to reach the diagnostics report, or it explains nothing. */
    @Test
    fun `the suppressed count is carried into the trace summary`() = runTest {
        // Arrange
        val f = fixture()
        TraceLabelPrompter(StubDelivery(authorized = false), f.stateStore)
            .requestPrompt(requireNotNull(TraceLabelPrompt.of(driveSession())))

        // Act
        val summary = f.recorder.summary()

        // Assert
        assertEquals(1, summary.labelPromptSuppressedCount)
    }

    // endregion

    // region What a tap writes

    @Test
    fun `tapping a mode writes that label to the session`() = runTest {
        // Arrange
        val f = fixture()
        f.store.write(driveSession())

        // Act — the receiver's own body, minus the Android plumbing it cannot reach here.
        val mode = requireNotNull(TraceLabelPromptChannel.modeOf(TraceLabelPromptChannel.actionFor(TraceMode.SUBWAY)))
        val failure = f.recorder.setLabel("drive", TraceLabelPrompt.labelFor(mode))

        // Assert — a subway parks nothing, so `parked` is answered without a second tap.
        assertNull(failure)
        assertEquals(TraceLabel(mode = TraceMode.SUBWAY, parked = false), f.store.read("drive")?.label)
    }

    /**
     * A car is the one mode where parking stays genuinely open, so the tap must not claim an
     * answer it does not have (§9 makes `parked: null` a first-class value).
     */
    @Test
    fun `tapping 자동차 leaves parked unanswered`() {
        assertEquals(TraceLabel(mode = TraceMode.CAR, parked = null), TraceLabelPrompt.labelFor(TraceMode.CAR))
        assertEquals(false, TraceLabelPrompt.labelFor(TraceMode.BUS).parked)
        assertEquals(false, TraceLabelPrompt.labelFor(TraceMode.WALK).parked)
    }

    /** The rolling cap can evict a session between the prompt and the tap. Not a crash. */
    @Test
    fun `tapping a session the store no longer has is refused, not thrown`() = runTest {
        // Arrange
        val f = fixture()

        // Act
        val failure = f.recorder.setLabel("gone", TraceLabelPrompt.labelFor(TraceMode.CAR))

        // Assert
        assertEquals("NotFound", failure)
    }

    @Test
    fun `an unknown intent action maps to no mode`() {
        assertNull(TraceLabelPromptChannel.modeOf(null))
        assertNull(TraceLabelPromptChannel.modeOf("android.intent.action.VIEW"))
        for (mode in TraceLabelPrompt.OFFERED_MODES) {
            assertEquals(mode, TraceLabelPromptChannel.modeOf(TraceLabelPromptChannel.actionFor(mode)))
        }
    }

    /** One notification per session, so the right one is the one that goes away on a tap. */
    @Test
    fun `each session gets its own notification id`() {
        assertNotNull(TraceLabelPromptChannel.notificationId("session-0"))
        assertTrue(
            TraceLabelPromptChannel.notificationId("session-0") !=
                TraceLabelPromptChannel.notificationId("session-1"),
        )
    }

    // endregion

    // region What the user reads

    /**
     * The body has to be recognisable as *that* trip — 17 minutes in a vehicle then a walk —
     * which is what makes a one-tap answer trustworthy rather than a guess.
     */
    @Test
    fun `the body states the time range, the length and what the device saw`() {
        // Arrange — 07:13 → 07:42 KST, a 17-minute ride followed by a walk.
        val session = session(
            sessionId = "commute",
            startedAt = startMillis,
            endedAt = startMillis + 29 * 60_000L,
            events = listOf(
                TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, startMillis),
                TraceEvent.motion(MotionEventKind.EXITED_VEHICLE, startMillis + 17 * 60_000L),
                TraceEvent.motion(MotionEventKind.STARTED_WALKING, startMillis + 18 * 60_000L),
            ),
        )

        // Act
        val prompt = requireNotNull(TraceLabelPrompt.of(session))

        // Assert
        assertEquals("차량 17분 + 도보", prompt.movementSummary)
        assertEquals("07:13–07:42 · 29분 · 차량 17분 + 도보 · 이벤트 3개", prompt.body(seoul))
    }

    /**
     * The Activity Transition API routinely reports one side of the vehicle pair only, and
     * an unclosed ride ran to the end of the recording — not to zero minutes.
     */
    @Test
    fun `a ride with no exit is measured to the end of the session`() {
        // Arrange
        val session = session(
            sessionId = "open-ride",
            startedAt = startMillis,
            endedAt = startMillis + 12 * 60_000L,
            events = listOf(
                TraceEvent.motion(MotionEventKind.STOPPED_BEING_STATIONARY, startMillis),
                TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, startMillis + 2 * 60_000L),
            ),
        )

        // Act
        val prompt = requireNotNull(TraceLabelPrompt.of(session))

        // Assert
        assertEquals("차량 10분 + 정지", prompt.movementSummary)
    }

    /**
     * The whole recording format exists to leave the device's location behind. A notification
     * body is read on a lock screen and photographed into bug reports, so it is held to the
     * same rule as the trace itself (docs/00_CORE_RULES.md Privacy).
     */
    @Test
    fun `the prompt text carries no coordinate and no accuracy`() {
        // Arrange — a session whose location event carries accuracy, speed and distance.
        val session = session(
            sessionId = "detailed",
            startedAt = startMillis,
            endedAt = startMillis + 300_000L,
            events = listOf(
                TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, startMillis),
                TraceEvent.location(
                    atMillis = startMillis + 60_000L,
                    accuracyM = 37.5f,
                    speedMps = 12.25f,
                    distanceFromPreviousM = 812.5,
                ),
                TraceEvent.motion(MotionEventKind.EXITED_VEHICLE, startMillis + 240_000L),
            ),
        )

        // Act
        val prompt = requireNotNull(TraceLabelPrompt.of(session))
        val text = "${TraceLabelPrompt.TITLE} ${prompt.body(seoul)}"

        // Assert — no coordinate exists to leak, and none of the location detail is quoted.
        for (forbidden in listOf("37.5", "12.25", "812.5", "latitude", "longitude")) {
            assertFalse("prompt text leaked $forbidden", text.contains(forbidden))
        }
    }

    /**
     * Android shows three action buttons. The order is what decides which mode drops off, so
     * it is stated here rather than left to whoever edits the list next.
     */
    @Test
    fun `three actions fit, and they are the ones only a person can answer`() {
        assertEquals(
            listOf(TraceMode.CAR, TraceMode.BUS, TraceMode.SUBWAY, TraceMode.WALK),
            TraceLabelPrompt.OFFERED_MODES,
        )
        assertEquals(
            listOf(TraceMode.CAR, TraceMode.BUS, TraceMode.SUBWAY),
            TraceLabelPrompt.OFFERED_MODES.take(TraceLabelPromptChannel.MAX_ACTIONS),
        )
        assertFalse(TraceMode.UNKNOWN in TraceLabelPrompt.OFFERED_MODES)
    }

    // endregion

    private fun driveSession(): TraceSession = session(
        sessionId = "drive",
        startedAt = startMillis,
        endedAt = startMillis + 600_000L,
        events = listOf(
            TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, startMillis),
            TraceEvent.motion(MotionEventKind.EXITED_VEHICLE, startMillis + 540_000L),
        ),
    )

    private fun session(
        sessionId: String,
        startedAt: Long,
        endedAt: Long,
        events: List<TraceEvent>,
    ): TraceSession = TraceSession(
        sessionId = sessionId,
        deviceModel = "SM-G996N",
        osVersion = "15 (SDK 35)",
        appVersion = "0.1.0 (1)",
        startedAt = startedAt,
        endedAt = endedAt,
        events = events,
    )
}

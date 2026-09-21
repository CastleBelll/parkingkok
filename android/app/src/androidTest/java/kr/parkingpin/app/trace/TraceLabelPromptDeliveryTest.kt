package kr.parkingpin.app.trace

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.parkingpin.app.domain.detection.MotionEventKind
import kr.parkingpin.app.domain.trace.TraceEvent
import kr.parkingpin.app.domain.trace.TraceLabel
import kr.parkingpin.app.domain.trace.TraceLabelPrompt
import kr.parkingpin.app.domain.trace.TraceMode
import kr.parkingpin.app.domain.trace.TraceSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of the label prompt that only a device can answer
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 labelling).
 *
 * The JVM suite states which sessions are worth asking about and what one tap writes.
 * Everything the OS owns — the diagnostics channel existing, the notification actually being
 * posted, three action buttons surviving Android's limit, the PendingIntent reaching
 * [TraceLabelPromptReceiver] — cannot be faked, so it is asserted here against the real
 * `NotificationManager` on the reference Galaxy S21+.
 *
 * The runtime permission is granted through the instrumentation's own `UiAutomation` rather
 * than by asking a human to tap, so the run is self-sufficient. If notifications are
 * switched off for the app at a level a grant cannot reach, the posting assertions are
 * *skipped* rather than passed — that is the suppression path, and it has its own counter.
 */
@RunWith(AndroidJUnit4::class)
class TraceLabelPromptDeliveryTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val prompt = TraceLabelPrompt(
        sessionId = "androidtest-session",
        startedAtMillis = 1_700_000_000_000L,
        endedAtMillis = 1_700_000_000_000L + 29 * 60_000L,
        eventCount = 3,
        vehicleMillis = 17 * 60_000L,
        hasVehicle = true,
        hasWalking = true,
        hasStationary = false,
    )

    @Before
    @After
    fun clearPostedNotification() {
        manager.cancel(TraceLabelPromptChannel.notificationId(prompt.sessionId))
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission on Android 13+, and the field checklist
     * grants it from the diagnostics screen. Here the instrumentation grants it directly, so
     * the acceptance run does not depend on someone tapping a system dialog.
     */
    @Before
    fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun postingCreatesItsOwnChannelAndNeverTheProductOnes() {
        // Arrange
        val delivery = NotificationLabelPromptDelivery(context)

        // Act
        delivery.post(prompt)

        // Assert — a diagnostics channel of its own. docs/04_ANDROID_IMPLEMENTATION.md §9's
        // product channels must stay untouched: muting "what was that trip?" must never be
        // able to mute a parking alert.
        val channel = manager.getNotificationChannel(TraceLabelPromptChannel.ID)
        assertNotNull("the diagnostics channel was not created", channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertTrue(
            "the prompt must not live on a product channel",
            manager.notificationChannels.none { it.id == "parking_detection" || it.id == "parking_status" },
        )
    }

    /**
     * The acceptance question: does a notification actually appear on this device, carrying
     * the text and the one-tap actions a person is meant to answer with?
     */
    @Test
    fun theNotificationIsPostedWithThreeLabelActionsAndNoCoordinate() {
        // Arrange
        val delivery = NotificationLabelPromptDelivery(context)
        assumeTrue("notifications are switched off for this app", delivery.isAuthorized())

        // Act
        delivery.post(prompt)

        // Assert
        val posted = manager.activeNotifications
            .firstOrNull { it.id == TraceLabelPromptChannel.notificationId(prompt.sessionId) }
        assertNotNull("no notification was posted", posted)
        val notification = posted!!.notification

        // Android shows three action buttons, so the fourth offered mode is dropped rather
        // than silently ignored by the OS.
        assertEquals(TraceLabelPromptChannel.MAX_ACTIONS, notification.actions.size)
        assertEquals(
            listOf(TraceMode.CAR, TraceMode.BUS, TraceMode.SUBWAY).map(TraceLabelPrompt::actionTitle),
            notification.actions.map { it.title.toString() },
        )
        for (action in notification.actions) {
            assertNotNull("action ${action.title} has no PendingIntent", action.actionIntent)
        }

        val text = "${notification.extras.getString("android.title")} " +
            notification.extras.getString("android.text")
        assertTrue("the title must read as a question, not as a parking claim", TraceLabelPrompt.TITLE in text)
        // The same rule the trace file is held to: this text is read on a lock screen.
        for (forbidden in listOf("latitude", "longitude", "lat", "lon")) {
            assertTrue("prompt text leaked $forbidden", !text.contains(forbidden))
        }
    }

    /**
     * The acceptance question the JVM suite cannot answer: firing the action's own
     * PendingIntent is the tap minus the finger, and it proves the whole path — the
     * notification the OS is holding, the broadcast into [TraceLabelPromptReceiver], the
     * container it resolves, and the label reaching the trace file on this device's disk.
     */
    @Test
    fun tappingAnActionWritesTheLabelToDisk() {
        // Arrange — a real closed session in the app's own traces directory, so the receiver
        // writes where it would in the field.
        val delivery = NotificationLabelPromptDelivery(context)
        assumeTrue("notifications are switched off for this app", delivery.isAuthorized())
        val store = FileTraceStore(FileTraceStore.defaultDirectory(context))
        try {
            assertEquals(null, store.write(closedSession()))
            delivery.post(prompt)
            val posted = manager.activeNotifications
                .first { it.id == TraceLabelPromptChannel.notificationId(prompt.sessionId) }

            // Act — the subway button, as the OS would send it.
            posted.notification.actions
                .first { it.title.toString() == TraceLabelPrompt.actionTitle(TraceMode.SUBWAY) }
                .actionIntent
                .send()

            // Assert — the label is on disk, and a subway parks nothing.
            val labelled = awaitLabel()
            assertEquals(TraceMode.SUBWAY, labelled?.mode)
            assertEquals(false, labelled?.parked)
            // The receiver takes the notification down once it has acted on the tap.
            assertTrue(
                "the tapped notification should be gone",
                manager.activeNotifications.none {
                    it.id == TraceLabelPromptChannel.notificationId(prompt.sessionId)
                },
            )
        } finally {
            store.delete(prompt.sessionId)
        }
    }

    /**
     * The broadcast crosses a process boundary, so the only honest wait is on the outcome.
     * Bounded, and it fails by timing out rather than by sleeping a guessed interval
     * (docs/16_CODING_STANDARDS.md §8 — the rule is against sleeping *instead* of observing).
     */
    private fun awaitLabel(): TraceLabel? {
        val store = FileTraceStore(FileTraceStore.defaultDirectory(context))
        val deadline = System.currentTimeMillis() + LABEL_WRITE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val label = store.read(prompt.sessionId)?.label
            if (label != null && label.mode != TraceMode.UNKNOWN) return label
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return store.read(prompt.sessionId)?.label
    }

    private fun closedSession(): TraceSession = TraceSession(
        sessionId = prompt.sessionId,
        deviceModel = "androidTest",
        osVersion = "androidTest",
        appVersion = "androidTest",
        startedAt = prompt.startedAtMillis,
        endedAt = prompt.endedAtMillis,
        events = listOf(
            TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, prompt.startedAtMillis),
            TraceEvent.motion(MotionEventKind.EXITED_VEHICLE, prompt.endedAtMillis),
        ),
    )

    private companion object {
        const val LABEL_WRITE_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}

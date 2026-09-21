package com.sjstudioz.parkingpin.analytics

import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Remembers every switch flip, in order. */
private class RecordingCollectionControl : AnalyticsCollectionControl {

    val calls = mutableListOf<Boolean>()

    override fun setEnabled(granted: Boolean) {
        calls += granted
    }
}

/**
 * **The half of docs/07 "동의" that `AnalyticsConsentGateTest` cannot see.**
 *
 * That test proves no *app* event escapes before consent. Firebase Analytics also reports
 * on its own — `session_start`, `first_open`, `app_update` — and none of that passes
 * through `AnalyticsRecorder`. This is what holds the SDK's own switch to the same flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsCollectionGateTest {

    private val dataStore = InMemoryPreferencesDataStore()
    private val consent = AnalyticsConsentStore(dataStore)
    private val control = RecordingCollectionControl()
    private val gate = AnalyticsCollectionGate(consent, control)

    @Test
    fun `a fresh install switches the SDK off, matching the manifest default`() = runTest {
        // Arrange — nothing written to the store; this is first launch.

        // Act
        val running = backgroundScope.launchGate(testScheduler)
        runCurrent()

        // Assert — the manifest already ships `false`; re-asserting it means the runtime
        // state is decided by the stored flag rather than by whatever the last session left
        // behind, since `setAnalyticsCollectionEnabled` persists across launches.
        assertEquals(listOf(false), control.calls)
        running.cancel()
    }

    @Test
    fun `granting consent is the only thing that switches the SDK on`() = runTest {
        // Arrange
        val running = backgroundScope.launchGate(testScheduler)
        runCurrent()
        assertEquals(listOf(false), control.calls)

        // Act
        consent.setGranted(true)
        runCurrent()

        // Assert
        assertEquals(listOf(false, true), control.calls)
        running.cancel()
    }

    @Test
    fun `revoking consent switches the SDK off without waiting for another event`() = runTest {
        // Arrange — opted in.
        consent.setGranted(true)
        val running = backgroundScope.launchGate(testScheduler)
        runCurrent()
        assertEquals(listOf(true), control.calls)

        // Act
        consent.setGranted(false)
        runCurrent()

        // Assert — docs/07: 끄면 즉시 중단한다. Nothing had to be reported for the SDK to
        // hear about it.
        assertEquals(listOf(true, false), control.calls)
        running.cancel()
    }

    @Test
    fun `an unchanged flag is not re-applied`() = runTest {
        // Arrange
        val running = backgroundScope.launchGate(testScheduler)
        runCurrent()

        // Act — the same value written twice, as an idle toggle would.
        consent.setGranted(false)
        runCurrent()
        consent.setGranted(false)
        runCurrent()

        // Assert — one call, from the initial read.
        assertEquals(listOf(false), control.calls)
        running.cancel()
    }

    /**
     * The gate never returns, so it runs in the background scope and the test cancels it.
     * Unconfined so a consent write lands on the control within the same `runCurrent`.
     */
    private fun CoroutineScope.launchGate(scheduler: TestCoroutineScheduler): Job =
        launch(UnconfinedTestDispatcher(scheduler)) { gate.run() }
}

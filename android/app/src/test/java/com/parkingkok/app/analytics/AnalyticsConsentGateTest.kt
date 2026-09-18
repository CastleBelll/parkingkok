package com.parkingkok.app.analytics

import com.parkingkok.app.data.InMemoryPreferencesDataStore
import com.parkingkok.app.detection.MutableTestClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **docs/07 "동의".** Default off, nothing sent and nothing buffered before the opt-in, and a
 * revocation stops transmission immediately.
 */
class AnalyticsConsentGateTest {

    private val now = 1_780_000_000_000L
    private val clock = MutableTestClock(epochMillis = now)
    private val sink = RecordingAnalyticsSink()

    /** Held here, not inside the store, so a test can open a second store over it. */
    private val dataStore = InMemoryPreferencesDataStore()
    private val consent = AnalyticsConsentStore(dataStore)
    private val recorder = AnalyticsRecorder(consentStore = consent, sink = sink, clock = clock)

    @Test
    fun `consent defaults to off, so a fresh install transmits nothing`() = runTest {
        // Arrange — nothing written to the store; this is first launch.

        // Act — everything the app can report, before anyone was asked.
        EventSamples.all.forEach { recorder.record(it) }

        // Assert
        assertFalse(consent.isGrantedOnce())
        assertTrue(sink.payloads.isEmpty())
    }

    @Test
    fun `nothing recorded before consent is flushed after it — there is no buffer`() = runTest {
        // Arrange — three events while opted out.
        recorder.record(AnalyticsEvent.ParkingManualSaved)
        recorder.record(AnalyticsEvent.OnboardingCompleted)
        recorder.record(AnalyticsEvent.PaywallViewed)
        assertTrue(sink.payloads.isEmpty())

        // Act — the user opts in afterwards.
        consent.setGranted(true)

        // Assert — the backlog does not exist. docs/07: consenting to future reporting is
        // not consenting to the past.
        assertTrue(sink.payloads.isEmpty())

        // …and the next event does go.
        recorder.record(AnalyticsEvent.ReferralShared)
        assertEquals(listOf("referral_shared"), sink.names)
    }

    @Test
    fun `after consent, every event reaches the transport`() = runTest {
        // Arrange
        consent.setGranted(true)

        // Act
        EventSamples.all.forEach { recorder.record(it) }

        // Assert
        assertEquals(EventSamples.all.size, sink.payloads.size)
        assertEquals(AnalyticsEvent.ALL_NAMES, sink.names.toSet())
    }

    @Test
    fun `switching consent off stops the very next event`() = runTest {
        // Arrange — opted in, one event through.
        consent.setGranted(true)
        recorder.record(AnalyticsEvent.ParkingManualSaved)
        assertEquals(1, sink.payloads.size)

        // Act
        consent.setGranted(false)
        recorder.record(AnalyticsEvent.ParkingManualSaved)
        recorder.record(AnalyticsEvent.PaywallViewed)

        // Assert — nothing after the revocation, with no restart or invalidation step.
        assertEquals(1, sink.payloads.size)
    }

    @Test
    fun `the consent change itself is never an event`() = runTest {
        // Act — grant, then revoke. Both go through the store, which has no recorder.
        consent.setGranted(true)
        consent.setGranted(false)

        // Assert — docs/07: an event on the grant would be a transmission decided by the
        // state before consent existed. There is no such event in AnalyticsEvent to send.
        assertTrue(sink.payloads.isEmpty())
        assertFalse(AnalyticsEvent.ALL_NAMES.any { it.contains("consent") })
    }

    @Test
    fun `the payload is stamped from the injected clock`() = runTest {
        // Arrange
        consent.setGranted(true)

        // Act
        recorder.record(AnalyticsEvent.ParkingManualSaved)

        // Assert
        assertEquals(now, sink.payloads.single().occurredAtMillis)
    }

    @Test
    fun `consent survives a new store over the same preferences`() = runTest {
        // Arrange
        consent.setGranted(true)

        // Act — the same DataStore read through a fresh wrapper, as a relaunch would build.
        val reopened = AnalyticsConsentStore(dataStore)

        // Assert
        assertTrue(reopened.isGrantedOnce())

        // …and a revocation persists the same way.
        reopened.setGranted(false)
        assertFalse(consent.isGrantedOnce())
    }
}

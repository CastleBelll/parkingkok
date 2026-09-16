package com.parkingkok.app.diagnostics

import com.parkingkok.app.detection.RegistrationStatus
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.MotionEventKind
import com.parkingkok.app.domain.detection.ReliableLocation
import com.parkingkok.app.domain.location.LocationDropReason
import com.parkingkok.app.domain.location.LocationQualityEntry
import com.parkingkok.app.domain.location.LocationQualitySample
import com.parkingkok.app.domain.location.LocationSessionState
import com.parkingkok.app.domain.trace.TraceSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics export must not carry a coordinate.
 *
 * The file is copied off the device by design, so a coordinate reaching it would walk
 * parking locations straight past docs/00_CORE_RULES.md Privacy. [DetectionCheckpoint] is
 * `@Serializable` and carries [ReliableLocation], so this has to be checked against the
 * **encoded bytes** rather than against the field list: a redacted `toString` would not
 * have caught it, because the serializer never calls `toString`.
 *
 * Same test, same reasoning as iOS `DiagnosticsReportTests.encodedReportCarriesNoCoordinate`.
 */
class DiagnosticsReportTest {

    private val now = 1_700_000_000_000L
    private val json = Json { encodeDefaults = true }

    private fun report(
        checkpoint: DetectionCheckpoint? = null,
        sessionState: LocationSessionState = LocationSessionState(),
        qualityHistory: List<LocationQualityEntry> = emptyList(),
        transitions: List<MotionDomainEvent> = emptyList(),
        trace: TraceSummary = TraceSummary(),
    ) = DiagnosticsReport.from(
        nowMillis = now,
        checkpoint = checkpoint,
        sessionState = sessionState,
        qualityHistory = qualityHistory,
        transitions = transitions,
        activityRecognitionGranted = true,
        foregroundLocationGranted = true,
        backgroundLocationGranted = false,
        smartDetectionEnabled = true,
        transitionRegistration = RegistrationStatus.Active(specVersion = 1, registeredAtMillis = now),
        trace = trace,
    )

    private fun locatedCheckpoint() = DetectionCheckpoint(
        state = com.parkingkok.app.domain.detection.DetectionState.PARKING_CANDIDATE,
        stateEnteredAtMillis = now,
        lastReliableLocation = ReliableLocation(
            latitude = 37.123_456_7,
            longitude = 127.987_654_3,
            horizontalAccuracyM = 12f,
            capturedAtMillis = now - 60_000L,
        ),
        travelDistanceEstimateMeters = 1_234.0,
        revision = 7,
    )

    @Test
    fun `no coordinate survives into the encoded report, even when the checkpoint has one`() {
        // Arrange — distinctive values that would be unmistakable in the output.
        val located = locatedCheckpoint()

        // Act
        val encoded = json.encodeToString(report(checkpoint = located))

        // Assert
        assertFalse(encoded.contains("37.123"))
        assertFalse(encoded.contains("127.987"))
        assertFalse(encoded.lowercase().contains("latitude"))
        assertFalse(encoded.lowercase().contains("longitude"))
        assertFalse(encoded.lowercase().contains("lastreliablelocation"))
    }

    @Test
    fun `the reliable fix is reported as presence and quality, never as a place`() {
        // Arrange
        val located = locatedCheckpoint()

        // Act
        val withFix = report(checkpoint = located)
        val withoutFix = report(checkpoint = DetectionCheckpoint.initial(now))

        // Assert
        assertTrue(withFix.hasReliableLocation)
        assertEquals(12f, requireNotNull(withFix.reliableLocationAccuracyM), 0f)
        assertEquals(now - 60_000L, withFix.reliableLocationCapturedAtMillis)
        assertFalse(withoutFix.hasReliableLocation)
        assertNull(withoutFix.reliableLocationAccuracyM)
    }

    @Test
    fun `the ring buffer reaches the report as quality and drop reasons only`() {
        // Arrange — docs/05 §5: exclusions are counted and exposed, never dropped quietly.
        val history = listOf(
            LocationQualityEntry(LocationQualitySample(now - 10_000L, 8f, 14f), dropReason = null),
            LocationQualityEntry(
                LocationQualitySample(now - 16_260_000L, 8f),
                dropReason = LocationDropReason.CACHED_FIX_REPLAY,
            ),
        )

        // Act
        val exported = report(qualityHistory = history)
        val encoded = json.encodeToString(exported)

        // Assert
        assertEquals(2, exported.recentFixes.size)
        assertNull(exported.recentFixes[0].dropReason)
        assertEquals("CACHED_FIX_REPLAY", exported.recentFixes[1].dropReason)
        assertFalse(encoded.lowercase().contains("latitude"))
    }

    @Test
    fun `transition delivery delay survives the projection`() {
        // Arrange — the OEM delay number docs/04 §20 asks P0 to measure.
        val transitions = listOf(
            MotionDomainEvent(
                kind = MotionEventKind.STARTED_WALKING,
                atMillis = now - 8_772L,
                receivedAtMillis = now,
            ),
        )

        // Act
        val exported = report(transitions = transitions)

        // Assert
        assertEquals(1, exported.transitionEventCount)
        assertEquals("walking_enter", exported.recentTransitions[0].kind)
        assertEquals(8_772L, exported.recentTransitions[0].deliveryDelayMillis)
    }

    @Test
    fun `the trace recorder's losses are reported, not hidden`() {
        // Arrange — docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 requires a rolling cap,
        // and a field run that looks thin because the cap ate half of it has to say so.
        val trace = TraceSummary(
            sessionCount = 8,
            eventCount = 412,
            discardedSessionCount = 12,
            unlabelledSessionCount = 5,
        )

        // Act
        val exported = report(trace = trace)

        // Assert
        assertEquals(8, exported.trace.sessionCount)
        assertEquals(412, exported.trace.eventCount)
        assertEquals(12, exported.trace.discardedSessionCount)
        assertEquals(5, exported.trace.unlabelledSessionCount)
    }

    @Test
    fun `the report round-trips through JSON at the declared schema version`() {
        // Arrange
        val original = report(checkpoint = locatedCheckpoint())

        // Act
        val decoded = json.decodeFromString<DiagnosticsReport>(json.encodeToString(original))

        // Assert
        assertEquals(original, decoded)
        assertEquals(DiagnosticsReport.SCHEMA_VERSION, decoded.schemaVersion)
    }
}

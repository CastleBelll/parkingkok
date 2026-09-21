package kr.parkingpin.app.diagnostics

import kr.parkingpin.app.detection.RegistrationStatus
import kr.parkingpin.app.domain.detection.DetectionCheckpoint
import kr.parkingpin.app.domain.detection.MotionDomainEvent
import kr.parkingpin.app.domain.detection.MotionEventKind
import kr.parkingpin.app.domain.detection.ReliableLocation
import kr.parkingpin.app.domain.location.DrivingSessionEvidence
import kr.parkingpin.app.domain.location.LocationDropReason
import kr.parkingpin.app.domain.location.LocationQualityEntry
import kr.parkingpin.app.domain.location.LocationQualitySample
import kr.parkingpin.app.domain.location.LocationSessionState
import kr.parkingpin.app.domain.location.MovementAnchor
import kr.parkingpin.app.domain.location.MovementEvidence
import kr.parkingpin.app.domain.location.MovementEvidenceRejectReason
import kr.parkingpin.app.domain.trace.TraceSummary
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
        state = kr.parkingpin.app.domain.detection.DetectionState.PARKING_TRANSITION,
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
    fun `the measured clauses are exported as counts, and their anchors never are`() {
        // Arrange — §7's two measured clauses are the only parts of the driving guard that
        // have to hold a position to do their job, so this is where a coordinate could
        // escape. The session below has travelled underground: no speed on any fix, two
        // pairs decided by the distance fallback, and both anchors holding a real position.
        val evidence = DrivingSessionEvidence(
            vehicleFirstSeenAtMillis = now - 600_000L,
            lastVehicleEvidenceAtMillis = now - 30_000L,
            travelDistanceMeters = 4_100.0,
            distanceNoiseFloorRejectCount = 46,
            distanceAnchor = MovementAnchor(now - 20_000L, 37.111_222_3, 127.444_555_6, 120f),
            movement = MovementEvidence(
                movingSampleCount = 2,
                speedAvailableCount = 0,
                speedMissingCount = 58,
                derivedMovingSampleCount = 2,
                rejectReason = MovementEvidenceRejectReason.ACCURACY_TOO_COARSE,
                outlierCount = 1,
                anchor = MovementAnchor(now - 30_000L, 37.123_456_7, 127.987_654_3, 300f),
            ),
        )

        // Act
        val exported = report(sessionState = LocationSessionState(evidence = evidence))
        val encoded = json.encodeToString(exported)

        // Assert — the four counters §7 asks for, plus the reason, as wire strings.
        assertEquals(2, exported.movingSampleCount)
        assertEquals(0, exported.speedAvailableCount)
        assertEquals(58, exported.speedMissingCount)
        assertEquals(2, exported.derivedMovingSampleCount)
        assertEquals("accuracyTooCoarse", exported.movementEvidenceRejectReason)
        assertEquals(1, exported.movementOutlierCount)
        // The distance clause, as the metres it accumulated and the legs it refused. The
        // pair is the point: metres alone cannot say whether the floor is set wrong.
        assertEquals(4_100.0, requireNotNull(exported.travelDistanceMeters), 0.001)
        assertEquals(46, exported.distanceNoiseFloorRejectCount)
        // And both anchors stayed behind.
        assertFalse(encoded.contains("37.123"))
        assertFalse(encoded.contains("127.987"))
        assertFalse(encoded.contains("37.111"))
        assertFalse(encoded.contains("127.444"))
        assertFalse(encoded.lowercase().contains("anchor"))
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

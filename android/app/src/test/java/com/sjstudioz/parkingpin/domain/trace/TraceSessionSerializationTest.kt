package com.sjstudioz.parkingpin.domain.trace

import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace file format against docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9.
 *
 * Two things are being protected. First, the **wire vocabulary**: a trace recorded here is
 * read by the fixture converter and compared against one recorded on iOS, so a renamed
 * event type does not fail loudly — it produces two recordings that quietly cannot be used
 * against each other. Second, the **absence of coordinates**: this file is copied off the
 * device by design, so it is checked against the encoded bytes, the same way
 * `DiagnosticsReportTest` does, and for the same reason — a redacted `toString` proves
 * nothing, because the serializer never calls it.
 */
class TraceSessionSerializationTest {

    private val startMillis = 1_789_530_905_483L

    /** Matches [com.sjstudioz.parkingpin.trace.FileTraceStore]'s encoder, which is what ships. */
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private fun session(events: List<TraceEvent>) = TraceSession(
        sessionId = "9f1c2d3e-0000-4000-8000-000000000001",
        deviceModel = "SM-G996N",
        osVersion = "15 (SDK 35)",
        appVersion = "0.1.0 (1)",
        startedAt = startMillis,
        endedAt = events.maxOfOrNull { it.atMillis } ?: startMillis,
        events = events,
    )

    @Test
    fun `every normalized motion event maps to the contract wire string`() {
        // Arrange — §2's vocabulary, as the coordinator fixed it. Android's internal
        // `still_enter`/`still_exit` naming stops at the adapter boundary.
        val expected = mapOf(
            MotionEventKind.ENTERED_VEHICLE to "vehicle_enter",
            MotionEventKind.EXITED_VEHICLE to "vehicle_exit",
            MotionEventKind.STARTED_WALKING to "walking_enter",
            MotionEventKind.BECAME_STATIONARY to "stationary_enter",
            MotionEventKind.STOPPED_BEING_STATIONARY to "stationary_exit",
        )

        // Act
        val encoded = expected.keys.associateWith { kind ->
            json.encodeToString(TraceEvent.motion(kind, startMillis))
        }

        // Assert
        expected.forEach { (kind, wire) ->
            assertTrue(
                "$kind must encode as \"$wire\", got ${encoded[kind]}",
                encoded.getValue(kind).contains("\"type\":\"$wire\""),
            )
        }
        // Every kind is covered, so adding one to the enum fails here rather than silently
        // going unrecorded.
        assertEquals(MotionEventKind.entries.size, expected.size)
    }

    @Test
    fun `no coordinate survives into the encoded trace`() {
        // Arrange — a whole recorded trip. The distance is a real computed value; the
        // coordinates it was computed from must be nowhere in the output.
        val recorded = session(
            listOf(
                TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, startMillis),
                TraceEvent.location(startMillis + 30_000L, 8f, 9.2f, distanceFromPreviousM = 41.0),
                TraceEvent.qualityDegraded(
                    startMillis + 94_517L,
                    LocationQualityBucket.GOOD,
                    LocationQualityBucket.POOR,
                ),
                TraceEvent.motion(MotionEventKind.EXITED_VEHICLE, startMillis + 140_000L),
            ),
        )

        // Act
        val encoded = json.encodeToString(recorded)

        // Assert — there is no field to hold one, and nothing smuggled one in.
        assertFalse(encoded.lowercase().contains("latitude"))
        assertFalse(encoded.lowercase().contains("longitude"))
        assertFalse(encoded.contains("\"lat\""))
        assertFalse(encoded.contains("\"lon\""))
        // The distance is the only thing derived from a position, and it is a scalar.
        assertTrue(encoded.contains("\"distanceFromPreviousM\":41.0"))
    }

    @Test
    fun `an absent optional field is omitted, not written as null`() {
        // Arrange — §9's example shows a vehicle_enter carrying no accuracy key at all.
        val event = TraceEvent.motion(MotionEventKind.ENTERED_VEHICLE, startMillis)

        // Act
        val encoded = json.encodeToString(event)

        // Assert
        assertFalse(encoded.contains("accuracy"))
        assertFalse(encoded.contains("null"))
        assertEquals("""{"type":"vehicle_enter","atMillis":$startMillis}""", encoded)
    }

    @Test
    fun `a recorded session round-trips at the declared schema version`() {
        // Arrange
        val original = session(
            listOf(
                TraceEvent.location(startMillis, 8f, 9.2f, 41.0),
                TraceEvent.qualityDegraded(
                    startMillis + 1_000L,
                    LocationQualityBucket.GOOD,
                    LocationQualityBucket.FAIR,
                ),
            ),
        ).copy(label = TraceLabel(TraceMode.CAR, parked = true, note = "지하 3층"))

        // Act
        val decoded = json.decodeFromString<TraceSession>(json.encodeToString(original))

        // Assert
        assertEquals(original, decoded)
        assertEquals(TraceSession.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals("android", decoded.platform)
    }

    @Test
    fun `a session with no label decodes as unknown and unparked`() {
        // Arrange — §9: the device cannot know the mode, so it must not invent one.
        val original = session(emptyList())

        // Act
        val decoded = json.decodeFromString<TraceSession>(json.encodeToString(original))

        // Assert
        assertEquals(TraceMode.UNKNOWN, decoded.label.mode)
        assertNull(decoded.label.parked)
        assertFalse(decoded.isLabelled)
    }

    @Test
    fun `quality buckets are drawn at their own thresholds, and invalid accuracy gets none`() {
        // Arrange — the edges, plus the value Fused Location uses to say "not a fix".

        // Act & Assert
        assertEquals(LocationQualityBucket.GOOD, LocationQualityBucket.of(20f))
        assertEquals(LocationQualityBucket.FAIR, LocationQualityBucket.of(20.1f))
        assertEquals(LocationQualityBucket.FAIR, LocationQualityBucket.of(35f))
        assertEquals(LocationQualityBucket.POOR, LocationQualityBucket.of(35.1f))
        // Not a quality level: an invalid fix must stay visible as a defect, not be
        // absorbed into a plausible-looking "poor".
        assertNull(LocationQualityBucket.of(-1f))
        assertTrue(LocationQualityBucket.POOR.isWorseThan(LocationQualityBucket.GOOD))
        assertFalse(LocationQualityBucket.GOOD.isWorseThan(LocationQualityBucket.POOR))
    }
}

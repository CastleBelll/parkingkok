package com.sjstudioz.parkingpin.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last hop before an event leaves the device.
 *
 * `AnalyticsPayloadTest` already holds the payload against docs/17 §3. This holds the
 * Firebase-shaped copy against it too, because a mapping is exactly where a value can
 * quietly change meaning — or disappear.
 */
class FirebaseAnalyticsParametersTest {

    private val occurredAt = 1_780_000_000_000L

    @Test
    fun `every parameter is a String or a Long — the two types Firebase keeps`() {
        // Arrange — one of each event, so every branch of the payload mapper is walked.
        val payloads = EventSamples.all.map { AnalyticsPayload.of(it, occurredAt) }

        // Act
        val values = payloads.flatMap { firebaseParameters(it).values }

        // Assert — a Boolean here would be dropped by the SDK without a word, turning a
        // reported flag into a missing column nobody notices until the data is needed.
        assertTrue(values.isNotEmpty())
        values.forEach { value ->
            assertTrue("unsupported parameter type ${value.javaClass}", value is String || value is Long)
        }
    }

    @Test
    fun `booleans travel as the same strings iOS sends`() {
        // Arrange — walkingEvidence true, gpsDegradation false (EventSamples.detection).
        val payload = AnalyticsPayload.of(AnalyticsEvent.ParkingCandidateCreated(EventSamples.detection), occurredAt)

        // Act
        val parameters = firebaseParameters(payload)

        // Assert — docs/05 parity: the two platforms must land in one comparable column.
        assertEquals("true", parameters[AnalyticsPayload.Key.WALKING_EVIDENCE])
        assertEquals("false", parameters[AnalyticsPayload.Key.GPS_DEGRADATION])
    }

    @Test
    fun `detectorVersion travels as a Long, not a string`() {
        // Arrange
        val payload = AnalyticsPayload.of(AnalyticsEvent.ParkingAutoEnd, occurredAt)

        // Act
        val version = firebaseParameters(payload)[AnalyticsPayload.Key.DETECTOR_VERSION]

        // Assert — docs/17 §4 compares rejection rates per version, which wants a number.
        assertEquals(DetectorVersion.CURRENT.toLong(), version)
    }

    @Test
    fun `the mapping invents no key and drops none`() {
        // Arrange
        val payloads = EventSamples.all.map { AnalyticsPayload.of(it, occurredAt) }

        // Act + Assert — one to one with the payload, and inside docs/17 §3's allowlist.
        payloads.forEach { payload ->
            val mapped = firebaseParameters(payload)
            assertEquals(payload.parameters.keys, mapped.keys)
            assertTrue(AnalyticsPayload.Key.ALL.containsAll(mapped.keys))
        }
    }

    @Test
    fun `occurredAt never becomes a parameter`() {
        // Arrange — the clock stamp is transport metadata, not a docs/17 §3 property.
        val payload = AnalyticsPayload.of(AnalyticsEvent.ParkingManualSaved, occurredAt)

        // Act
        val parameters = firebaseParameters(payload)

        // Assert
        assertTrue(parameters.values.none { it == occurredAt })
        assertTrue(parameters.keys.none { it.contains("time") || it.contains("occurred") })
    }
}

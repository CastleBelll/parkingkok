package kr.parkingpin.app.analytics

import kr.parkingpin.app.domain.parking.ConfidenceBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **docs/17 §2 and §3.** The fourteen names, the allowed properties, and the forbidden list
 * being unreachable.
 *
 * The key strings and bucket wire values asserted here are the cross-platform contract —
 * `ios/ParkingPinTests/AnalyticsTests.swift` asserts the same ones. If the two drift, the
 * platforms' numbers stop being addable (docs/17 §8).
 */
class AnalyticsEventContractTest {

    private val now = 1_780_000_000_000L

    /**
     * The names in docs/17 §2, transcribed here so a rename in production fails a test
     * rather than quietly redefining the contract.
     */
    private val documentedNames = setOf(
        "onboarding_completed",
        "permission_motion_result",
        "permission_location_level",
        "smart_detection_enabled",
        "parking_candidate_created",
        "parking_candidate_confirmed",
        "parking_candidate_rejected",
        "parking_manual_saved",
        "parking_auto_end",
        "widget_floor_changed",
        "paywall_viewed",
        "purchase_completed",
        "referral_shared",
        "referral_redeemed",
    )

    @Test
    fun `the event set is exactly docs 17 section 2's fourteen`() {
        assertEquals(documentedNames, AnalyticsEvent.ALL_NAMES)
        assertEquals(14, AnalyticsEvent.ALL_NAMES.size)
        assertEquals(documentedNames, EventSamples.all.map { it.name }.toSet())
    }

    @Test
    fun `every payload key is on docs 17 section 3's allowlist`() {
        EventSamples.all.forEach { event ->
            val payload = AnalyticsPayload.of(event, now)
            val unexpected = payload.parameters.keys - AnalyticsPayload.Key.ALL
            assertEquals("${payload.name} carried $unexpected", emptySet<String>(), unexpected)
        }
    }

    /**
     * docs/17 §3's forbidden list, plus the neighbouring fields docs/09 §11 names.
     *
     * The type system already makes these unreachable — [DetectionProperties] has no such
     * property and [AnalyticsPayload]'s constructor is private, so
     * `AnalyticsPayload("x", mapOf("latitude" to …), 0)` **does not compile**. This asserts
     * the mapper did not invent one anyway.
     *
     * Matched per `_`-separated word rather than by substring: `platform` contains "lat",
     * and a test that failed on that would have to be weakened to pass, which is how a
     * guard stops guarding.
     */
    @Test
    fun `no payload key names a forbidden field`() {
        val forbidden = setOf(
            "lat", "latitude", "lon", "lng", "longitude", "coordinate", "coordinates",
            "route", "path", "trace",
            "address", "place", "business",
            "floor", "spot", "zone", "memo", "photo",
        )
        EventSamples.all.forEach { event ->
            val payload = AnalyticsPayload.of(event, now)
            payload.parameters.keys.forEach { key ->
                val offending = key.split("_").toSet() intersect forbidden
                assertEquals("${payload.name} key $key names $offending", emptySet<String>(), offending)
                assertFalse("${payload.name} key $key is forbidden", key in forbidden)
            }
        }
    }

    /**
     * A latitude is a `Double`, and [AnalyticsValue] has no `Double` subtype — so this is a
     * statement about a type, verified on every value the contract can produce.
     */
    @Test
    fun `every parameter value is a bucket, a flag or a count — never a measurement`() {
        EventSamples.all.forEach { event ->
            val payload = AnalyticsPayload.of(event, now)
            payload.parameters.values.forEach { value ->
                when (value) {
                    // A bucket name or a contract enum, never a formatted number.
                    is AnalyticsValue.Text ->
                        assertNull("${payload.name} carried numeric text ${value.value}", value.value.toDoubleOrNull())
                    is AnalyticsValue.Count, is AnalyticsValue.Flag -> Unit
                }
            }
        }
    }

    @Test
    fun `every event carries platform`() {
        EventSamples.all.forEach { event ->
            val payload = AnalyticsPayload.of(event, now)
            assertEquals(
                AnalyticsValue.Text("android"),
                payload.parameters[AnalyticsPayload.Key.PLATFORM],
            )
        }
    }

    @Test
    fun `docs 17 section 4 - the detection family carries detectorVersion`() {
        val detectionEvents = listOf(
            AnalyticsEvent.ParkingCandidateCreated(EventSamples.detection),
            AnalyticsEvent.ParkingCandidateConfirmed(EventSamples.detection),
            AnalyticsEvent.ParkingCandidateRejected(EventSamples.detection),
            AnalyticsEvent.ParkingAutoEnd,
        )
        detectionEvents.forEach { event ->
            val payload = AnalyticsPayload.of(event, now)
            assertEquals(
                "${payload.name} lost detectorVersion",
                AnalyticsValue.Count(DetectorVersion.CURRENT),
                payload.parameters[AnalyticsPayload.Key.DETECTOR_VERSION],
            )
        }
    }

    @Test
    fun `a candidate event carries exactly docs 17 section 3's detection properties`() {
        // Act
        val payload = AnalyticsPayload.of(AnalyticsEvent.ParkingCandidateCreated(EventSamples.detection), now)

        // Assert — the exact cross-platform shape, key for key and value for value.
        assertEquals(
            mapOf(
                "platform" to AnalyticsValue.Text("android"),
                "confidence_bucket" to AnalyticsValue.Text("medium"),
                "drive_duration_bucket" to AnalyticsValue.Text("min_5_15"),
                "distance_bucket" to AnalyticsValue.Text("km_1_5"),
                "accuracy_bucket" to AnalyticsValue.Text("fair"),
                "walking_evidence" to AnalyticsValue.Flag(true),
                "gps_degradation" to AnalyticsValue.Flag(false),
                "optional_vehicle_signal" to AnalyticsValue.Flag(true),
                "detector_version" to AnalyticsValue.Count(1),
            ),
            payload.parameters,
        )
    }

    @Test
    fun `an unknown bucket is omitted rather than defaulted`() {
        // Arrange — a candidate whose trip length and accuracy are not known.
        val sparse = DetectionProperties(
            confidenceBucket = ConfidenceBucket.LOW,
            driveDurationBucket = null,
            distanceBucket = null,
            accuracyBucket = null,
            walkingEvidence = false,
            gpsDegradation = true,
            optionalVehicleSignal = false,
        )

        // Act
        val payload = AnalyticsPayload.of(AnalyticsEvent.ParkingCandidateRejected(sparse), now)

        // Assert — an absent property is honest; a defaulted bucket would invent a trip.
        assertFalse(AnalyticsPayload.Key.DRIVE_DURATION_BUCKET in payload.parameters)
        assertFalse(AnalyticsPayload.Key.DISTANCE_BUCKET in payload.parameters)
        assertFalse(AnalyticsPayload.Key.ACCURACY_BUCKET in payload.parameters)
        assertEquals(
            AnalyticsValue.Text("low"),
            payload.parameters[AnalyticsPayload.Key.CONFIDENCE_BUCKET],
        )
    }

    @Test
    fun `smart_detection_enabled reports the direction of the change`() {
        assertEquals(
            AnalyticsValue.Flag(true),
            AnalyticsPayload.of(AnalyticsEvent.SmartDetectionEnabled(true), now)
                .parameters[AnalyticsPayload.Key.ENABLED],
        )
        assertEquals(
            AnalyticsValue.Flag(false),
            AnalyticsPayload.of(AnalyticsEvent.SmartDetectionEnabled(false), now)
                .parameters[AnalyticsPayload.Key.ENABLED],
        )
    }

    @Test
    fun `parking_manual_saved says only which platform saved`() {
        val payload = AnalyticsPayload.of(AnalyticsEvent.ParkingManualSaved, now)
        assertEquals(mapOf("platform" to AnalyticsValue.Text("android")), payload.parameters)
    }

    @Test
    fun `the debug summary is stable and sorted`() {
        val payload = AnalyticsPayload.of(AnalyticsEvent.SmartDetectionEnabled(true), now)
        assertEquals("smart_detection_enabled {enabled=true, platform=android}", payload.debugSummary)
    }

    @Test
    fun `a payload equals another built from the same event at the same instant`() {
        val first = AnalyticsPayload.of(AnalyticsEvent.PaywallViewed, now)
        val second = AnalyticsPayload.of(AnalyticsEvent.PaywallViewed, now)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first != AnalyticsPayload.of(AnalyticsEvent.PaywallViewed, now + 1))
    }
}

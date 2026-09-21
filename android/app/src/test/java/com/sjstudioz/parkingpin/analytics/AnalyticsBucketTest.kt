package com.sjstudioz.parkingpin.analytics

import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import com.sjstudioz.parkingpin.domain.trace.LocationQualityBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bands the contract reports instead of measurements. */
class AnalyticsBucketTest {

    @Test
    fun `drive duration bands`() {
        val cases = listOf(
            0L to DriveDurationBucket.UNDER_5_MIN,
            299_999L to DriveDurationBucket.UNDER_5_MIN,
            300_000L to DriveDurationBucket.MIN_5_15,
            899_999L to DriveDurationBucket.MIN_5_15,
            900_000L to DriveDurationBucket.MIN_15_45,
            2_699_999L to DriveDurationBucket.MIN_15_45,
            2_700_000L to DriveDurationBucket.OVER_45_MIN,
            86_400_000L to DriveDurationBucket.OVER_45_MIN,
        )
        cases.forEach { (millis, expected) ->
            assertEquals("at $millis ms", expected, DriveDurationBucket.of(millis))
        }
    }

    @Test
    fun `a negative duration gets no band, so a backwards clock stays visible`() {
        assertNull(DriveDurationBucket.of(-1))
    }

    @Test
    fun `distance bands`() {
        val cases = listOf(
            0.0 to DistanceBucket.UNDER_1_KM,
            999.0 to DistanceBucket.UNDER_1_KM,
            1_000.0 to DistanceBucket.KM_1_5,
            4_999.0 to DistanceBucket.KM_1_5,
            5_000.0 to DistanceBucket.KM_5_20,
            19_999.0 to DistanceBucket.KM_5_20,
            20_000.0 to DistanceBucket.OVER_20_KM,
        )
        cases.forEach { (meters, expected) ->
            assertEquals("at $meters m", expected, DistanceBucket.of(meters))
        }
    }

    @Test
    fun `a negative distance gets no band`() {
        assertNull(DistanceBucket.of(-0.5))
    }

    /**
     * The two platforms have to agree on these strings or the numbers cannot be added up.
     * `ios/ParkingPinTests/AnalyticsTests.swift` asserts the same values.
     */
    @Test
    fun `bucket wire values are the cross-platform ones`() {
        assertEquals(
            listOf("under_5_min", "min_5_15", "min_15_45", "over_45_min"),
            DriveDurationBucket.entries.map { it.wireValue },
        )
        assertEquals(
            listOf("under_1_km", "km_1_5", "km_5_20", "over_20_km"),
            DistanceBucket.entries.map { it.wireValue },
        )
        // These two are reused from the parking and trace domains, where the wire form is
        // the lowercased name — which is how the payload mapper emits them.
        assertEquals(
            listOf("low", "medium", "high"),
            ConfidenceBucket.entries.map { it.name.lowercase() },
        )
        assertEquals(
            listOf("good", "fair", "poor"),
            LocationQualityBucket.entries.map { it.name.lowercase() },
        )
        assertEquals(
            listOf("granted", "denied", "restricted", "not_determined"),
            MotionPermissionResult.entries.map { it.wireValue },
        )
        assertEquals(
            listOf("always", "when_in_use", "denied", "restricted", "not_determined"),
            LocationPermissionLevel.entries.map { it.wireValue },
        )
        assertEquals(listOf("up", "down"), WidgetFloorDirection.entries.map { it.wireValue })
        assertEquals(listOf("app_store", "play_store"), PurchaseStore.entries.map { it.wireValue })
    }

    @Test
    fun `the detector version is the one iOS reports`() {
        // docs/17 §8 compares the two platforms per detector version; a drift here makes
        // the comparison meaningless.
        assertEquals(1, DetectorVersion.CURRENT)
    }
}

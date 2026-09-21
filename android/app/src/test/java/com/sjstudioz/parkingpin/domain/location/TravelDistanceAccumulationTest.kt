package com.sjstudioz.parkingpin.domain.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * docs/05_PARKING_DETECTION_ENGINE.md §7, the `distance >= 800m` clause.
 *
 * The clause used to accumulate only from fixes the §6 reliability bar admitted — 35 m or
 * better — which is that section's threshold in the wrong place: it chooses a parking spot
 * worth remembering, it does not measure how far something went. Measured underground
 * accuracy ran from 100 m to 2620 m, so underground the clause accumulated almost nothing.
 * iOS had the opposite defect, accumulating every step the §5 speed cap let through.
 *
 * The unified rule holds an anchor and adds a leg only when its displacement clears the
 * pair's combined error — the same 2σ floor [MovementEvidencePolicy] uses. The tests below
 * are that rule's four moving parts: refuse and keep the anchor, clear and advance it,
 * reach the floor eventually on slow travel, and never let a jump through.
 *
 * `ParkingPinTests/DrivingDistanceAccumulationTests` asserts the same numbers on iOS.
 */
class TravelDistanceAccumulationTest {

    private val start = 1_700_000_000_000L

    /** A session with vehicle evidence already in hand; only the fixes are under test. */
    private fun session() = DrivingSessionEvidence(
        vehicleFirstSeenAtMillis = start,
        lastVehicleEvidenceAtMillis = start,
    )

    private fun DrivingSessionEvidence.recording(
        atSecond: Long,
        metersNorth: Double,
        accuracyM: Float = 10f,
    ) = recordingFix(
        TestGeo.sample(
            atMillis = start + atSecond * 1_000L,
            metersNorth = metersNorth,
            accuracyM = accuracyM,
        ),
    )

    @Test
    fun `the first fix of a session contributes nothing and becomes the anchor`() {
        // Arrange / Act — the fix before it belongs to the previous trip.
        val evidence = session().recording(atSecond = 0, metersNorth = 0.0)

        // Assert
        assertEquals(0.0, evidence.travelDistanceMeters, 0.001)
        assertEquals(0, evidence.distanceNoiseFloorRejectCount)
    }

    @Test
    fun `a leg inside the combined error is not accumulated and the anchor is kept`() {
        // Arrange — two 10 m fixes put the floor at 2·sqrt(10² + 10²) ≈ 28.28 m. The
        // second fix is 10 m out, the third 29 m out from the *first*. Had the refusal
        // advanced the anchor, the third leg would measure 19 m and be refused too.
        val evidence = session()
            .recording(atSecond = 0, metersNorth = 0.0)
            .recording(atSecond = 10, metersNorth = 10.0)

        // Act
        val afterRefusal = evidence
        val afterClearing = evidence.recording(atSecond = 20, metersNorth = 29.0)

        // Assert
        assertEquals(0.0, afterRefusal.travelDistanceMeters, 0.001)
        assertEquals(1, afterRefusal.distanceNoiseFloorRejectCount)
        assertEquals(29.0, afterClearing.travelDistanceMeters, 0.01)
        assertEquals(1, afterClearing.distanceNoiseFloorRejectCount)
    }

    @Test
    fun `clearing the floor advances the anchor, so the same metres are never counted twice`() {
        // Arrange — 40 m then 80 m, each leg 40 m long and each clearing the 28.28 m floor.
        val evidence = session()
            .recording(atSecond = 0, metersNorth = 0.0)
            .recording(atSecond = 10, metersNorth = 40.0)
            .recording(atSecond = 20, metersNorth = 80.0)

        // Assert — 80 m, not 40 + 80 = 120 m, which is what a held anchor would have given.
        assertEquals(80.0, evidence.travelDistanceMeters, 0.01)
        assertEquals(0, evidence.distanceNoiseFloorRejectCount)
    }

    @Test
    fun `slow travel accumulates once the held anchor is far enough away`() {
        // Arrange — 2 m/s at 1 Hz. No single leg ever clears the 28.28 m floor, which is
        // exactly the case a consecutive-pair rule could never decide.
        var evidence = session()
        for (second in 0L..15L) {
            evidence = evidence.recording(atSecond = second, metersNorth = second * 2.0)
        }

        // Assert — the 15th second is 30 m from the anchor, so one leg lands; the 14 fixes
        // before it were refused and kept the anchor in place.
        assertEquals(30.0, evidence.travelDistanceMeters, 0.01)
        assertEquals(14, evidence.distanceNoiseFloorRejectCount)
    }

    @Test
    fun `a coarse pair whose displacement clearly exceeds its own error still accumulates`() {
        // Arrange — the underground case the ≤35 m gate made impossible: two fixes accurate
        // to 500 m, 2000 m apart. The floor is 2·sqrt(500² + 500²) ≈ 1414 m.
        val evidence = session()
            .recording(atSecond = 0, metersNorth = 0.0, accuracyM = 500f)
            .recording(atSecond = 60, metersNorth = 2_000.0, accuracyM = 500f)

        // Assert
        assertEquals(2_000.0, evidence.travelDistanceMeters, 1.0)
        assertEquals(0, evidence.distanceNoiseFloorRejectCount)
    }

    @Test
    fun `jitter that the section 5 speed cap lets through is refused by the noise floor`() {
        // Arrange — docs/05 §7's worked example: two fixes accurate to 1000 m, 900 m apart
        // in 60 s. That is 15 m/s, well under the 90 m/s outlier cap, and it is noise.
        val evidence = session()
            .recording(atSecond = 0, metersNorth = 0.0, accuracyM = 1_000f)
            .recording(atSecond = 60, metersNorth = 900.0, accuracyM = 1_000f)

        // Assert — the floor is 2·sqrt(2)·1000 ≈ 2828 m, so nothing accumulates.
        assertEquals(0.0, evidence.travelDistanceMeters, 0.001)
        assertEquals(1, evidence.distanceNoiseFloorRejectCount)
    }

    @Test
    fun `an implausible jump is not distance, and it does not disturb the anchor`() {
        // Arrange — §5's outlier cap stays in front of the noise floor. 10 km in 1 s is a
        // teleport; the fix after it is ordinary travel measured from the original anchor.
        val evidence = session()
            .recording(atSecond = 0, metersNorth = 0.0)
            .recording(atSecond = 1, metersNorth = 10_000.0)

        // Act
        val afterOrdinaryFix = evidence.recording(atSecond = 11, metersNorth = 40.0)

        // Assert
        assertEquals(0.0, evidence.travelDistanceMeters, 0.001)
        assertEquals(1, evidence.movement.outlierCount)
        assertEquals(0, evidence.distanceNoiseFloorRejectCount)
        assertEquals(40.0, afterOrdinaryFix.travelDistanceMeters, 0.01)
    }

    @Test
    fun `an invalid fix is not a leg at all`() {
        // Arrange — docs/05 §5: a negative accuracy is Fused Location saying "not a fix".
        val evidence = session()
            .recording(atSecond = 0, metersNorth = 0.0)
            .recording(atSecond = 10, metersNorth = 4_000.0, accuracyM = -1f)

        // Assert — neither accumulated nor counted against the noise floor.
        assertEquals(0.0, evidence.travelDistanceMeters, 0.001)
        assertEquals(0, evidence.distanceNoiseFloorRejectCount)
    }
}

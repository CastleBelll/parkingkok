package com.parkingkok.app.domain.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The driving confirmation guard from docs/05_PARKING_DETECTION_ENGINE.md §7.
 *
 * The rule the tests exist to protect is "one event alone never confirms a full driving
 * session" — which is why the movement clause is exercised on its own.
 */
class DrivingConfirmationGuardTest {

    private val now = 1_700_000_000_000L

    private fun evidence(
        firstSeenAgoMillis: Long = 0L,
        lastEvidenceAgoMillis: Long = 0L,
        distanceMeters: Double = 0.0,
        reliableSamples: Int = 0,
        maxSpeedMps: Float? = null,
    ) = DrivingSessionEvidence(
        vehicleFirstSeenAtMillis = now - firstSeenAgoMillis,
        lastVehicleEvidenceAtMillis = now - lastEvidenceAgoMillis,
        travelDistanceMeters = distanceMeters,
        reliableSampleCount = reliableSamples,
        maxSpeedMps = maxSpeedMps,
    )

    @Test
    fun `duration plus movement confirms`() {
        // Arrange — over 120s, with fixes showing real displacement.
        val driven = evidence(
            firstSeenAgoMillis = 130_000L,
            distanceMeters = 900.0,
            reliableSamples = 6,
            maxSpeedMps = 14f,
        )

        // Act
        val result = DrivingConfirmationGuard.evaluate(driven, now)

        // Assert
        assertTrue(result.confirmed)
        assertTrue(result.reasonCodes.contains(DrivingReasonCode.RECENT_VEHICLE_ACTIVITY))
        assertTrue(result.reasonCodes.contains(DrivingReasonCode.VEHICLE_DURATION_MET))
        assertTrue(result.reasonCodes.contains(DrivingReasonCode.VEHICLE_DISTANCE_MET))
    }

    @Test
    fun `distance alone confirms a short fast trip that misses the duration bar`() {
        // Arrange — 60s but 900m: §7 is an OR between duration and distance.
        val quick = evidence(
            firstSeenAgoMillis = 60_000L,
            distanceMeters = 900.0,
            reliableSamples = 4,
            maxSpeedMps = 15f,
        )

        // Act & Assert
        assertTrue(DrivingConfirmationGuard.evaluate(quick, now).confirmed)
    }

    @Test
    fun `a phone in a parked car does not confirm on duration alone`() {
        // Arrange — this is the case the movement clause exists for. An IN_VEHICLE
        // transition plus two minutes of sitting still satisfies duration, and without
        // the movement conjunct it would confirm a drive that never happened.
        val stationary = evidence(
            firstSeenAgoMillis = 600_000L,
            distanceMeters = 4.0,
            reliableSamples = 8,
            maxSpeedMps = 0.3f,
        )

        // Act
        val result = DrivingConfirmationGuard.evaluate(stationary, now)

        // Assert
        assertFalse(result.confirmed)
        assertTrue(result.reasonCodes.contains(DrivingReasonCode.VEHICLE_DURATION_MET))
    }

    @Test
    fun `a single fix cannot evidence movement`() {
        // Arrange — one fix is a guess about a position, not evidence of displacement.
        val singleFix = evidence(
            firstSeenAgoMillis = 200_000L,
            distanceMeters = 5_000.0,
            reliableSamples = 1,
            maxSpeedMps = 25f,
        )

        // Act & Assert
        assertFalse(DrivingConfirmationGuard.evaluate(singleFix, now).confirmed)
    }

    @Test
    fun `stale vehicle evidence does not confirm however long the session ran`() {
        // Arrange — the vehicle signal is an hour old; whatever it described is over.
        val stale = evidence(
            firstSeenAgoMillis = 3_600_000L,
            lastEvidenceAgoMillis = 3_500_000L,
            distanceMeters = 9_000.0,
            reliableSamples = 40,
            maxSpeedMps = 22f,
        )

        // Act
        val result = DrivingConfirmationGuard.evaluate(stale, now)

        // Assert
        assertFalse(result.confirmed)
        assertFalse(result.reasonCodes.contains(DrivingReasonCode.RECENT_VEHICLE_ACTIVITY))
    }

    @Test
    fun `vehicle speed substitutes for distance when few fixes landed`() {
        // Arrange — a tunnel or an urban canyon costs fixes, not the drive itself.
        val sparse = evidence(
            firstSeenAgoMillis = 200_000L,
            distanceMeters = 40.0,
            reliableSamples = 2,
            maxSpeedMps = DrivingConfirmationGuard.MIN_VEHICLE_SPEED_MPS,
        )

        // Act & Assert
        assertTrue(DrivingConfirmationGuard.evaluate(sparse, now).confirmed)
    }
}

package com.parkingpin.app.domain.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The driving confirmation guard from docs/05_PARKING_DETECTION_ENGINE.md §7.
 *
 * The rule the tests exist to protect is "one event alone never confirms a full driving
 * session" — which is why the movement clause is exercised on its own. Since the
 * 2026-09-18 unification the clause is a **count of moving samples**, not a session
 * distance total: what that count is made of belongs to [MovementEvidenceTest].
 */
class DrivingConfirmationGuardTest {

    private val now = 1_700_000_000_000L

    private fun evidence(
        firstSeenAgoMillis: Long = 0L,
        lastEvidenceAgoMillis: Long = 0L,
        distanceMeters: Double = 0.0,
        movingSamples: Int = 0,
    ) = DrivingSessionEvidence(
        vehicleFirstSeenAtMillis = now - firstSeenAgoMillis,
        lastVehicleEvidenceAtMillis = now - lastEvidenceAgoMillis,
        travelDistanceMeters = distanceMeters,
        movement = MovementEvidence(movingSampleCount = movingSamples),
    )

    @Test
    fun `duration plus movement confirms`() {
        // Arrange — over 120s, with fixes showing real displacement.
        val driven = evidence(
            firstSeenAgoMillis = 130_000L,
            distanceMeters = 900.0,
            movingSamples = 6,
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
            movingSamples = 4,
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
            movingSamples = 0,
        )

        // Act
        val result = DrivingConfirmationGuard.evaluate(stationary, now)

        // Assert
        assertFalse(result.confirmed)
        assertTrue(result.reasonCodes.contains(DrivingReasonCode.VEHICLE_DURATION_MET))
    }

    @Test
    fun `one moving sample is not enough`() {
        // Arrange — "one event alone never confirms", made concrete. A single decided
        // pair is a guess about a moment, not a travelling session.
        val single = evidence(
            firstSeenAgoMillis = 200_000L,
            distanceMeters = 5_000.0,
            movingSamples = 1,
        )

        // Act & Assert
        assertFalse(DrivingConfirmationGuard.evaluate(single, now).confirmed)
        assertTrue(DrivingConfirmationGuard.evaluate(single.copy(movement = MovementEvidence(2)), now).confirmed)
    }

    @Test
    fun `a huge accumulated distance is not movement evidence by itself`() {
        // Arrange — the defect the unification removed. A session total cannot tell
        // "800 m travelled steadily" from "800 m of jitter added up", so distance alone
        // must never satisfy the movement conjunct however large it grows.
        val jittery = evidence(
            firstSeenAgoMillis = 600_000L,
            distanceMeters = 9_000.0,
            movingSamples = 0,
        )

        // Act & Assert
        assertFalse(DrivingConfirmationGuard.evaluate(jittery, now).confirmed)
    }

    @Test
    fun `stale vehicle evidence does not confirm however long the session ran`() {
        // Arrange — the vehicle signal is an hour old; whatever it described is over.
        val stale = evidence(
            firstSeenAgoMillis = 3_600_000L,
            lastEvidenceAgoMillis = 3_500_000L,
            distanceMeters = 9_000.0,
            movingSamples = 40,
        )

        // Act
        val result = DrivingConfirmationGuard.evaluate(stale, now)

        // Assert
        assertFalse(result.confirmed)
        assertFalse(result.reasonCodes.contains(DrivingReasonCode.RECENT_VEHICLE_ACTIVITY))
    }

    @Test
    fun `vehicle evidence stays recent right up to the five minute horizon`() {
        // Arrange — 300s is the unified window (docs/05 §7): measured underground
        // transition gaps ran to minutes, so the tighter horizon lost whole journeys.
        val atHorizon = evidence(
            firstSeenAgoMillis = 600_000L,
            lastEvidenceAgoMillis = DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS,
            distanceMeters = 900.0,
            movingSamples = 3,
        )
        val justPast = atHorizon.copy(
            lastVehicleEvidenceAtMillis = atHorizon.lastVehicleEvidenceAtMillis - 1L,
        )

        // Act & Assert
        assertTrue(DrivingConfirmationGuard.evaluate(atHorizon, now).confirmed)
        assertFalse(DrivingConfirmationGuard.evaluate(justPast, now).confirmed)
    }
}

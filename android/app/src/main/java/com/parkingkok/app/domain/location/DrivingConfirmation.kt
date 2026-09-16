package com.parkingkok.app.domain.location

import kotlinx.serialization.Serializable

/**
 * What a driving session has accumulated so far, in the terms §7's guard is written in.
 *
 * Coordinate-free on purpose: distance arrives already reduced to metres, so the guard
 * itself can never hold a position.
 */
@Serializable
data class DrivingSessionEvidence(
    /** When vehicle evidence first appeared in this session. */
    val vehicleFirstSeenAtMillis: Long,
    /** Most recent vehicle evidence — a transition, or a fix at vehicle-plausible speed. */
    val lastVehicleEvidenceAtMillis: Long,
    /** Metres accumulated between successive reliable fixes. */
    val travelDistanceMeters: Double = 0.0,
    /** Reliable fixes admitted during the session. */
    val reliableSampleCount: Int = 0,
    /** Fastest speed observed, when the provider reported one. */
    val maxSpeedMps: Float? = null,
)

/** Why the guard did or did not confirm. Stable strings from docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §4. */
enum class DrivingReasonCode(val wire: String) {
    RECENT_VEHICLE_ACTIVITY("recent_vehicle_activity"),
    VEHICLE_DURATION_MET("vehicle_duration_met"),
    VEHICLE_DISTANCE_MET("vehicle_distance_met"),
}

/** Outcome of the §7 guard, with the reason codes that justified it. */
data class DrivingConfirmation(
    val confirmed: Boolean,
    val reasonCodes: List<DrivingReasonCode>,
)

/**
 * The driving-confirmation guard from docs/05_PARKING_DETECTION_ENGINE.md §7.
 *
 * ```
 * recent vehicle evidence
 *   AND (duration >= 120s OR distance >= 800m)
 *   AND movement evidence consistent with travel
 * ```
 *
 * "One event alone never confirms a full driving session" is the point of the whole rule,
 * so the movement clause is a separate conjunct rather than something the duration clause
 * implies: a phone sitting in a parked car can satisfy the 120 s duration on an
 * IN_VEHICLE transition alone, and without the movement clause that would confirm a drive
 * that never happened.
 *
 * This guard does **not** move the state machine. Transitions, candidate creation, and
 * confidence scoring are M3 (docs/05 §16). M0B-2 uses the verdict only to decide whether
 * to keep the bounded capture running and to surface it in diagnostics.
 *
 * Every threshold below is a field-tuning starting point (§8, §18).
 */
object DrivingConfirmationGuard {

    /** §7 initial conceptual guard. */
    const val MIN_DURATION_MILLIS: Long = 120_000L
    const val MIN_DISTANCE_METERS: Double = 800.0

    /** Vehicle evidence older than this no longer describes the current situation. */
    const val RECENT_VEHICLE_WINDOW_MILLIS: Long = 300_000L

    /** Two fixes are the minimum that can evidence displacement rather than a single guess. */
    const val MIN_RELIABLE_SAMPLES_FOR_MOVEMENT: Int = 2

    /** Metres of displacement that rule out a stationary phone with a drifting fix. */
    const val MIN_MOVEMENT_METERS: Double = 150.0

    /** ~29 km/h. Above walking or fix drift, below the point of excluding city traffic. */
    const val MIN_VEHICLE_SPEED_MPS: Float = 8f

    fun evaluate(evidence: DrivingSessionEvidence, nowMillis: Long): DrivingConfirmation {
        val reasons = mutableListOf<DrivingReasonCode>()

        val hasRecentVehicleEvidence =
            nowMillis - evidence.lastVehicleEvidenceAtMillis <= RECENT_VEHICLE_WINDOW_MILLIS &&
                nowMillis >= evidence.lastVehicleEvidenceAtMillis
        if (hasRecentVehicleEvidence) reasons += DrivingReasonCode.RECENT_VEHICLE_ACTIVITY

        val durationMet =
            nowMillis - evidence.vehicleFirstSeenAtMillis >= MIN_DURATION_MILLIS
        if (durationMet) reasons += DrivingReasonCode.VEHICLE_DURATION_MET

        val distanceMet = evidence.travelDistanceMeters >= MIN_DISTANCE_METERS
        if (distanceMet) reasons += DrivingReasonCode.VEHICLE_DISTANCE_MET

        val confirmed = hasRecentVehicleEvidence &&
            (durationMet || distanceMet) &&
            hasMovementEvidence(evidence)

        return DrivingConfirmation(confirmed = confirmed, reasonCodes = reasons.toList())
    }

    private fun hasMovementEvidence(evidence: DrivingSessionEvidence): Boolean {
        if (evidence.reliableSampleCount < MIN_RELIABLE_SAMPLES_FOR_MOVEMENT) return false
        val speed = evidence.maxSpeedMps
        return evidence.travelDistanceMeters >= MIN_MOVEMENT_METERS ||
            (speed != null && speed >= MIN_VEHICLE_SPEED_MPS)
    }
}

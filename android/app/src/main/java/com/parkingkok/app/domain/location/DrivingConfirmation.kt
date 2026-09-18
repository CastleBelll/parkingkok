package com.parkingkok.app.domain.location

import kotlinx.serialization.Serializable

/**
 * What a driving session has accumulated so far, in the terms §7's guard is written in.
 *
 * Every field the guard reads is a scalar: metres, counts, a speed. The one position in
 * here lives inside [movement], which is the only clause that needs two points to say
 * anything at all — see [MovementAnchor].
 */
@Serializable
data class DrivingSessionEvidence(
    /** When vehicle evidence first appeared in this session. */
    val vehicleFirstSeenAtMillis: Long,
    /** Most recent vehicle evidence — a transition, or a fix at vehicle-plausible speed. */
    val lastVehicleEvidenceAtMillis: Long,
    /**
     * Metres accumulated between successive reliable fixes.
     *
     * This feeds §7's `distance >= 800m` clause and nothing else. It used to double as the
     * movement clause; the 2026-09-18 unification separated the two roles, because a sum
     * cannot tell steady travel from accumulated jitter (see [MovementEvidence]).
     */
    val travelDistanceMeters: Double = 0.0,
    /** Reliable fixes admitted during the session. */
    val reliableSampleCount: Int = 0,
    /** Fastest speed observed, when the provider reported one. */
    val maxSpeedMps: Float? = null,
    /** §7's "movement evidence consistent with travel", counted per pair of fixes. */
    val movement: MovementEvidence = MovementEvidence(),
) {

    /**
     * Folds one delivered fix into the movement clause and the observed-speed ceiling.
     *
     * Deliberately takes *every* fix, not only the ones [ReliableLocationSelector] admits:
     * §6's 35 m bar chooses a parking spot worth remembering, and applying it here would
     * make driving confirmation impossible underground (docs/05 §7).
     */
    fun recordingFix(sample: LocationSample): DrivingSessionEvidence = copy(
        maxSpeedMps = maxOfNullable(maxSpeedMps, sample.speedMps.takeIf { sample.quality.isValid }),
        movement = movement.recording(sample),
    )

    private fun maxOfNullable(current: Float?, candidate: Float?): Float? = when {
        candidate == null -> current
        current == null -> candidate
        else -> maxOf(current, candidate)
    }
}

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

    /**
     * Vehicle evidence older than this no longer describes the current situation.
     *
     * 300 s rather than the tighter figure iOS started from: measured Activity transition
     * gaps underground ran to minutes, and the cost of missing a whole journey is larger
     * than the cost of one wrongly-open timeout window (docs/05 §7).
     */
    const val RECENT_VEHICLE_WINDOW_MILLIS: Long = 300_000L

    /**
     * "One event alone never confirms" made concrete: a single fix can never satisfy the
     * movement requirement.
     */
    const val MIN_MOVING_SAMPLES: Int = 2

    /**
     * ~7.2 km/h — above brisk walking, below any real traffic speed.
     *
     * Not a "is this a vehicle" bar: that question is already answered by the recent
     * vehicle evidence conjunct. This one asks only whether the device really moved, so a
     * higher threshold would reject the car crawling through a car park looking for a
     * space — which is the exact moment this product exists to catch.
     */
    const val MOVING_SPEED_THRESHOLD_MPS: Double = 2.0

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
            evidence.movement.movingSampleCount >= MIN_MOVING_SAMPLES

        return DrivingConfirmation(confirmed = confirmed, reasonCodes = reasons.toList())
    }
}

package com.sjstudioz.parkingpin.domain.location

import kotlinx.serialization.Serializable

/**
 * What a driving session has accumulated so far, in the terms §7's guard is written in.
 *
 * Every field the guard reads is a scalar: metres, counts, a speed. The positions in here
 * are anchors, and they exist because both of §7's measured clauses ask about a
 * **displacement**, which needs two points — see [MovementAnchor]. Nothing derived from
 * them leaves as anything but metres.
 */
@Serializable
data class DrivingSessionEvidence(
    /** When vehicle evidence first appeared in this session. */
    val vehicleFirstSeenAtMillis: Long,
    /** Most recent vehicle evidence — a transition, or a fix at vehicle-plausible speed. */
    val lastVehicleEvidenceAtMillis: Long,
    /**
     * Metres accumulated for §7's `distance >= 800m` clause, one anchored leg at a time.
     *
     * This feeds that clause and nothing else. It used to double as the movement clause;
     * the 2026-09-18 unification separated the two roles, because a sum cannot tell steady
     * travel from accumulated jitter (see [MovementEvidence]). It used to be fed only by
     * fixes the §6 reliability bar admitted, which is that same section's threshold in the
     * wrong place a second time: 35 m chooses a parking spot worth remembering, and
     * underground — where accuracy ran from 100 m to 2620 m — it accumulated almost
     * nothing. See [accumulatingDistance] for what replaced it.
     */
    val travelDistanceMeters: Double = 0.0,
    /**
     * Legs the noise floor kept out of [travelDistanceMeters].
     *
     * Instrumented rather than silently dropped: a session that accumulates nothing
     * underground and one that never moved look identical from outside, and this is the
     * field that separates them. A count far above the accepted legs says the floor is
     * wrong for this device, not that the car stood still.
     */
    val distanceNoiseFloorRejectCount: Int = 0,
    /**
     * The fix [travelDistanceMeters] measures its next leg from.
     *
     * A second anchor rather than a reuse of [MovementEvidence.anchor], because §7 asks
     * the two clauses different questions: distance has no baseline bounds and no speed
     * gate, only the noise floor they share.
     */
    val distanceAnchor: MovementAnchor? = null,
    /** Reliable fixes admitted during the session. */
    val reliableSampleCount: Int = 0,
    /** Fastest speed observed, when the provider reported one. */
    val maxSpeedMps: Float? = null,
    /** §7's "movement evidence consistent with travel", counted per pair of fixes. */
    val movement: MovementEvidence = MovementEvidence(),
) {

    /**
     * Folds one delivered fix into the movement clause, the distance clause and the
     * observed-speed ceiling.
     *
     * Deliberately takes *every* fix, not only the ones [ReliableLocationSelector] admits:
     * §6's 35 m bar chooses a parking spot worth remembering, and applying it to either
     * measured clause makes driving confirmation impossible underground (docs/05 §7).
     */
    fun recordingFix(sample: LocationSample): DrivingSessionEvidence {
        // Asked before the fold, because the answer is about the fix this one follows.
        val admitted = movement.admits(sample)
        val folded = copy(
            maxSpeedMps = maxOfNullable(maxSpeedMps, sample.speedMps.takeIf { sample.quality.isValid }),
            movement = movement.recording(sample),
        )
        return if (admitted) folded.accumulatingDistance(sample) else folded
    }

    /**
     * docs/05 §7 `distance >= 800m`, under the same noise floor as movement evidence.
     *
     * A leg is added only when its displacement clears the combined positional uncertainty
     * of the two fixes. Failing keeps the anchor, exactly as the movement clause does, so
     * slow travel still accumulates — one leg later, measured from further back. Clearing
     * it advances the anchor, which is what stops the same metres being counted twice.
     *
     * The baseline bounds and the speed gate stay out of this deliberately. Those ask
     * whether a leg looks like travel, which is the movement clause's question; this one
     * only asks how far.
     */
    private fun accumulatingDistance(sample: LocationSample): DrivingSessionEvidence {
        // The first fix of a session has nothing to measure from: the one before it
        // belongs to the previous trip, and counting that gap would credit this drive with
        // the whole distance since the last parking spot.
        val anchor = distanceAnchor ?: return copy(distanceAnchor = MovementAnchor.of(sample))
        val displacement =
            GeoDistance.meters(anchor.latitude, anchor.longitude, sample.latitude, sample.longitude)
        if (displacement < MovementEvidencePolicy.noiseFloorMeters(anchor, sample)) {
            return copy(distanceNoiseFloorRejectCount = distanceNoiseFloorRejectCount + 1)
        }
        return copy(
            travelDistanceMeters = travelDistanceMeters + displacement,
            distanceAnchor = MovementAnchor.of(sample),
        )
    }

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

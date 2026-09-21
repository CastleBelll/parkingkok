package com.parkingpin.app.domain.detection

import com.parkingpin.app.domain.location.DrivingConfirmationGuard
import com.parkingpin.app.domain.parking.ConfidenceBucket

/**
 * docs/05_PARKING_DETECTION_ENGINE.md §8 evidence weights and §9 confidence buckets.
 *
 * The score is internal; the bucket is the external contract
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §5), which is why nothing outside this file
 * sees the integer except diagnostics.
 *
 * ### How a weight is earned
 * Every positive weight is keyed to a §4 reason code the engine has already accumulated,
 * with one exception noted below. That is deliberate: §3a says reason codes accumulate as
 * evidence arrives and are never recomputed at the end from the final state, so scoring
 * from the accumulated codes is scoring from the evidence, in arrival order, rather than
 * re-deriving it from a snapshot.
 *
 * The exception is `route duration/distance comfortably over minimum`, which is a
 * statement about the §7 minimums rather than about one piece of evidence, so it takes the
 * session's duration and distance directly.
 *
 * ### Every number here is a field-tuning starting point
 * §8 says so in its own title and §18 adds that no above-ground car drive has been replayed
 * against any of them. They are not truths; they are the numbers the two platforms agree to
 * be wrong in the same way until there is data.
 */
object ParkingConfidencePolicy {

    // §8 positive.
    const val WEIGHT_RECENT_VEHICLE_SESSION: Int = 25
    const val WEIGHT_VEHICLE_EXIT: Int = 15
    const val WEIGHT_WALKING_AFTER_VEHICLE: Int = 30
    const val WEIGHT_STATIONARY_AFTER_DRIVING: Int = 10
    const val WEIGHT_LOCATION_MOVEMENT_STOPPED: Int = 10
    const val WEIGHT_GPS_DEGRADED_NEAR_END: Int = 5
    const val WEIGHT_TRUSTED_LINK_DISCONNECT: Int = 20
    const val WEIGHT_ROUTE_COMFORTABLY_OVER_MINIMUM: Int = 5

    // §8 negative. Only the one the engine can observe at creation time is applied — see
    // [score].
    const val WEIGHT_TRIP_BELOW_MINIMUM: Int = -15

    /** §9. */
    const val HIGH_MINIMUM_SCORE: Int = 80
    const val MEDIUM_MINIMUM_SCORE: Int = 60

    /**
     * How far past a §7 minimum a route has to be before §8 calls it "comfortably over".
     *
     * Twice the bar, on either clause. §8 does not fix a figure, and doubling is the
     * reading that needs no new constant: it says the route cleared the minimum with as
     * much again to spare, on whichever clause the drive actually satisfied.
     */
    const val COMFORTABLE_MULTIPLE: Int = 2

    /**
     * @param reasons the §4 codes accumulated by the travel session, in arrival order.
     * @param durationMillis how long vehicle evidence spanned, first sighting to session end.
     * @param distanceMeters §7's accumulated travel distance for the same session.
     */
    fun score(
        reasons: Collection<EvidenceReasonCode>,
        durationMillis: Long,
        distanceMeters: Double,
    ): Int {
        var score = 0
        if (EvidenceReasonCode.RECENT_VEHICLE_ACTIVITY in reasons) score += WEIGHT_RECENT_VEHICLE_SESSION
        if (EvidenceReasonCode.VEHICLE_EXIT_DETECTED in reasons) score += WEIGHT_VEHICLE_EXIT
        if (EvidenceReasonCode.WALKING_AFTER_VEHICLE in reasons) score += WEIGHT_WALKING_AFTER_VEHICLE
        if (EvidenceReasonCode.STATIONARY_AFTER_VEHICLE in reasons) score += WEIGHT_STATIONARY_AFTER_DRIVING
        if (EvidenceReasonCode.LOCATION_STOPPED in reasons) score += WEIGHT_LOCATION_MOVEMENT_STOPPED
        if (EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in reasons) score += WEIGHT_GPS_DEGRADED_NEAR_END
        if (EvidenceReasonCode.CAR_PROJECTION_DISCONNECTED in reasons) score += WEIGHT_TRUSTED_LINK_DISCONNECT

        val durationMet = EvidenceReasonCode.VEHICLE_DURATION_MET in reasons
        val distanceMet = EvidenceReasonCode.VEHICLE_DISTANCE_MET in reasons
        // §8 "trip below minimum": neither of §7's two measured clauses was ever met, so
        // this was not a meaningful vehicle session however it ended.
        if (!durationMet && !distanceMet) score += WEIGHT_TRIP_BELOW_MINIMUM
        if (isComfortablyOverMinimum(durationMillis, distanceMeters)) score += WEIGHT_ROUTE_COMFORTABLY_OVER_MINIMUM

        // The three remaining §8 negatives — `vehicle resumes quickly`, `movement
        // continues`, `short stop pattern` — describe a candidate the drive went on to
        // contradict, and none of them can be true at the moment one is created: a vehicle
        // session that resumed retires the candidate through §3a's reconnect and
        // `PARKING_TRANSITION -> DRIVING` rows rather than lowering its score. They belong
        // to candidate *revision*, which the contract does not have and which §9's "do not
        // auto-confirm" makes unnecessary for v1.
        return score
    }

    fun bucketOf(score: Int): ConfidenceBucket = when {
        score >= HIGH_MINIMUM_SCORE -> ConfidenceBucket.HIGH
        score >= MEDIUM_MINIMUM_SCORE -> ConfidenceBucket.MEDIUM
        else -> ConfidenceBucket.LOW
    }

    private fun isComfortablyOverMinimum(durationMillis: Long, distanceMeters: Double): Boolean =
        durationMillis >= DrivingConfirmationGuard.MIN_DURATION_MILLIS * COMFORTABLE_MULTIPLE ||
            distanceMeters >= DrivingConfirmationGuard.MIN_DISTANCE_METERS * COMFORTABLE_MULTIPLE
}

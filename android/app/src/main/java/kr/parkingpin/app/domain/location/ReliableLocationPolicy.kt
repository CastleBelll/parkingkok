package kr.parkingpin.app.domain.location

import kr.parkingpin.app.domain.detection.ReliableLocation

/**
 * How old a fix may be and still count as live evidence
 * (docs/05_PARKING_DETECTION_ENGINE.md §5, "Cached-fix replay — 양 플랫폼 필수 가드").
 *
 * The OS hands over its cached last-known fix the moment location monitoring starts, and
 * that fix can be arbitrarily old. On iOS this was measured, not theorised: a fix 3h20m
 * older than the app install itself arrived with 8 m accuracy and passed the
 * negative-accuracy check unchallenged. `FusedLocationProviderClient.getLastLocation` and
 * the first delivery of a fresh `requestLocationUpdates` have the same character on
 * Android, so the guard is implemented here with identical semantics.
 *
 * Accuracy cannot catch this. A cached fix is usually a *good* fix — just not a current
 * one — so the guard has to be on the timestamp.
 *
 * [SESSION_FRESHNESS_MILLIS] (§6) governs the bounded driving session and is deliberately
 * not reused as the replay bound: a genuine delivery can reach a dozing app minutes late
 * through no fault of the fix, and rejecting those would throw away real evidence.
 *
 * Both values are field-tuning starting points in the same spirit as the evidence weights
 * in §8, not values the spec derives.
 */
object LocationFreshnessPolicy {

    /** Beyond this age a fix is treated as a replayed cache entry, not live evidence. */
    const val CACHED_FIX_MAX_AGE_MILLIS: Long = 300_000L

    /** Tolerance for device clock skew. A fix from the future is suspect; a moment ahead is ordinary. */
    const val FUTURE_TOLERANCE_MILLIS: Long = 5_000L

    /** docs/05 §6: freshness bar for a fix used as evidence inside an active session. */
    const val SESSION_FRESHNESS_MILLIS: Long = 20_000L

    fun isCachedFixReplay(sample: LocationQualitySample, nowMillis: Long): Boolean {
        val ageMillis = nowMillis - sample.atMillis
        return ageMillis > CACHED_FIX_MAX_AGE_MILLIS || ageMillis < -FUTURE_TOLERANCE_MILLIS
    }

    fun isFreshForSession(sample: LocationQualitySample, nowMillis: Long): Boolean {
        val ageMillis = nowMillis - sample.atMillis
        return ageMillis <= SESSION_FRESHNESS_MILLIS && ageMillis >= -FUTURE_TOLERANCE_MILLIS
    }
}

/** Outcome of offering one fix to [ReliableLocationSelector]. */
sealed interface ReliableLocationDecision {

    /** The fix is the new `lastReliableLocation`. */
    data class Accepted(val location: ReliableLocation) : ReliableLocationDecision

    data class Rejected(val reason: LocationDropReason) : ReliableLocationDecision
}

/**
 * Chooses `lastReliableLocation` (docs/04_ANDROID_IMPLEMENTATION.md §8,
 * docs/05_PARKING_DETECTION_ENGINE.md §6). Pure, so the boundaries are unit-tested rather
 * than discovered on a real drive.
 *
 * Semantics are shared with iOS by contract, not by shared code
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §7).
 *
 * Admission gates, in order — a fix must clear all of them:
 *  1. accuracy >= 0 (§5: negative accuracy is invalid)
 *  2. not a replayed cached fix (§5)
 *  3. fresh for the session, <= 20s (§6)
 *  4. accuracy <= 35m (§6)
 *
 * Among admitted fixes the newer one wins, with better accuracy breaking a timestamp tie.
 * Requiring "newer *and* at least as accurate" was considered and rejected: it would keep
 * a 5 m fix taken on the motorway over a 30 m fix taken at the actual parking spot, which
 * inverts the product goal. The admission bar is what keeps bad samples out — §6's "poor
 * samples must not overwrite lastReliableLocation" is enforced there, not by the tiebreak.
 *
 * **Batched deliveries.** Play services batches fixes to let the radio sleep, so one
 * delivery routinely carries several minutes of a drive at once. Measuring each fix's
 * freshness against wall-clock arrival would then reject everything but the newest: the
 * first real-device run on a Galaxy S21+ delivered a batch of four 15s-apart fixes and
 * threw two away as stale, having done nothing wrong. Freshness is therefore measured
 * against the moment the *batch* describes — its newest fix — which is what §6's "during
 * an active session" means. The cached-fix guard still runs against wall clock, because
 * replay is by definition a claim about now, so an ancient batch is still rejected whole.
 *
 * [MAX_ACCURACY_METERS] is a field-tuning starting point.
 */
object ReliableLocationSelector {

    /** docs/05 §6 initial default. Starting point for field tuning. */
    const val MAX_ACCURACY_METERS: Float = 35f

    /**
     * @param nowMillis wall clock at the moment the delivery was received. The cached-fix
     *   guard is measured against this, because replay is defined relative to *now*.
     * @param sessionReferenceMillis the moment the batch describes — see the note above on
     *   batched deliveries. Defaults to [nowMillis] for a single live fix.
     */
    fun select(
        current: ReliableLocation?,
        sample: LocationSample,
        nowMillis: Long,
        sessionReferenceMillis: Long = nowMillis,
    ): ReliableLocationDecision {
        val quality = sample.quality
        val rejection = when {
            !quality.isValid -> LocationDropReason.INVALID_ACCURACY
            LocationFreshnessPolicy.isCachedFixReplay(quality, nowMillis) -> LocationDropReason.CACHED_FIX_REPLAY
            !LocationFreshnessPolicy.isFreshForSession(quality, sessionReferenceMillis) ->
                LocationDropReason.STALE_FOR_SESSION
            quality.horizontalAccuracyM > MAX_ACCURACY_METERS -> LocationDropReason.ACCURACY_TOO_POOR
            current != null && !isImprovement(current, sample) -> LocationDropReason.NOT_NEWER
            else -> null
        }
        if (rejection != null) return ReliableLocationDecision.Rejected(rejection)

        return ReliableLocationDecision.Accepted(location = sample.toReliableLocation())
    }

    private fun isImprovement(current: ReliableLocation, sample: LocationSample): Boolean = when {
        sample.atMillis > current.capturedAtMillis -> true
        sample.atMillis < current.capturedAtMillis -> false
        else -> sample.horizontalAccuracyM < current.horizontalAccuracyM
    }
}

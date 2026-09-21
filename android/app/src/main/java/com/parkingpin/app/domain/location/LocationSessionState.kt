package com.parkingpin.app.domain.location

import kotlinx.serialization.Serializable

/**
 * Counters the P0 field runs are judged on.
 *
 * docs/05_PARKING_DETECTION_ENGINE.md §5 is explicit that excluded samples are counted,
 * not dropped quietly. A run that admits nothing while [cachedFixDropCount] climbs means
 * the freshness bound is wrong, and that is only visible if the rejections are kept.
 *
 * Coordinate-free: counts, accuracies, and ages only.
 */
@Serializable
data class LocationDiagnosticsCounters(
    /** PendingIntent deliveries received, each of which may carry several fixes. */
    val deliveryCount: Int = 0,
    val sampleCount: Int = 0,
    val admittedCount: Int = 0,
    val invalidAccuracyDropCount: Int = 0,
    /** The guard that matters most in P0 — see [LocationFreshnessPolicy]. */
    val cachedFixDropCount: Int = 0,
    val staleDropCount: Int = 0,
    val poorAccuracyDropCount: Int = 0,
    val notNewerDropCount: Int = 0,
    /** Age of the most recent rejected cached fix, so the threshold can be judged, not guessed. */
    val lastCachedFixAgeMillis: Long? = null,
    val lastSampleAccuracyM: Float? = null,
    val lastSampleAtMillis: Long? = null,
    val sessionsStarted: Int = 0,
    val sessionsStopped: Int = 0,
) {
    /**
     * Zeroes the per-session counts and keeps the cumulative ones.
     *
     * The drop counts only mean something inside one session — "12 cached fixes rejected"
     * is a threshold verdict for this trip, not a running total since install — while the
     * session tallies are how a whole field run is judged.
     */
    fun startingNewSession(): LocationDiagnosticsCounters = LocationDiagnosticsCounters(
        sessionsStarted = sessionsStarted + 1,
        sessionsStopped = sessionsStopped,
    )

    fun recordingDrop(reason: LocationDropReason, ageMillis: Long): LocationDiagnosticsCounters = when (reason) {
        LocationDropReason.INVALID_ACCURACY -> copy(invalidAccuracyDropCount = invalidAccuracyDropCount + 1)
        LocationDropReason.CACHED_FIX_REPLAY -> copy(
            cachedFixDropCount = cachedFixDropCount + 1,
            lastCachedFixAgeMillis = ageMillis,
        )
        LocationDropReason.STALE_FOR_SESSION -> copy(staleDropCount = staleDropCount + 1)
        LocationDropReason.ACCURACY_TOO_POOR -> copy(poorAccuracyDropCount = poorAccuracyDropCount + 1)
        LocationDropReason.NOT_NEWER -> copy(notNewerDropCount = notNewerDropCount + 1)
    }
}

/**
 * Everything about the bounded location session that has to survive process death.
 *
 * One serialized blob behind one DataStore key, so a delivery that updates the record, the
 * evidence, and the counters lands as a single atomic edit. A torn write cannot leave a
 * registration recorded whose evidence was never updated.
 */
@Serializable
data class LocationSessionState(
    val record: LocationSessionRecord? = null,
    val evidence: DrivingSessionEvidence? = null,
    val counters: LocationDiagnosticsCounters = LocationDiagnosticsCounters(),
    /** Verdict of the §7 guard for the current session. */
    val drivingConfirmed: Boolean = false,
    /** Reason codes from the last §7 evaluation, as the contract's stable wire strings. */
    val drivingReasonCodes: List<String> = emptyList(),
    val lastStopReason: LocationSessionStopReason? = null,
    /** Short, coordinate-free reason the last Play services call was rejected. */
    val lastFailure: String? = null,
) {
    val mode: LocationSessionMode
        get() = record?.mode ?: LocationSessionMode.IDLE
}

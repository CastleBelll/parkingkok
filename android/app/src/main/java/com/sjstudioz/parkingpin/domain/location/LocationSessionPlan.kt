package com.sjstudioz.parkingpin.domain.location

import kotlinx.serialization.Serializable

/**
 * The conceptual location modes from docs/04_ANDROID_IMPLEMENTATION.md §2.
 *
 * Each mode owns its own bounded request shape, and [IDLE] owns none at all — that is the
 * rule docs/00_CORE_RULES.md turns into "no 24h continuous high accuracy location".
 */
@Serializable
enum class LocationSessionMode {
    /** No request registered with Play services whatsoever. */
    IDLE,

    /** Vehicle evidence appeared; cheap confirmation window while §7's guard decides. */
    DRIVING_CANDIDATE,

    /** Confirmed vehicle session; bounded high-accuracy capture. */
    DRIVING,

    /** Vehicle session ended; capture the last reliable points, then stop. */
    PARKING_TRANSITION,
}

/** Accuracy tier, mapped to a Play services `Priority` constant at the adapter boundary. */
enum class LocationAccuracyTier {
    BALANCED,
    HIGH,
}

/**
 * One bounded Fused Location request.
 *
 * [durationMillis] is the part that matters most: Play services expires the request on its
 * own when it elapses, so a session cannot outlive its budget even if this process is
 * never scheduled again. See [LocationSessionPlanner] for the other two layers.
 */
data class LocationSessionConfig(
    val tier: LocationAccuracyTier,
    val intervalMillis: Long,
    val minUpdateIntervalMillis: Long,
    /** How long Play services may batch deliveries. Batching is the main battery lever here. */
    val maxUpdateDelayMillis: Long,
    val durationMillis: Long,
    val maxUpdates: Int?,
)

/**
 * Per-mode request shapes and lifetimes.
 *
 * Every number here is a field-tuning starting point measured against the P0 battery gate
 * (docs/05_PARKING_DETECTION_ENGINE.md §19), not a value the spec derives.
 */
object LocationSessionProfiles {

    /**
     * Bump when any profile below changes, so a session registered by an older build is
     * replaced instead of left running on terms this build no longer asks for.
     *
     * v2 capped the DRIVING batch window at the freshness bar. The bump is what forces a
     * device already holding a v1 registration to re-register: on the S21+ it kept
     * delivering 60s batches across the upgrade until the version told the planner to
     * replace it.
     */
    const val VERSION: Int = 2

    /**
     * Absolute ceiling on one bounded session, per mode. A drive longer than the DRIVING
     * ceiling stops capturing rather than running indefinitely: an unbounded session is
     * exactly what the battery gate rejects, and a vehicle session with no exit after two
     * hours is an anomaly the M3 state machine will handle, not something to burn GPS on.
     */
    fun maxSessionMillis(mode: LocationSessionMode): Long = when (mode) {
        LocationSessionMode.IDLE -> 0L
        LocationSessionMode.DRIVING_CANDIDATE -> 10 * 60_000L
        LocationSessionMode.DRIVING -> 120 * 60_000L
        LocationSessionMode.PARKING_TRANSITION -> 3 * 60_000L
    }

    fun configFor(mode: LocationSessionMode, remainingMillis: Long): LocationSessionConfig? = when (mode) {
        LocationSessionMode.IDLE -> null

        // Cheap: we only need to know whether the device is actually travelling before
        // committing to high accuracy. The 30s interval is itself the battery lever here,
        // so there is nothing left for batching to save.
        LocationSessionMode.DRIVING_CANDIDATE -> LocationSessionConfig(
            tier = LocationAccuracyTier.BALANCED,
            intervalMillis = 30_000L,
            minUpdateIntervalMillis = 15_000L,
            maxUpdateDelayMillis = 0L,
            durationMillis = remainingMillis.coerceAtMost(5 * 60_000L),
            maxUpdates = null,
        )

        // Batched, but never for longer than a fix stays usable.
        //
        // Batching is the main battery lever: it lets the radio sleep between bursts
        // instead of waking the app every 15s. But §6 only counts a fix as reliable for
        // 20s, so a 60s batch window arrives holding fixes its own freshness rule must
        // then reject — measured on a Galaxy S21+, a batch of four arrived and two were
        // dropped as stale having done nothing wrong. Capping the window at the freshness
        // bar keeps both rules true at once.
        //
        // If the battery measurement (§19) later demands a longer window, the fix is not
        // to loosen §6 but to split it from §5: sample *validity* governs the distance
        // chain, and reliable-location *selection* governs the parking point. That is a
        // deliberate change to make with field data in hand, not before.
        LocationSessionMode.DRIVING -> LocationSessionConfig(
            tier = LocationAccuracyTier.HIGH,
            intervalMillis = 15_000L,
            minUpdateIntervalMillis = 10_000L,
            maxUpdateDelayMillis = LocationFreshnessPolicy.SESSION_FRESHNESS_MILLIS,
            durationMillis = remainingMillis.coerceAtMost(15 * 60_000L),
            maxUpdates = null,
        )

        // The one moment accuracy is worth full price, and the one mode with a hard update
        // budget: a handful of fixes at the kerb, then stop.
        LocationSessionMode.PARKING_TRANSITION -> LocationSessionConfig(
            tier = LocationAccuracyTier.HIGH,
            intervalMillis = 5_000L,
            minUpdateIntervalMillis = 3_000L,
            maxUpdateDelayMillis = 0L,
            durationMillis = remainingMillis.coerceAtMost(60_000L),
            maxUpdates = 5,
        )
    }
}

/** Durable record of the registration currently held with Play services. */
@Serializable
data class LocationSessionRecord(
    val mode: LocationSessionMode,
    val profileVersion: Int,
    val startedAtMillis: Long,
    /** Absolute stop time. Reached, the session ends regardless of what else is happening. */
    val hardDeadlineAtMillis: Long,
    /** When the current Play services request expires by itself. */
    val registrationExpiresAtMillis: Long,
    val deliveredUpdateCount: Int = 0,
)

/** Why a session was torn down. Surfaced in diagnostics so a stop is never silent. */
@Serializable
enum class LocationSessionStopReason {
    DESIRED_IDLE,
    DEADLINE_REACHED,
    UPDATE_BUDGET_SPENT,
    PERMISSION_LOST,
    PROFILE_CHANGED,
}

/** What the planner decided to do about the Play services location registration. */
sealed interface LocationSessionAction {
    data class Start(val config: LocationSessionConfig, val record: LocationSessionRecord) : LocationSessionAction
    data class Renew(val config: LocationSessionConfig, val record: LocationSessionRecord) : LocationSessionAction
    data class Stop(val reason: LocationSessionStopReason) : LocationSessionAction
    data object None : LocationSessionAction
}

/**
 * Decides how to bring the actual Fused Location registration in line with the desired
 * mode. Pure, no Android types — this is where "the session must not leak" is enforced,
 * so it is unit tested directly rather than observed on a battery graph.
 *
 * Three independent layers stop a leaked session, because any one of them can be defeated:
 *
 *  1. `durationMillis` on every request. Play services expires it even if this process is
 *     never scheduled again — the only layer that survives the app never running.
 *  2. [LocationSessionRecord.hardDeadlineAtMillis], checked on every delivery and on every
 *     process start. A renewal never extends it, so renewals cannot walk the session
 *     forward indefinitely.
 *  3. Reconciliation on process start and boot, which stops a record that is already past
 *     its deadline.
 *
 * A reboot drops Play services registrations outright, so there is no fourth case.
 */
object LocationSessionPlanner {

    /**
     * Renew this far before the Play services request expires. One DRIVING delivery
     * interval plus its batching window, so a renewal always has a delivery to ride on and
     * the session never lapses mid-drive.
     */
    const val RENEW_LEAD_MILLIS: Long = 90_000L

    fun plan(
        current: LocationSessionRecord?,
        desiredMode: LocationSessionMode,
        permissionGranted: Boolean,
        nowMillis: Long,
    ): LocationSessionAction {
        if (!permissionGranted) {
            return if (current == null) {
                LocationSessionAction.None
            } else {
                LocationSessionAction.Stop(LocationSessionStopReason.PERMISSION_LOST)
            }
        }
        if (desiredMode == LocationSessionMode.IDLE) {
            return if (current == null) {
                LocationSessionAction.None
            } else {
                LocationSessionAction.Stop(LocationSessionStopReason.DESIRED_IDLE)
            }
        }
        if (current == null) return start(desiredMode, nowMillis)

        if (nowMillis >= current.hardDeadlineAtMillis) {
            return LocationSessionAction.Stop(LocationSessionStopReason.DEADLINE_REACHED)
        }
        // A profile change means the registered request no longer matches what this build
        // asks for, so it is replaced rather than left to expire on old terms.
        if (current.mode != desiredMode || current.profileVersion != LocationSessionProfiles.VERSION) {
            return start(desiredMode, nowMillis)
        }

        val config = LocationSessionProfiles.configFor(desiredMode, current.hardDeadlineAtMillis - nowMillis)
            ?: return LocationSessionAction.Stop(LocationSessionStopReason.DEADLINE_REACHED)

        // PARKING_TRANSITION is the mode with a fixed update budget: a handful of fixes at
        // the kerb, then stop. Spending it is a normal, successful end to a session.
        val budget = config.maxUpdates
        if (budget != null && current.deliveredUpdateCount >= budget) {
            return LocationSessionAction.Stop(LocationSessionStopReason.UPDATE_BUDGET_SPENT)
        }
        if (current.registrationExpiresAtMillis - nowMillis > RENEW_LEAD_MILLIS) {
            return LocationSessionAction.None
        }
        return LocationSessionAction.Renew(
            config = config,
            // The hard deadline is carried over untouched — that is what makes renewal
            // safe. Only the Play services request is re-armed.
            record = current.copy(registrationExpiresAtMillis = nowMillis + config.durationMillis),
        )
    }

    private fun start(mode: LocationSessionMode, nowMillis: Long): LocationSessionAction {
        val hardDeadline = nowMillis + LocationSessionProfiles.maxSessionMillis(mode)
        val config = LocationSessionProfiles.configFor(mode, hardDeadline - nowMillis)
            ?: return LocationSessionAction.Stop(LocationSessionStopReason.DESIRED_IDLE)
        return LocationSessionAction.Start(
            config = config,
            record = LocationSessionRecord(
                mode = mode,
                profileVersion = LocationSessionProfiles.VERSION,
                startedAtMillis = nowMillis,
                hardDeadlineAtMillis = hardDeadline,
                registrationExpiresAtMillis = nowMillis + config.durationMillis,
            ),
        )
    }
}

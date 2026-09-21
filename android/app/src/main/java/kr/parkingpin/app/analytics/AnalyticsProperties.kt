package kr.parkingpin.app.analytics

import kotlinx.serialization.Serializable

/**
 * The logical version of the detection engine's scoring and rules (docs/17 §4).
 *
 * Bumped by hand whenever a weight or rule changes, so rejection rates from two builds
 * stay comparable without any location telemetry. It is the only thing that makes the
 * detection family of events comparable over time at all.
 */
object DetectorVersion {

    /**
     * Never derived from the app version: a release that does not touch the engine must
     * not look like an engine change, and two releases that share an engine must not look
     * like two engines.
     *
     * Kept in step with `DetectorVersion.current` on iOS — the two platforms' numbers are
     * compared per version (docs/17 §8).
     */
    const val CURRENT: Int = 1
}

/**
 * Coarse drive-duration band — docs/17 §3 `driveDurationBucket`.
 *
 * **The edges are literals and stay that way**, for the reason [LocationQualityBucket]
 * spells out: a band bound to a tunable threshold would retroactively change what an
 * already-reported event meant, and a reporting format has to stay comparable over time.
 */
@Serializable
enum class DriveDurationBucket(val wireValue: String) {
    UNDER_5_MIN("under_5_min"),
    MIN_5_15("min_5_15"),
    MIN_15_45("min_15_45"),
    OVER_45_MIN("over_45_min"),

    ;

    companion object {
        const val SHORT_MAXIMUM_MILLIS: Long = 5 * 60 * 1_000L
        const val MEDIUM_MAXIMUM_MILLIS: Long = 15 * 60 * 1_000L
        const val LONG_MAXIMUM_MILLIS: Long = 45 * 60 * 1_000L

        /**
         * Null for a negative duration. A clock that ran backwards is a defect; giving it
         * a band would hide it inside a healthy-looking distribution.
         */
        fun of(durationMillis: Long): DriveDurationBucket? = when {
            durationMillis < 0 -> null
            durationMillis < SHORT_MAXIMUM_MILLIS -> UNDER_5_MIN
            durationMillis < MEDIUM_MAXIMUM_MILLIS -> MIN_5_15
            durationMillis < LONG_MAXIMUM_MILLIS -> MIN_15_45
            else -> OVER_45_MIN
        }
    }
}

/**
 * Coarse straight-line distance band — docs/17 §3 `distanceBucket`.
 *
 * Fixed edges, same reasoning as [DriveDurationBucket].
 */
@Serializable
enum class DistanceBucket(val wireValue: String) {
    UNDER_1_KM("under_1_km"),
    KM_1_5("km_1_5"),
    KM_5_20("km_5_20"),
    OVER_20_KM("over_20_km"),

    ;

    companion object {
        const val SHORT_MAXIMUM_METERS: Double = 1_000.0
        const val MEDIUM_MAXIMUM_METERS: Double = 5_000.0
        const val LONG_MAXIMUM_METERS: Double = 20_000.0

        /** Null for a negative distance, for the reason [DriveDurationBucket.of] gives. */
        fun of(meters: Double): DistanceBucket? = when {
            meters < 0 -> null
            meters < SHORT_MAXIMUM_METERS -> UNDER_1_KM
            meters < MEDIUM_MAXIMUM_METERS -> KM_1_5
            meters < LONG_MAXIMUM_METERS -> KM_5_20
            else -> OVER_20_KM
        }
    }
}

/**
 * The cross-platform shape of a motion-permission outcome.
 *
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §4: never ship a platform SDK enum as a
 * product code. iOS `CMAuthorizationStatus` and this platform's `ACTIVITY_RECOGNITION`
 * grant both map onto this, which is what makes the two platforms' numbers addable.
 *
 * `RESTRICTED` has no Android source today and is here because the contract is shared;
 * the alternative is two enums that cannot be summed.
 */
enum class MotionPermissionResult(val wireValue: String) {
    GRANTED("granted"),
    DENIED("denied"),
    RESTRICTED("restricted"),
    NOT_DETERMINED("not_determined"),
}

/**
 * The cross-platform shape of a location-permission level, same contract as
 * [MotionPermissionResult].
 *
 * `ALWAYS` is this platform's background-location grant and `WHEN_IN_USE` its foreground
 * one, which is the mapping that makes the level comparable with iOS.
 */
enum class LocationPermissionLevel(val wireValue: String) {
    ALWAYS("always"),
    WHEN_IN_USE("when_in_use"),
    DENIED("denied"),
    RESTRICTED("restricted"),
    NOT_DETERMINED("not_determined"),
}

/**
 * Which way the widget's `-` / `+` moved the floor.
 *
 * The direction, never the floor: docs/17 §3 forbids `floor` outright.
 */
enum class WidgetFloorDirection(val wireValue: String) {
    UP("up"),
    DOWN("down"),
}

/** Which store took the money. docs/17 §7 counts new paid conversions by store. */
enum class PurchaseStore(val wireValue: String) {
    APP_STORE("app_store"),
    PLAY_STORE("play_store"),
}

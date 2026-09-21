package com.parkingpin.app.domain.location

import com.parkingpin.app.domain.detection.ReliableLocation

/**
 * One Fused Location fix, normalized off `android.location.Location`
 * (docs/04_ANDROID_IMPLEMENTATION.md §7: SDK types are mapped before entering the domain).
 *
 * This is the only domain type that carries coordinates besides [ReliableLocation], and
 * like it, [toString] is redacted so an accidental `Log.d("$sample")` cannot leak a
 * position (docs/00_CORE_RULES.md Privacy). Everything downstream that does not need the
 * coordinate takes [quality] instead.
 */
data class LocationSample(
    val atMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float,
    val speedMps: Float? = null,
) {
    val quality: LocationQualitySample
        get() = LocationQualitySample(atMillis, horizontalAccuracyM, speedMps)

    fun toReliableLocation(): ReliableLocation = ReliableLocation(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyM = horizontalAccuracyM,
        capturedAtMillis = atMillis,
    )

    override fun toString(): String =
        "LocationSample(redacted, accuracyM=$horizontalAccuracyM, atMillis=$atMillis)"
}

/**
 * What a fix contributes once the coordinate is dropped: when it was taken, how good it
 * was, and how fast the device was moving.
 *
 * The ring buffer, the diagnostics report, and the driving guard all take this type, so
 * no coordinate exists on any of those paths at all — the same structural choice iOS made
 * with `LocationQualitySample`.
 */
data class LocationQualitySample(
    val atMillis: Long,
    val horizontalAccuracyM: Float,
    val speedMps: Float? = null,
) {
    /**
     * Fused Location reports a negative accuracy when the fix is invalid
     * (docs/05_PARKING_DETECTION_ENGINE.md §5).
     */
    val isValid: Boolean
        get() = horizontalAccuracyM >= 0f
}

/**
 * Why a fix was not admitted as live evidence.
 *
 * docs/05_PARKING_DETECTION_ENGINE.md §5 requires excluded samples to be counted rather
 * than dropped quietly: a rising exclusion count with no admitted samples is how a
 * mis-set threshold shows itself in the field.
 */
enum class LocationDropReason {
    /** Negative accuracy — the fix is not a fix. */
    INVALID_ACCURACY,

    /** The cached last-known fix the OS replays when monitoring starts. */
    CACHED_FIX_REPLAY,

    /** Older than the active session allows, so it cannot describe where we are now. */
    STALE_FOR_SESSION,

    /** Accuracy worse than the reliable-location threshold. */
    ACCURACY_TOO_POOR,

    /** Admitted, but not an improvement on the reliable fix we already hold. */
    NOT_NEWER,
}

package kr.parkingpin.app.detection

import kr.parkingpin.app.domain.location.LocationSessionConfig

/**
 * The Fused Location side effects, behind an interface so the session lifecycle can be
 * tested without Play services (docs/16_CODING_STANDARDS.md §2).
 */
interface LocationSessionRegistrar {

    /** Foreground location, without which no request can be made at all. */
    fun hasForegroundLocationPermission(): Boolean

    /**
     * Background location. Without it Play services stops delivering to the PendingIntent
     * once the app leaves the foreground, which is precisely when a drive happens.
     */
    fun hasBackgroundLocationPermission(): Boolean

    /** @return null on success, or a short, coordinate-free reason string on failure. */
    suspend fun request(config: LocationSessionConfig): String?

    /** @return null on success, or a short, coordinate-free reason string on failure. */
    suspend fun remove(): String?
}

package com.parkingpin.app.detection

/**
 * Registration side effects, behind an interface so reconciliation can be tested without
 * Play services (docs/16_CODING_STANDARDS.md §2: interface-based boundaries at the SDK edge).
 */
interface TransitionRegistrar {

    fun hasPermission(): Boolean

    /** @return null on success, or a short, coordinate-free reason string on failure. */
    suspend fun register(): String?

    /** @return null on success, or a short, coordinate-free reason string on failure. */
    suspend fun unregister(): String?
}

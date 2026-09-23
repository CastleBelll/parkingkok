package com.sjstudioz.parkingpin.domain.parking

/**
 * Supplies a location to attach to a record, when one can be had.
 *
 * FR-001 turns on this interface: manual parking must save "위치 권한 없이도", so the
 * absence of a location is an ordinary return value here, not an error. Implementations
 * return null when permission is missing, when detection is off, or when no fix has been
 * good enough to trust yet — the caller cannot tell those apart and must not need to.
 */
fun interface ParkingLocationProvider {

    /**
     * What is already known, without waiting for anything.
     *
     * This is what a save uses, because a save must not wait: the record is local and the
     * user is standing next to their car.
     */
    suspend fun lastReliableLocation(): ParkingLocation?

    /**
     * One fix from the OS, which takes as long as a GPS takes.
     *
     * Never prompts (FR-001), and null is an ordinary answer — no permission, a timeout,
     * indoors. Called **after** a record exists, never before it: waiting for this first
     * made the save button look broken for up to eight seconds.
     */
    suspend fun currentFix(): ParkingLocation? = null
    // Still a `fun interface`: `currentFix` has a default, so `lastReliableLocation` is the
    // one abstract member and every `ParkingLocationProvider { null }` in the tests keeps
    // working.
}

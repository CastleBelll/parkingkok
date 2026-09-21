package kr.parkingpin.app.domain.parking

/**
 * Supplies a location to attach to a record, when one can be had.
 *
 * FR-001 turns on this interface: manual parking must save "위치 권한 없이도", so the
 * absence of a location is an ordinary return value here, not an error. Implementations
 * return null when permission is missing, when detection is off, or when no fix has been
 * good enough to trust yet — the caller cannot tell those apart and must not need to.
 */
fun interface ParkingLocationProvider {

    /** The last location worth recording, or null. Never throws for a missing permission. */
    suspend fun lastReliableLocation(): ParkingLocation?
}

package com.parkingpin.app.domain.detection

import kotlinx.serialization.Serializable

/**
 * Normalized motion events, the Android half of the cross-platform event vocabulary in
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2. The `wire` value is the stable string
 * shared with iOS, the parity fixtures in `platform-tests/`, and analytics — never a
 * platform SDK enum value (§4).
 *
 * `atMillis` is wall-clock time of the transition itself; `receivedAtMillis` is when this
 * process observed it. The gap between them is the OEM delivery delay we measure in P0
 * (docs/04_ANDROID_IMPLEMENTATION.md §20).
 */
@Serializable
data class MotionDomainEvent(
    val kind: MotionEventKind,
    val atMillis: Long,
    val receivedAtMillis: Long,
)

/** Stable normalized event identities. `wire` strings are part of the cross-platform contract. */
@Serializable
enum class MotionEventKind(val wire: String) {
    ENTERED_VEHICLE("vehicle_enter"),
    EXITED_VEHICLE("vehicle_exit"),
    STARTED_WALKING("walking_enter"),
    BECAME_STATIONARY("still_enter"),
    STOPPED_BEING_STATIONARY("still_exit"),
}

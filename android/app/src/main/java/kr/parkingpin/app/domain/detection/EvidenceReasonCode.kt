package kr.parkingpin.app.domain.detection

import kr.parkingpin.app.domain.location.DrivingReasonCode
import kotlinx.serialization.Serializable

/**
 * The complete §4 reason-code list from `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md`.
 *
 * **The list is closed.** docs/05_PARKING_DETECTION_ENGINE.md §3a: "an engine that needs a
 * code that is not on it has found a contract gap, and the answer is to raise it, not to
 * add a string." A new constant here is a contract change, not an implementation detail.
 *
 * [DrivingReasonCode] holds the three codes the §7 guard produces and is unchanged — it is
 * the guard's own vocabulary and is already serialized into the location session state.
 * [fromWire] is how those three re-enter this enum, so the two never drift into separate
 * spellings of the same contract.
 */
@Serializable
enum class EvidenceReasonCode(val wire: String) {
    RECENT_VEHICLE_ACTIVITY("recent_vehicle_activity"),
    VEHICLE_DURATION_MET("vehicle_duration_met"),
    VEHICLE_DISTANCE_MET("vehicle_distance_met"),
    VEHICLE_EXIT_DETECTED("vehicle_exit_detected"),
    WALKING_AFTER_VEHICLE("walking_after_vehicle"),
    STATIONARY_AFTER_VEHICLE("stationary_after_vehicle"),
    LOCATION_STOPPED("location_stopped"),
    LOCATION_QUALITY_DEGRADED("location_quality_degraded"),
    RELIABLE_LOCATION_CAPTURED("reliable_location_captured"),

    /**
     * §3a "The car link": one code covers a projection link and a Bluetooth car link,
     * because the product distinction — the phone was attached to a car and stopped being
     * attached — is the same. Which kind of link it was belongs in the §8 weighting, not
     * in a second code.
     */
    CAR_PROJECTION_DISCONNECTED("car_projection_disconnected"),

    CANDIDATE_TIMEOUT("candidate_timeout"),
    ;

    companion object {

        fun fromWire(wire: String): EvidenceReasonCode? = entries.firstOrNull { it.wire == wire }

        fun of(code: DrivingReasonCode): EvidenceReasonCode = checkNotNull(fromWire(code.wire)) {
            // Unreachable while §4 stays closed, and loud rather than silent if it stops
            // being: a guard code that has no §4 home is exactly the contract gap §3a
            // says to raise.
            "driving reason ${code.wire} is not a §4 reason code"
        }
    }
}

package kr.parkingpin.app.domain.location

import kr.parkingpin.app.domain.detection.MotionEventKind

/**
 * Picks the capture mode a motion event calls for.
 *
 * **Not the state machine.** docs/05_PARKING_DETECTION_ENGINE.md §16's engine — transitions,
 * candidate creation, confidence scoring — is M3. This only answers the narrow M0B-2
 * question "what should the Fused Location request look like right now", which is the
 * conceptual mode ladder in docs/04_ANDROID_IMPLEMENTATION.md §2.
 *
 * When the engine lands it takes this decision over and this object goes away.
 */
object LocationCaptureModePolicy {

    fun modeFor(event: MotionEventKind, current: LocationSessionMode): LocationSessionMode = when (event) {
        // Vehicle evidence opens a cheap confirmation window; §7's guard promotes it.
        MotionEventKind.ENTERED_VEHICLE -> when (current) {
            LocationSessionMode.DRIVING -> LocationSessionMode.DRIVING
            else -> LocationSessionMode.DRIVING_CANDIDATE
        }

        // Vehicle ended. Capture the last reliable points, then stop (§2 PARKING_TRANSITION).
        MotionEventKind.EXITED_VEHICLE -> LocationSessionMode.PARKING_TRANSITION

        // Walking only matters as an end-of-drive confirmation. Walking with no vehicle
        // session behind it is someone on foot, and must not start a location request.
        MotionEventKind.STARTED_WALKING -> when (current) {
            LocationSessionMode.DRIVING,
            LocationSessionMode.DRIVING_CANDIDATE,
            LocationSessionMode.PARKING_TRANSITION,
            -> LocationSessionMode.PARKING_TRANSITION

            LocationSessionMode.IDLE -> LocationSessionMode.IDLE
        }

        // Stationary is supporting evidence only (§8) and never changes the request shape:
        // a car at a red light is STILL, and dropping capture there would lose the drive.
        MotionEventKind.BECAME_STATIONARY,
        MotionEventKind.STOPPED_BEING_STATIONARY,
        -> current
    }

    /** Promotion to [LocationSessionMode.DRIVING] once §7's guard is satisfied. */
    fun promoteOnDrivingConfirmed(current: LocationSessionMode): LocationSessionMode =
        if (current == LocationSessionMode.DRIVING_CANDIDATE) LocationSessionMode.DRIVING else current
}

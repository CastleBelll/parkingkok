package kr.parkingpin.app.domain.detection

/**
 * Motion activities the detector cares about, decoupled from Play services
 * `DetectedActivity` integer codes (docs/04_ANDROID_IMPLEMENTATION.md §7:
 * SDK types are mapped before entering the domain).
 */
enum class MotionActivity {
    IN_VEHICLE,
    WALKING,
    STILL,
}

/** Direction of an activity transition, decoupled from `ActivityTransition` int codes. */
enum class TransitionKind {
    ENTER,
    EXIT,
}

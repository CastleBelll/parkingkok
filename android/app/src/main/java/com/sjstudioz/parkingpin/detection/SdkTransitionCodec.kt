package com.sjstudioz.parkingpin.detection

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.sjstudioz.parkingpin.domain.detection.MotionActivity
import com.sjstudioz.parkingpin.domain.detection.TransitionKind

/**
 * Translates Play services integer codes to and from domain enums, and converts the
 * elapsed-realtime stamp on a transition event to wall-clock time.
 *
 * This is the only place that knows Play services constants, keeping the mapper and the
 * rest of the detection pipeline SDK-free (docs/04_ANDROID_IMPLEMENTATION.md §7).
 */
object SdkTransitionCodec {

    fun toMotionActivity(detectedActivityType: Int): MotionActivity? = when (detectedActivityType) {
        DetectedActivity.IN_VEHICLE -> MotionActivity.IN_VEHICLE
        DetectedActivity.WALKING -> MotionActivity.WALKING
        DetectedActivity.STILL -> MotionActivity.STILL
        else -> null
    }

    fun toDetectedActivityType(activity: MotionActivity): Int = when (activity) {
        MotionActivity.IN_VEHICLE -> DetectedActivity.IN_VEHICLE
        MotionActivity.WALKING -> DetectedActivity.WALKING
        MotionActivity.STILL -> DetectedActivity.STILL
    }

    fun toTransitionKind(transitionType: Int): TransitionKind? = when (transitionType) {
        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> TransitionKind.ENTER
        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> TransitionKind.EXIT
        else -> null
    }

    fun toTransitionType(kind: TransitionKind): Int = when (kind) {
        TransitionKind.ENTER -> ActivityTransition.ACTIVITY_TRANSITION_ENTER
        TransitionKind.EXIT -> ActivityTransition.ACTIVITY_TRANSITION_EXIT
    }

    /**
     * Transition events are stamped on the monotonic boot clock. Anchor them to wall
     * clock by measuring how long ago the event happened.
     *
     * A future-dated event (clock skew between the GMS process and ours) is clamped to
     * "now" rather than producing a timestamp ahead of the observation time.
     */
    fun eventTimeMillis(
        eventElapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long,
        nowEpochMillis: Long,
    ): Long {
        val ageMillis = (nowElapsedRealtimeNanos - eventElapsedRealtimeNanos) / NANOS_PER_MILLI
        if (ageMillis <= 0L) return nowEpochMillis
        return nowEpochMillis - ageMillis
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}

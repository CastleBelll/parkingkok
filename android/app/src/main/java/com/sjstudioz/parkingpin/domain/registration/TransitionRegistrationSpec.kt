package com.sjstudioz.parkingpin.domain.registration

import com.sjstudioz.parkingpin.domain.detection.MotionActivity
import com.sjstudioz.parkingpin.domain.detection.TransitionKind

/** One activity transition the detector subscribes to. */
data class TransitionSubscription(
    val activity: MotionActivity,
    val transition: TransitionKind,
)

/**
 * The exact set of transitions the app wants registered with Play services.
 *
 * [version] must be bumped whenever [subscriptions] changes. Reconciliation compares the
 * recorded version against this one, so a stale registration left over from an older app
 * build is replaced instead of silently kept.
 */
object TransitionRegistrationSpec {

    /** docs/04_ANDROID_IMPLEMENTATION.md §2 minimum set. */
    val subscriptions: List<TransitionSubscription> = listOf(
        TransitionSubscription(MotionActivity.IN_VEHICLE, TransitionKind.ENTER),
        TransitionSubscription(MotionActivity.IN_VEHICLE, TransitionKind.EXIT),
        TransitionSubscription(MotionActivity.WALKING, TransitionKind.ENTER),
        TransitionSubscription(MotionActivity.STILL, TransitionKind.ENTER),
        TransitionSubscription(MotionActivity.STILL, TransitionKind.EXIT),
    )

    const val VERSION: Int = 1
}

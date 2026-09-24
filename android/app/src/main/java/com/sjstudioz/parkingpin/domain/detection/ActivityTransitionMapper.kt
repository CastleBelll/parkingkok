package com.sjstudioz.parkingpin.domain.detection

/**
 * Maps a normalized activity transition onto the cross-platform motion event vocabulary.
 *
 * Pure domain logic with no Play services types, so it is covered by JVM unit tests.
 * Transitions the detector does not subscribe to return null and are dropped rather than
 * logged as unknown events.
 */
object ActivityTransitionMapper {

    fun map(
        activity: MotionActivity,
        transition: TransitionKind,
        atMillis: Long,
        receivedAtMillis: Long,
    ): MotionDomainEvent? {
        val kind = kindOf(activity, transition) ?: return null
        return MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = receivedAtMillis)
    }

    private fun kindOf(activity: MotionActivity, transition: TransitionKind): MotionEventKind? =
        when (activity) {
            MotionActivity.IN_VEHICLE -> when (transition) {
                TransitionKind.ENTER -> MotionEventKind.ENTERED_VEHICLE
                TransitionKind.EXIT -> MotionEventKind.EXITED_VEHICLE
            }
            // WALKING EXIT carries no product meaning: walking stops for many reasons.
            MotionActivity.WALKING -> when (transition) {
                TransitionKind.ENTER -> MotionEventKind.STARTED_WALKING
                TransitionKind.EXIT -> null
            }
            MotionActivity.STILL -> when (transition) {
                TransitionKind.ENTER -> MotionEventKind.BECAME_STATIONARY
                TransitionKind.EXIT -> MotionEventKind.STOPPED_BEING_STATIONARY
            }
        }
}

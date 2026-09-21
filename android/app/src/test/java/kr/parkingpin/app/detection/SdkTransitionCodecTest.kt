package kr.parkingpin.app.detection

import kr.parkingpin.app.domain.detection.MotionActivity
import kr.parkingpin.app.domain.detection.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Play services integer codes are compile-time constants, so this runs on the JVM with
 * no device. Values are spelled out rather than referenced so a silent SDK renumbering
 * would fail here instead of in the field.
 */
class SdkTransitionCodecTest {

    private companion object {
        const val DETECTED_IN_VEHICLE = 0
        const val DETECTED_ON_FOOT = 2
        const val DETECTED_STILL = 3
        const val DETECTED_WALKING = 7
        const val TRANSITION_ENTER = 0
        const val TRANSITION_EXIT = 1
    }

    @Test
    fun subscribedActivities_decodeToDomainEnums() {
        assertEquals(MotionActivity.IN_VEHICLE, SdkTransitionCodec.toMotionActivity(DETECTED_IN_VEHICLE))
        assertEquals(MotionActivity.WALKING, SdkTransitionCodec.toMotionActivity(DETECTED_WALKING))
        assertEquals(MotionActivity.STILL, SdkTransitionCodec.toMotionActivity(DETECTED_STILL))
    }

    @Test
    fun unsubscribedActivity_decodesToNull() {
        // Arrange / Act / Assert — ON_FOOT is not subscribed; it must not masquerade as WALKING.
        assertNull(SdkTransitionCodec.toMotionActivity(DETECTED_ON_FOOT))
    }

    @Test
    fun transitionTypes_decodeBothWays() {
        assertEquals(TransitionKind.ENTER, SdkTransitionCodec.toTransitionKind(TRANSITION_ENTER))
        assertEquals(TransitionKind.EXIT, SdkTransitionCodec.toTransitionKind(TRANSITION_EXIT))
        assertEquals(TRANSITION_ENTER, SdkTransitionCodec.toTransitionType(TransitionKind.ENTER))
        assertEquals(TRANSITION_EXIT, SdkTransitionCodec.toTransitionType(TransitionKind.EXIT))
    }

    @Test
    fun encodingRoundTripsForEverySubscribedActivity() {
        MotionActivity.entries.forEach { activity ->
            val code = SdkTransitionCodec.toDetectedActivityType(activity)
            assertEquals(activity, SdkTransitionCodec.toMotionActivity(code))
        }
    }

    @Test
    fun elapsedRealtimeStamp_anchorsToWallClockByAge() {
        // Arrange — the transition happened 12s before we observed it.
        val nowEpochMillis = 1_700_000_000_000L
        val nowElapsedNanos = 900_000_000_000L
        val eventElapsedNanos = nowElapsedNanos - 12_000_000_000L

        // Act
        val atMillis = SdkTransitionCodec.eventTimeMillis(eventElapsedNanos, nowElapsedNanos, nowEpochMillis)

        // Assert
        assertEquals(nowEpochMillis - 12_000L, atMillis)
    }

    @Test
    fun batchedDelayedDelivery_keepsTheOriginalTransitionTime() {
        // Arrange — Samsung can hold transitions for minutes (§20). The event time must
        // reflect when it happened, not when it was finally delivered.
        val nowEpochMillis = 1_700_000_000_000L
        val nowElapsedNanos = 5_000_000_000_000L
        val fiveMinutesNanos = 300_000_000_000L

        // Act
        val atMillis = SdkTransitionCodec.eventTimeMillis(
            nowElapsedNanos - fiveMinutesNanos,
            nowElapsedNanos,
            nowEpochMillis,
        )

        // Assert
        assertEquals(nowEpochMillis - 300_000L, atMillis)
    }

    @Test
    fun futureDatedStamp_isClampedToNow() {
        // Arrange — clock skew between the GMS process and ours.
        val nowEpochMillis = 1_700_000_000_000L
        val nowElapsedNanos = 900_000_000_000L

        // Act
        val atMillis = SdkTransitionCodec.eventTimeMillis(
            nowElapsedNanos + 5_000_000_000L,
            nowElapsedNanos,
            nowEpochMillis,
        )

        // Assert — never stamp an event ahead of the moment it was observed.
        assertEquals(nowEpochMillis, atMillis)
    }
}

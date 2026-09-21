package com.parkingpin.app.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Transition -> normalized event mapping (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2). */
class ActivityTransitionMapperTest {

    private val atMillis = 1_700_000_000_000L
    private val receivedAtMillis = atMillis + 4_000L

    @Test
    fun vehicleEnter_mapsToEnteredVehicle() {
        // Arrange / Act
        val event = map(MotionActivity.IN_VEHICLE, TransitionKind.ENTER)

        // Assert
        assertEquals(MotionEventKind.ENTERED_VEHICLE, event?.kind)
        assertEquals("vehicle_enter", event?.kind?.wire)
        assertEquals(atMillis, event?.atMillis)
        assertEquals(receivedAtMillis, event?.receivedAtMillis)
    }

    @Test
    fun vehicleExit_mapsToExitedVehicle() {
        assertEquals(MotionEventKind.EXITED_VEHICLE, map(MotionActivity.IN_VEHICLE, TransitionKind.EXIT)?.kind)
    }

    @Test
    fun walkingEnter_mapsToStartedWalking() {
        assertEquals(MotionEventKind.STARTED_WALKING, map(MotionActivity.WALKING, TransitionKind.ENTER)?.kind)
    }

    @Test
    fun stillEnterAndExit_mapToStationaryEvidence() {
        assertEquals(MotionEventKind.BECAME_STATIONARY, map(MotionActivity.STILL, TransitionKind.ENTER)?.kind)
        assertEquals(
            MotionEventKind.STOPPED_BEING_STATIONARY,
            map(MotionActivity.STILL, TransitionKind.EXIT)?.kind,
        )
    }

    @Test
    fun walkingExit_isDropped() {
        // Arrange / Act — walking stops for many reasons and carries no product meaning.
        val event = map(MotionActivity.WALKING, TransitionKind.EXIT)

        // Assert
        assertNull(event)
    }

    @Test
    fun wireValuesMatchTheParityFixtureVocabulary() {
        // Arrange — these strings are shared with iOS and platform-tests/*.json.
        val expected = listOf("vehicle_enter", "vehicle_exit", "walking_enter", "still_enter", "still_exit")

        // Act
        val actual = MotionEventKind.entries.map { it.wire }

        // Assert
        assertEquals(expected, actual)
    }

    private fun map(activity: MotionActivity, transition: TransitionKind) =
        ActivityTransitionMapper.map(activity, transition, atMillis, receivedAtMillis)
}

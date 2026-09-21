package com.parkingpin.app.domain.detection

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The checkpoint is what survives process death, so its serialized round trip is a
 * correctness requirement, not a formality.
 */
class DetectionCheckpointTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun fullyPopulatedCheckpoint_roundTripsUnchanged() {
        // Arrange — every field from docs/04_IOS_IMPLEMENTATION.md §6 populated.
        val original = DetectionCheckpoint(
            state = DetectionState.PARKING_TRANSITION,
            stateEnteredAtMillis = 1_700_000_000_000L,
            lastAutomotiveAtMillis = 1_699_999_000_000L,
            lastReliableLocation = ReliableLocation(37.5665, 126.9780, 12.5f, 1_699_999_500_000L),
            lastLocationAtMillis = 1_699_999_900_000L,
            travelDistanceEstimateMeters = 4231.75,
            candidateId = "candidate-abc",
            revision = 42L,
        )

        // Act
        val restored = json.decodeFromString<DetectionCheckpoint>(json.encodeToString(original))

        // Assert
        assertEquals(original, restored)
    }

    @Test
    fun emptyCheckpoint_roundTripsUnchanged() {
        // Arrange
        val original = DetectionCheckpoint.initial(0L)

        // Act
        val restored = json.decodeFromString<DetectionCheckpoint>(json.encodeToString(original))

        // Assert
        assertEquals(original, restored)
        assertEquals(DetectionState.IDLE, restored.state)
    }

    @Test
    fun everyDetectionState_roundTrips() {
        // Arrange / Act / Assert — parity with the iOS enum must not silently break.
        DetectionState.entries.forEach { state ->
            val original = DetectionCheckpoint.initial(0L).copy(state = state)
            assertEquals(original, json.decodeFromString<DetectionCheckpoint>(json.encodeToString(original)))
        }
    }

    @Test
    fun reliableLocationToString_redactsCoordinates() {
        // Arrange
        val location = ReliableLocation(37.5665, 126.9780, 12.5f, 1L)

        // Act
        val rendered = location.toString()

        // Assert — docs/00_CORE_RULES.md Privacy: coordinates must not reach logs.
        assertFalse(rendered.contains("37.5665"))
        assertFalse(rendered.contains("126.978"))
    }
}

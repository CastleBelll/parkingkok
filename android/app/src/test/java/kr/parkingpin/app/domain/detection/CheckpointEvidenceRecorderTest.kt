package kr.parkingpin.app.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckpointEvidenceRecorderTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun vehicleEvent_stampsLastAutomotiveAtAndBumpsRevision() {
        // Arrange
        val checkpoint = DetectionCheckpoint.initial(t0)

        // Act
        val updated = CheckpointEvidenceRecorder.apply(checkpoint, event(MotionEventKind.ENTERED_VEHICLE, t0 + 10))

        // Assert
        assertEquals(t0 + 10, updated.lastAutomotiveAtMillis)
        assertEquals(1L, updated.revision)
    }

    @Test
    fun walkingEvent_leavesLastAutomotiveAtUntouched() {
        // Arrange
        val checkpoint = DetectionCheckpoint.initial(t0)

        // Act
        val updated = CheckpointEvidenceRecorder.apply(checkpoint, event(MotionEventKind.STARTED_WALKING, t0 + 10))

        // Assert
        assertNull(updated.lastAutomotiveAtMillis)
        assertEquals(1L, updated.revision)
    }

    @Test
    fun outOfOrderVehicleEvent_doesNotRewindLastAutomotiveAt() {
        // Arrange — Samsung can batch and deliver transitions late and out of order (§20).
        val checkpoint = CheckpointEvidenceRecorder.apply(
            DetectionCheckpoint.initial(t0),
            event(MotionEventKind.EXITED_VEHICLE, t0 + 5_000),
        )

        // Act
        val updated = CheckpointEvidenceRecorder.apply(checkpoint, event(MotionEventKind.ENTERED_VEHICLE, t0 + 1_000))

        // Assert — the newest automotive evidence wins, but revision still advances.
        assertEquals(t0 + 5_000, updated.lastAutomotiveAtMillis)
        assertEquals(2L, updated.revision)
    }

    @Test
    fun applying_neverPopulatesLocationFields() {
        // Arrange — location capture is M0B-2; the fields exist but nothing writes them yet.
        val checkpoint = DetectionCheckpoint.initial(t0)

        // Act
        val updated = CheckpointEvidenceRecorder.apply(checkpoint, event(MotionEventKind.ENTERED_VEHICLE, t0))

        // Assert
        assertNull(updated.lastReliableLocation)
        assertNull(updated.lastLocationAtMillis)
        assertEquals(0.0, updated.travelDistanceEstimateMeters, 0.0)
    }

    private fun event(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis + 100)
}

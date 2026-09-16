package com.parkingkok.app.domain.detection

/**
 * Records motion evidence onto a checkpoint.
 *
 * M0B-1 deliberately stops short of the state machine: this only stamps evidence
 * timestamps and bumps the revision. State transitions, candidate creation, and
 * confidence scoring belong to the ParkingDetectionEngine landing in M0B-2
 * (docs/05_PARKING_DETECTION_ENGINE.md §16).
 */
object CheckpointEvidenceRecorder {

    fun apply(checkpoint: DetectionCheckpoint, event: MotionDomainEvent): DetectionCheckpoint {
        val lastAutomotiveAt = when (event.kind) {
            MotionEventKind.ENTERED_VEHICLE,
            MotionEventKind.EXITED_VEHICLE,
            -> maxOfNullable(checkpoint.lastAutomotiveAtMillis, event.atMillis)

            else -> checkpoint.lastAutomotiveAtMillis
        }
        return checkpoint.copy(
            lastAutomotiveAtMillis = lastAutomotiveAt,
            revision = checkpoint.revision + 1,
        )
    }

    private fun maxOfNullable(current: Long?, candidate: Long): Long =
        if (current == null || candidate > current) candidate else current
}

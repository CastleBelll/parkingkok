package com.parkingkok.app.detection

import com.parkingkok.app.core.Clock
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.domain.detection.ActivityTransitionMapper
import com.parkingkok.app.domain.detection.CheckpointEvidenceRecorder
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionActivity
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.TransitionKind

/**
 * Application-layer handler for received transitions: normalize, persist, update
 * checkpoint. The BroadcastReceiver does nothing but call this
 * (docs/16_CODING_STANDARDS.md §2: no business logic in receivers).
 *
 * Deliberately not the detection engine. Once the engine lands in M0B-2 it consumes the
 * events this writes; M0B-1 stops at durable, ordered ingestion.
 */
class TransitionEventIngestor(
    private val store: DetectionStateStore,
    private val clock: Clock,
) {

    /** One raw transition, already decoded off the SDK types. */
    data class RawTransition(
        val activity: MotionActivity,
        val transition: TransitionKind,
        val atMillis: Long,
    )

    suspend fun ingest(transitions: List<RawTransition>) {
        val receivedAtMillis = clock.nowEpochMillis()
        transitions
            .mapNotNull { raw ->
                ActivityTransitionMapper.map(
                    activity = raw.activity,
                    transition = raw.transition,
                    atMillis = raw.atMillis,
                    receivedAtMillis = receivedAtMillis,
                )
            }
            .forEach { event -> persist(event) }
    }

    private suspend fun persist(event: MotionDomainEvent) {
        val current = store.readCheckpointOnce()
            ?: DetectionCheckpoint.initial(clock.nowEpochMillis())
        store.appendEventAndCheckpoint(event, CheckpointEvidenceRecorder.apply(current, event))
    }
}

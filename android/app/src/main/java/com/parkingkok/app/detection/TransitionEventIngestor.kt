package com.parkingkok.app.detection

import com.parkingkok.app.core.Clock
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.domain.detection.ActivityTransitionMapper
import com.parkingkok.app.domain.detection.CheckpointEvidenceRecorder
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionActivity
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.TransitionKind
import com.parkingkok.app.trace.NoOpTraceRecording
import com.parkingkok.app.trace.TraceRecording

/**
 * Application-layer handler for received transitions: normalize, persist, update
 * checkpoint, then let the location session react. The BroadcastReceiver does nothing but
 * call this (docs/16_CODING_STANDARDS.md §2: no business logic in receivers).
 *
 * Deliberately not the detection engine. Once the engine lands in M3 it consumes the
 * events this writes; here ingestion stops at durable, ordered evidence plus the bounded
 * capture decision (docs/04_ANDROID_IMPLEMENTATION.md §2).
 */
class TransitionEventIngestor(
    private val store: DetectionStateStore,
    private val clock: Clock,
    private val locationSessionController: FusedLocationSessionController,
    private val traceRecorder: TraceRecording = NoOpTraceRecording,
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
            .forEach { event ->
                persist(event)
                // After persisting, never before: if the process dies here the evidence
                // survives and the next reconcile re-derives the session from it.
                locationSessionController.onMotionEvent(event)
                // Last, and best-effort: the trace is field evidence, and a recorder that
                // could delay or fail detection would be the wrong trade
                // (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9).
                traceRecorder.recordMotion(event)
            }
    }

    private suspend fun persist(event: MotionDomainEvent) {
        val current = store.readCheckpointOnce()
            ?: DetectionCheckpoint.initial(clock.nowEpochMillis())
        store.appendEventAndCheckpoint(event, CheckpointEvidenceRecorder.apply(current, event))
    }
}

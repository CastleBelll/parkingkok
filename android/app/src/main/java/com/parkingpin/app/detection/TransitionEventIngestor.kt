package com.parkingpin.app.detection

import com.parkingpin.app.core.Clock
import com.parkingpin.app.data.DetectionStateStore
import com.parkingpin.app.domain.detection.ActivityTransitionMapper
import com.parkingpin.app.domain.detection.CheckpointEvidenceRecorder
import com.parkingpin.app.domain.detection.DetectionCheckpoint
import com.parkingpin.app.domain.detection.MotionActivity
import com.parkingpin.app.domain.detection.MotionDomainEvent
import com.parkingpin.app.domain.detection.TransitionKind
import com.parkingpin.app.trace.NoOpTraceRecording
import com.parkingpin.app.trace.TraceRecording

/**
 * Application-layer handler for received transitions: normalize, persist, let the location
 * session react, then move the state machine. The BroadcastReceiver does nothing but call
 * this (docs/16_CODING_STANDARDS.md §2: no business logic in receivers).
 *
 * The order is the invariant. Evidence is durable before anything acts on it, so a process
 * death between two steps loses a decision and never an observation; the bounded capture
 * decision comes next because it is what produces the fixes the engine will want; and the
 * engine runs last, because it is the only step that can post a notification and a
 * notification about a trip whose evidence was lost would be unexplainable.
 */
class TransitionEventIngestor(
    private val store: DetectionStateStore,
    private val clock: Clock,
    private val locationSessionController: FusedLocationSessionController,
    /**
     * Nullable so the M0B-1 compositions that predate the engine — and the tests that pin
     * ingestion alone — still build one. A null runtime means evidence is recorded and
     * nothing transitions, which is exactly what this class did before §3a landed.
     */
    private val detectionRuntime: ParkingDetectionRuntime? = null,
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
                // docs/05 §3a. The capture decision above shapes the request; this decides
                // what the trip *is*, and is the only step that can reach the user.
                detectionRuntime?.handleMotion(event)
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

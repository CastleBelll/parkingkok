package com.parkingkok.app.detection

import android.util.Log
import com.parkingkok.app.core.Clock
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.MotionEventKind
import com.parkingkok.app.domain.detection.ReliableLocation
import com.parkingkok.app.domain.location.DrivingConfirmationGuard
import com.parkingkok.app.domain.location.GeoDistance
import com.parkingkok.app.domain.location.DrivingSessionEvidence
import com.parkingkok.app.domain.location.LocationCaptureModePolicy
import com.parkingkok.app.domain.location.LocationQualityEntry
import com.parkingkok.app.domain.location.LocationQualityRing
import com.parkingkok.app.domain.location.LocationQualitySample
import com.parkingkok.app.domain.location.LocationSample
import com.parkingkok.app.domain.location.LocationSessionAction
import com.parkingkok.app.domain.location.LocationSessionMode
import com.parkingkok.app.domain.location.LocationSessionPlanner
import com.parkingkok.app.domain.location.LocationSessionState
import com.parkingkok.app.domain.location.ReliableLocationDecision
import com.parkingkok.app.domain.location.ReliableLocationSelector
import com.parkingkok.app.trace.NoOpTraceRecording
import com.parkingkok.app.trace.TraceRecording
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * Drives the bounded Fused Location session
 * (docs/04_ANDROID_IMPLEMENTATION.md §2 modes, §8 location reliability).
 *
 * This is the application layer: it holds no SDK types and no UI. It decides *when* a
 * bounded capture should exist and folds each arriving fix into the checkpoint. Every rule
 * it applies — mode selection, reliable-fix admission, the §7 driving guard, session
 * lifetime — is a pure domain object, so the behaviour that matters is unit tested without
 * Play services on the JVM.
 *
 * The [Mutex] serializes every entry point. A location batch, a transition broadcast, and
 * an app-start reconcile can land in the same process at once, and without it two of them
 * could each read "no session" and each issue a request.
 *
 * Not the detection engine. State-machine transitions, candidate creation, notifications,
 * and confidence scoring are M3 (docs/05_PARKING_DETECTION_ENGINE.md §16).
 */
class FusedLocationSessionController(
    private val store: DetectionStateStore,
    private val registrar: LocationSessionRegistrar,
    private val clock: Clock,
    private val ring: LocationQualityRing = LocationQualityRing(),
    private val traceRecorder: TraceRecording = NoOpTraceRecording,
) {

    private val mutex = Mutex()

    /** Oldest first. Volatile — see [LocationQualityRing]. */
    fun qualityHistory(): List<LocationQualityEntry> = ring.snapshot()

    fun hasForegroundLocationPermission(): Boolean = registrar.hasForegroundLocationPermission()

    fun hasBackgroundLocationPermission(): Boolean = registrar.hasBackgroundLocationPermission()

    /**
     * Brings the registration in line with the recorded mode, without changing it.
     *
     * Called on process start and on boot. This is the third leak guard: a record left
     * behind by a process that died mid-session is stopped here once its deadline has
     * passed (docs/04 §6 recovery; see [LocationSessionPlanner] for the other two).
     */
    suspend fun reconcile(): LocationSessionState = mutex.withLock {
        val state = store.readLocationSessionStateOnce()
        applyPlan(stored = state, state = state, desiredMode = state.mode, nowMillis = clock.nowEpochMillis())
    }

    /** A normalized transition arrived. Decides what the capture should look like now. */
    suspend fun onMotionEvent(event: MotionDomainEvent): LocationSessionState = mutex.withLock {
        val now = clock.nowEpochMillis()
        val state = store.readLocationSessionStateOnce()
        val desiredMode = LocationCaptureModePolicy.modeFor(event.kind, state.mode)
        applyPlan(
            stored = state,
            state = state.copy(evidence = evidenceAfter(state.evidence, event)),
            desiredMode = desiredMode,
            nowMillis = now,
        )
    }

    /**
     * Explicit mode override for the P0 diagnostics screen, so bounded capture — and the
     * fact that it works with no foreground service — can be exercised without a drive.
     * Not a product path.
     */
    suspend fun setDesiredMode(mode: LocationSessionMode): LocationSessionState = mutex.withLock {
        val now = clock.nowEpochMillis()
        val state = store.readLocationSessionStateOnce()
        val seeded = if (mode == LocationSessionMode.IDLE || state.evidence != null) {
            state
        } else {
            state.copy(
                evidence = DrivingSessionEvidence(
                    vehicleFirstSeenAtMillis = now,
                    lastVehicleEvidenceAtMillis = now,
                ),
            )
        }
        applyPlan(stored = state, state = seeded, desiredMode = mode, nowMillis = now)
    }

    /**
     * One Play services delivery. Folds every fix into the checkpoint, then re-plans.
     *
     * Re-planning here is what keeps the session bounded without a service: a delivery is
     * this process's only guaranteed scheduled moment, so it is where the deadline is
     * enforced, the request is renewed, and a spent update budget ends the session.
     */
    suspend fun onLocationBatch(samples: List<LocationSample>): LocationSessionState = mutex.withLock {
        val now = clock.nowEpochMillis()
        // Filled by the fold and applied afterwards: a DataStore transform may be retried,
        // and the ring is a side effect that must not be applied twice.
        var observed: List<LocationQualityEntry> = emptyList()
        var traced: List<TracedFix> = emptyList()
        val ingested = store.updateLocationSessionAndCheckpoint { stored, checkpoint ->
            val fold = ingest(stored, checkpoint, samples, now)
            observed = fold.observed
            traced = fold.traced
            fold.state to fold.checkpoint
        }
        observed.forEach { ring.record(it.sample, it.dropReason) }
        // Best-effort and after the durable write, for the same reason the transition path
        // records last: the trace is field evidence, not part of detection.
        traced.forEach {
            traceRecorder.recordLocation(
                atMillis = it.sample.atMillis,
                accuracyM = it.sample.horizontalAccuracyM,
                speedMps = it.sample.speedMps,
                distanceFromPreviousM = it.distanceFromPreviousM,
            )
        }

        // §7's guard is the only thing that promotes a confirmation window to a real
        // driving session. One transition never does (docs/05 §7).
        val desiredMode = if (ingested.drivingConfirmed) {
            LocationCaptureModePolicy.promoteOnDrivingConfirmed(ingested.mode)
        } else {
            ingested.mode
        }
        applyPlan(stored = ingested, state = ingested, desiredMode = desiredMode, nowMillis = now)
    }

    /** What one delivery folded into. [checkpoint] is null when no fix was admitted. */
    private data class IngestResult(
        val state: LocationSessionState,
        val checkpoint: DetectionCheckpoint?,
        val observed: List<LocationQualityEntry>,
        val traced: List<TracedFix>,
    )

    /**
     * One fix as the trace records it — quality and a distance, never a position
     * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9).
     *
     * The projection happens inside the fold because this is the only place that holds two
     * coordinates at the same time, and it is where they can be turned into a scalar and
     * dropped. Nothing downstream of here can leak a position, because nothing downstream
     * of here has one.
     */
    private data class TracedFix(
        val sample: LocationQualitySample,
        val distanceFromPreviousM: Double?,
    )

    /**
     * Pure fold of one delivery onto the stored state and checkpoint.
     *
     * Runs inside the DataStore transform so the counters, the session record, and the
     * checkpoint land as a single atomic write: a checkpoint holding a reliable fix that
     * the counters never saw would make the P0 numbers unreadable. Pure for the same
     * reason — the transform can be retried, so the ring entries are returned rather than
     * recorded here.
     */
    private fun ingest(
        stored: LocationSessionState,
        storedCheckpoint: DetectionCheckpoint?,
        samples: List<LocationSample>,
        nowMillis: Long,
    ): IngestResult {
        var counters = stored.counters.copy(
            deliveryCount = stored.counters.deliveryCount + 1,
            sampleCount = stored.counters.sampleCount + samples.size,
        )
        var evidence = stored.evidence
        var working = storedCheckpoint ?: DetectionCheckpoint.initial(nowMillis)
        var checkpointChanged = false
        val observed = mutableListOf<LocationQualityEntry>()
        val traced = mutableListOf<TracedFix>()

        // What the trace measures `distanceFromPreviousM` from: the previous fix in this
        // recording, whatever the admission guards made of it. It starts at the reliable
        // fix the checkpoint already holds, but only inside an active session — between
        // trips that point belongs to the last parking spot, and anchoring on it would
        // open every recording with the whole distance travelled since then.
        var traceAnchor: ReliableLocation? = working.lastReliableLocation.takeIf { stored.record != null }

        // A batch describes the window ending at its newest fix, not the instant it was
        // handed over, so that is what its interior is judged fresh against. Clamped to
        // now so a future-dated fix cannot vouch for the rest of the batch.
        val sessionReferenceMillis = samples.maxOfOrNull { it.atMillis }?.coerceAtMost(nowMillis) ?: nowMillis

        // Oldest first, so the selector's "newer wins" sees the batch in real order.
        for (sample in samples.sortedBy { it.atMillis }) {
            val decision = ReliableLocationSelector.select(
                current = working.lastReliableLocation,
                sample = sample,
                nowMillis = nowMillis,
                sessionReferenceMillis = sessionReferenceMillis,
            )
            observed += LocationQualityEntry(
                sample.quality,
                (decision as? ReliableLocationDecision.Rejected)?.reason,
            )
            traced += TracedFix(
                sample = sample.quality,
                distanceFromPreviousM = traceAnchor?.let {
                    GeoDistance.meters(it.latitude, it.longitude, sample.latitude, sample.longitude)
                },
            )
            // Rejected fixes still anchor the next distance — the trace describes what the
            // device actually saw, and the converter decides what the engine owed it. An
            // invalid accuracy does not: Fused Location is saying that is not a fix at all.
            if (sample.quality.isValid) traceAnchor = sample.toReliableLocation()
            counters = counters.copy(
                lastSampleAccuracyM = sample.horizontalAccuracyM,
                lastSampleAtMillis = sample.atMillis,
            )
            // §7's movement clause reads every fix, whatever the reliability bar made of
            // it. Underground the accuracies that bar rejects are the only fixes there
            // are, and gating movement evidence on it would make confirmation impossible
            // exactly where this product lives (docs/05 §7).
            evidence = evidence?.recordingFix(sample)

            when (decision) {
                is ReliableLocationDecision.Rejected ->
                    counters = counters.recordingDrop(decision.reason, nowMillis - sample.atMillis)

                is ReliableLocationDecision.Accepted -> {
                    counters = counters.copy(admittedCount = counters.admittedCount + 1)
                    // Distance is session-scoped. The first admitted fix of a session
                    // contributes none: the fix it would be measured from belongs to the
                    // previous trip, and counting that gap would credit this drive with
                    // the whole distance since the last parking spot.
                    evidence = evidence?.let { current ->
                        val travelled = current.travelDistanceMeters +
                            if (current.reliableSampleCount == 0) 0.0 else (decision.movedMeters ?: 0.0)
                        current.copy(
                            reliableSampleCount = current.reliableSampleCount + 1,
                            travelDistanceMeters = travelled,
                        )
                    }
                    working = working.copy(
                        lastReliableLocation = decision.location,
                        lastLocationAtMillis = sample.atMillis,
                        // Mirrored from the session evidence so the checkpoint keeps the
                        // field iOS has (docs/04_IOS_IMPLEMENTATION.md §6 parity). Left
                        // alone when there is no session: a fix arriving after a stop
                        // still updates the reliable point, but it belongs to no trip and
                        // must not reset the distance the last one measured.
                        travelDistanceEstimateMeters = evidence?.travelDistanceMeters
                            ?: working.travelDistanceEstimateMeters,
                        revision = working.revision + 1,
                    )
                    checkpointChanged = true
                }
            }
        }

        val confirmation = evidence?.let { DrivingConfirmationGuard.evaluate(it, nowMillis) }

        val updated = stored.copy(
            record = stored.record?.copy(
                deliveredUpdateCount = stored.record.deliveredUpdateCount + samples.size,
            ),
            evidence = evidence,
            counters = counters,
            drivingConfirmed = confirmation?.confirmed ?: stored.drivingConfirmed,
            drivingReasonCodes = confirmation?.reasonCodes?.map { it.wire } ?: stored.drivingReasonCodes,
        )
        return IngestResult(updated, working.takeIf { checkpointChanged }, observed, traced)
    }

    private fun evidenceAfter(
        current: DrivingSessionEvidence?,
        event: MotionDomainEvent,
    ): DrivingSessionEvidence? = when (event.kind) {
        // Vehicle evidence, in both directions: an exit is still evidence that a vehicle
        // session was happening, and §7's "recent vehicle evidence" clause needs it.
        MotionEventKind.ENTERED_VEHICLE,
        MotionEventKind.EXITED_VEHICLE,
        -> current?.copy(
            lastVehicleEvidenceAtMillis = max(current.lastVehicleEvidenceAtMillis, event.atMillis),
        ) ?: DrivingSessionEvidence(
            vehicleFirstSeenAtMillis = event.atMillis,
            lastVehicleEvidenceAtMillis = event.atMillis,
        )

        else -> current
    }

    /**
     * Executes the planner's decision and records exactly what happened.
     *
     * A failed request clears the record rather than keeping it: believing a registration
     * exists when it does not is the one error that makes every later plan wrong, and it
     * is the error that leaks a session.
     */
    private suspend fun applyPlan(
        /** Exactly what was read from the store, so an unchanged plan can skip the write. */
        stored: LocationSessionState,
        /** [stored] plus whatever the caller already folded in, such as new evidence. */
        state: LocationSessionState,
        desiredMode: LocationSessionMode,
        nowMillis: Long,
    ): LocationSessionState {
        val action = LocationSessionPlanner.plan(
            current = state.record,
            desiredMode = desiredMode,
            permissionGranted = registrar.hasForegroundLocationPermission(),
            nowMillis = nowMillis,
        )
        // Mode names and the decision only. No coordinate exists on this path.
        Log.i(TAG, "plan mode=${state.mode} desired=$desiredMode -> ${action.describe()}")

        val next = when (action) {
            LocationSessionAction.None -> state

            is LocationSessionAction.Start -> {
                val isNewSession = state.record == null
                when (val failure = registrar.request(action.config)) {
                    null -> state.copy(
                        record = action.record,
                        // A fresh session starts from zero: the previous trip's counters
                        // would make this one's numbers unreadable.
                        counters = if (isNewSession) state.counters.startingNewSession() else state.counters,
                        lastStopReason = null,
                        lastFailure = null,
                    )

                    else -> state.copy(record = null, lastFailure = discardRegistration(failure))
                }
            }

            is LocationSessionAction.Renew -> when (val failure = registrar.request(action.config)) {
                null -> state.copy(record = action.record, lastFailure = null)
                else -> state.copy(record = null, lastFailure = discardRegistration(failure))
            }

            is LocationSessionAction.Stop -> {
                val failure = registrar.remove()
                state.copy(
                    record = null,
                    evidence = null,
                    drivingConfirmed = false,
                    drivingReasonCodes = emptyList(),
                    lastStopReason = action.reason,
                    lastFailure = failure,
                    counters = state.counters.copy(sessionsStopped = state.counters.sessionsStopped + 1),
                )
            }
        }

        if (next == stored) return stored
        return store.updateLocationSessionState { next }
    }

    /**
     * Clearing the record after a failed request is not enough on its own.
     *
     * A failed *renewal* leaves the previous Play services request in place, and this
     * process has just forgotten it exists — so nothing would ever stop it. Its own
     * `durationMillis` bounds the damage to one request's worth, but an explicit removal
     * ends it now. The request failure is what gets reported either way: a removal that
     * also fails says nothing the caller can act on.
     */
    private suspend fun discardRegistration(requestFailure: String): String {
        registrar.remove()
        return requestFailure
    }

    private fun LocationSessionAction.describe(): String = when (this) {
        LocationSessionAction.None -> "None"
        is LocationSessionAction.Start -> "Start(${record.mode})"
        is LocationSessionAction.Renew -> "Renew(${record.mode})"
        is LocationSessionAction.Stop -> "Stop($reason)"
    }

    private companion object {
        const val TAG = "ParkingkokLocation"
    }
}

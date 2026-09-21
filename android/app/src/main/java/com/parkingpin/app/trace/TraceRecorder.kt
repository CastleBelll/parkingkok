package com.parkingpin.app.trace

import android.util.Log
import com.parkingpin.app.data.DetectionStateStore
import com.parkingpin.app.domain.detection.MotionDomainEvent
import com.parkingpin.app.domain.trace.LocationQualityBucket
import com.parkingpin.app.domain.trace.TraceDeviceInfo
import com.parkingpin.app.domain.trace.TraceEvent
import com.parkingpin.app.domain.trace.TraceLabel
import com.parkingpin.app.domain.trace.TraceLabelPrompt
import com.parkingpin.app.domain.trace.TraceSession
import com.parkingpin.app.domain.trace.TraceSessionBoundaryPolicy
import com.parkingpin.app.domain.trace.TraceSessionSplit
import com.parkingpin.app.domain.trace.TraceSplitResult
import com.parkingpin.app.domain.trace.TraceSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * What the detection path is allowed to know about trace recording.
 *
 * Deliberately narrow, and deliberately carries no coordinate on any parameter: the
 * detection path cannot hand the recorder a position even by mistake, which is the same
 * structural guarantee [com.parkingpin.app.domain.trace.TraceEvent] gives the file format.
 * Distance is passed in already computed, by the one place that legitimately holds two
 * coordinates at once.
 */
interface TraceRecording {

    suspend fun recordMotion(event: MotionDomainEvent)

    /** @param distanceFromPreviousM already computed; null when there was no previous fix. */
    suspend fun recordLocation(
        atMillis: Long,
        accuracyM: Float,
        speedMps: Float?,
        distanceFromPreviousM: Double?,
    )

    /** Ends the open session, so the next event starts a new one. */
    suspend fun closeOpenSession()
}

/** Used wherever recording is not wired, so a collaborator is never null. */
object NoOpTraceRecording : TraceRecording {
    override suspend fun recordMotion(event: MotionDomainEvent) = Unit
    override suspend fun recordLocation(
        atMillis: Long,
        accuracyM: Float,
        speedMps: Float?,
        distanceFromPreviousM: Double?,
    ) = Unit
    override suspend fun closeOpenSession() = Unit
}

/**
 * Records ordinary movement as trace sessions
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9).
 *
 * **Append-only, event-driven, never polled.** Every entry point is called from something
 * that already happened — a transition broadcast, a location delivery, a toggle — so the
 * recorder costs nothing between events. It starts no timer, holds no wakelock and asks
 * for no callback of its own, which is what keeps §9's "no meaningful battery load"
 * honest and what keeps it out of the way of the Android P0 constraint against a standing
 * foreground service.
 *
 * **Best-effort, always.** Nothing here throws at the caller: a store failure is kept and
 * reported through the diagnostics summary instead. A diagnostics recorder that can fail a
 * detection callback would be worse than no recorder at all.
 *
 * **The open session lives on disk, not in this object.** Location batches and transitions
 * arrive by PendingIntent, and the process routinely dies between them, so the open
 * session is re-read from the store on every append and its id is kept in DataStore beside
 * the detection state. The [Mutex] only serializes writers inside one process; the file
 * rename in [TraceStore] is what makes a torn read impossible across them.
 */
class TraceRecorder(
    private val store: TraceStore,
    private val stateStore: DetectionStateStore,
    private val device: TraceDeviceInfo,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    /**
     * Asked for a label whenever a viable session stops growing (§9 labelling). The no-op
     * default is what every test that is not about prompting uses.
     */
    private val prompter: TraceLabelPrompting = NoOpTraceLabelPrompting,
) : TraceRecording {

    private val mutex = Mutex()

    /**
     * Volatile, like the location quality ring: it describes this process's last attempt,
     * and a failure worth acting on repeats on the next event anyway.
     */
    @Volatile
    private var lastFailure: String? = null

    override suspend fun recordMotion(event: MotionDomainEvent) {
        // event.atMillis, never event.receivedAtMillis — see TraceEvent.atMillis.
        append(event.atMillis) { listOf(TraceEvent.motion(event.kind, event.atMillis)) }
    }

    override suspend fun recordLocation(
        atMillis: Long,
        accuracyM: Float,
        speedMps: Float?,
        distanceFromPreviousM: Double?,
    ) {
        append(atMillis) { session ->
            val fix = TraceEvent.location(
                atMillis = atMillis,
                accuracyM = accuracyM,
                speedMps = speedMps,
                // "Previous" means previous *in this recording*. The caller measures from
                // the last fix it saw, which may belong to the trip before this one — and
                // an opening event claiming 30 km of travel would be read by the converter
                // as movement that happened inside the session. The first fix of a
                // recording has nothing to measure from, and says so.
                distanceFromPreviousM = distanceFromPreviousM.takeIf { session.lastLocationEvent() != null },
            )
            val degraded = degradationAfter(session, accuracyM, atMillis)
            if (degraded == null) listOf(fix) else listOf(fix, degraded)
        }
    }

    override suspend fun closeOpenSession() {
        mutex.withLock {
            // The file is already complete on disk; closing is purely forgetting which one
            // was open, so the next event cannot append to a finished trip.
            runBestEffort {
                // Opting out is a rotation in every sense §9's viability rule cares about:
                // nothing more can join this session, so "더 붙을 수 있다" stops applying —
                // including being worth a label prompt.
                closeSession(openSession())
                stateStore.setTraceOpenSessionId(null)
            }
        }
    }

    /** Applies a human label to a recorded session. @return null on success, or a reason. */
    suspend fun setLabel(sessionId: String, label: TraceLabel): String? = mutex.withLock {
        val session = store.read(sessionId) ?: return@withLock "NotFound"
        store.write(session.copy(label = label)).also { lastFailure = it }
    }

    /**
     * Cuts a closed session in two at the event the user picked
     * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "사람이 세션을 나눈다").
     *
     * @return null when the split happened, or the reason it was refused. A refusal is an
     *   ordinary outcome here, not an error: §9 forbids a cut that would leave a one-event
     *   fragment, and the person choosing the point cannot know that until they choose it.
     */
    suspend fun splitSession(sessionId: String, atEventIndex: Int): TraceSplitResult.Refusal? = mutex.withLock {
        // Openness is read here and not in the store: which session is being appended to
        // lives beside the detection state, because the recording process dies between
        // PendingIntent deliveries and the pointer has to survive that.
        val openSessionId = stateStore.readTraceOpenSessionIdOnce()
        if (openSessionId == sessionId) return@withLock TraceSplitResult.SessionIsOpen
        val parent = store.read(sessionId) ?: return@withLock TraceSplitResult.SessionNotFound

        try {
            when (val outcome = TraceSessionSplit.split(parent, atEventIndex, sessionIdFactory)) {
                is TraceSplitResult.Refusal -> outcome
                is TraceSplitResult.Fragments -> {
                    val failure = store.replace(sessionId, listOf(outcome.leading, outcome.trailing))
                    if (failure != null) {
                        lastFailure = failure
                        return@withLock TraceSplitResult.StoreFailure(failure)
                    }
                    // One session became two, so the rolling cap is now the thing most
                    // likely to be wrong. Not a drop of its own — splitting evicts
                    // nothing; the cap decides that, and counts it if it happens.
                    val discarded = store.prune(keepSessionId = openSessionId)
                    if (discarded > 0) stateStore.addTraceDiscardedSessions(discarded)
                    null
                }
            }
        } catch (cancellation: CancellationException) {
            // Never swallowed, for the same reason the recording path does not swallow it:
            // the caller's scope is being torn down, and turning that into a refusal would
            // strand the coroutine that owns the screen.
            throw cancellation
        } catch (error: Exception) {
            // Same contract as the recording path: the class name only, because the
            // message of an arbitrary exception is not something we can promise stays
            // coordinate-free.
            lastFailure = error.javaClass.simpleName
            Log.e(TAG, "trace split failed: ${error.javaClass.simpleName}")
            TraceSplitResult.StoreFailure(error.javaClass.simpleName)
        }
    }

    /** Newest first. For the labelling UI. */
    fun sessions(): List<TraceSession> = store.list()

    /** Which session may still grow, so the labelling UI can refuse to cut it. */
    suspend fun openSessionId(): String? = stateStore.readTraceOpenSessionIdOnce()

    /**
     * Counts for the diagnostics report.
     *
     * Reads every trace file, which is why it is called from the export and never from an
     * append: the recording path must not pay for the reporting path.
     */
    suspend fun summary(): TraceSummary {
        val sessions = store.list()
        // Only the sessions that carry a measurement. A trace recorded before gap
        // measurement landed was never measured, and folding it in as zero would
        // understate exactly the tail the 30-minute retune is looking for.
        val measured = sessions.mapNotNull { it.gapStats }
        return TraceSummary(
            sessionCount = sessions.size,
            eventCount = sessions.sumOf { it.events.size },
            discardedSessionCount = stateStore.readTraceDiscardedSessionCountOnce(),
            nonViableDropCount = stateStore.readTraceNonViableDropCountOnce(),
            unlabelledSessionCount = sessions.count { !it.isLabelled },
            labelPromptSuppressedCount = stateStore.readTraceLabelPromptSuppressedCountOnce(),
            measuredSessionCount = measured.size,
            maxGapMillis = measured.maxOfOrNull { it.maxGapMillis } ?: 0L,
            sessionsOver10MinGapCount = measured.count { it.gapsOver10MinCount > 0 },
            sessionsOver20MinGapCount = measured.count { it.gapsOver20MinCount > 0 },
            lastFailure = lastFailure,
        )
    }

    /**
     * The one write path: resolve the open session, rotate if the boundary says so, append,
     * persist, then apply the rolling cap.
     *
     * The session file is written before the open-session pointer moves. A process death
     * between the two leaves a complete trace on disk and a pointer at the previous
     * session, so the next event rotates instead of appending — evidence is kept and the
     * boundary is at worst early, which is the right way round for a recording.
     */
    private suspend fun append(atMillis: Long, events: (TraceSession) -> List<TraceEvent>) {
        mutex.withLock {
            runBestEffort {
                val open = openSession()
                val rotation = TraceSessionBoundaryPolicy.rotationReason(open, atMillis)
                if (rotation != null) Log.i(TAG, "trace session rotated: $rotation")

                val base = if (open == null || rotation != null) {
                    TraceSession.opening(sessionIdFactory(), device, atMillis)
                } else {
                    open
                }

                val updated = events(base).fold(base) { session, event -> session.appending(event) }
                lastFailure = store.write(updated)
                if (lastFailure != null) return@runBestEffort

                if (updated.sessionId != open?.sessionId) {
                    stateStore.setTraceOpenSessionId(updated.sessionId)
                    // Only now that the replacement is durable and the pointer has moved:
                    // a write failure above leaves the closing session on disk, and the
                    // next event rotates past it and judges it again.
                    if (rotation != null) closeSession(open)
                }
                val discarded = store.prune(keepSessionId = updated.sessionId)
                if (discarded > 0) stateStore.addTraceDiscardedSessions(discarded)
            }
        }
    }

    /**
     * Settles a session that has stopped growing: §9's viability rule first, then the label
     * prompt on whatever survived it.
     *
     * A discarded session is never prompted for — the file is about to be deleted, so the
     * answer would have nowhere to go. That is also why the two decisions are one function
     * rather than two calls a future edit could reorder.
     *
     * **Rotation — or the user closing recording — is the only moment this may be asked.**
     * Every session passes through one event on its way to two, and on Android the open
     * session lives on disk precisely because the process dies between two PendingIntent
     * deliveries: refusing to write the first event would leave nothing to reopen, and a
     * whole trip would be cut into first events that were each discarded in turn.
     *
     * The file goes; the duplicate-suppression state deliberately does not move backwards.
     * Those events were already seen, and the two things that decide whether an arriving
     * event is new — the [com.parkingpin.app.domain.detection.DetectionCheckpoint] and
     * [com.parkingpin.app.domain.location.LocationDiagnosticsCounters.lastSampleAtMillis],
     * which drives the `NOT_NEWER` guard — live in DataStore and are untouched here. Rewinding
     * either would let Fused Location's cached fixes walk straight back in and rebuild the
     * session that was just thrown away. The only pointer naming the deleted file is the
     * open-session id, and both callers move it in the same critical section.
     */
    private suspend fun closeSession(closing: TraceSession?) {
        if (closing == null) return

        if (!TraceSessionBoundaryPolicy.isViable(closing.events.size)) {
            // A file already gone means a previous call did it, and the counter must not
            // move twice for the same session.
            if (!store.delete(closing.sessionId)) return
            stateStore.addTraceNonViableDrops(1)
            Log.i(TAG, "trace discarded a non-viable session: ${closing.events.size} event(s)")
            return
        }

        // `of` refuses a session with no motion event: three location fixes are not a
        // question anyone could answer (§9 labelling).
        TraceLabelPrompt.of(closing)?.let { prompter.requestPrompt(it) }
    }

    private suspend fun openSession(): TraceSession? =
        stateStore.readTraceOpenSessionIdOnce()?.let { store.read(it) }

    /**
     * A `location_quality_degraded` event, or null when quality did not get worse.
     *
     * Judged against the previous `location` event in the same session, so the comparison
     * is always between two fixes of one trip — a bucket carried across a rotation would
     * report the last trip's accuracy degrading into this one's.
     *
     * An invalid accuracy produces no event in either position: it is not a quality level,
     * it is Fused Location saying the fix is not a fix, and
     * [LocationQualityBucket.of] refuses to give it a bucket.
     */
    private fun degradationAfter(session: TraceSession, accuracyM: Float, atMillis: Long): TraceEvent? {
        val previous = session.lastLocationEvent()?.accuracy?.let { LocationQualityBucket.of(it) } ?: return null
        val current = LocationQualityBucket.of(accuracyM) ?: return null
        return if (current.isWorseThan(previous)) {
            TraceEvent.qualityDegraded(atMillis, previous, current)
        } else {
            null
        }
    }

    /**
     * Swallows everything the store could not.
     *
     * [TraceStore] already returns its expected failures as strings, so reaching this means
     * something unanticipated — and a diagnostics recorder is never a reason for a
     * detection callback to die.
     */
    private inline fun runBestEffort(block: () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            // Never swallowed: the caller's scope is being torn down, and turning that
            // into a recorded "failure" would strand the coroutine that owns the callback.
            throw cancellation
        } catch (error: Exception) {
            // The class name only. No coordinate exists on this path, and the message of
            // an arbitrary exception is not something we can promise stays coordinate-free.
            lastFailure = error.javaClass.simpleName
            Log.e(TAG, "trace recording failed: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ParkingkokTrace"
    }
}

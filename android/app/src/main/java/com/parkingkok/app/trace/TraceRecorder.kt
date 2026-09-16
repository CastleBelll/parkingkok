package com.parkingkok.app.trace

import android.util.Log
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.trace.LocationQualityBucket
import com.parkingkok.app.domain.trace.TraceDeviceInfo
import com.parkingkok.app.domain.trace.TraceEvent
import com.parkingkok.app.domain.trace.TraceLabel
import com.parkingkok.app.domain.trace.TraceSession
import com.parkingkok.app.domain.trace.TraceSessionBoundaryPolicy
import com.parkingkok.app.domain.trace.TraceSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * What the detection path is allowed to know about trace recording.
 *
 * Deliberately narrow, and deliberately carries no coordinate on any parameter: the
 * detection path cannot hand the recorder a position even by mistake, which is the same
 * structural guarantee [com.parkingkok.app.domain.trace.TraceEvent] gives the file format.
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
            runBestEffort { stateStore.setTraceOpenSessionId(null) }
        }
    }

    /** Applies a human label to a recorded session. @return null on success, or a reason. */
    suspend fun setLabel(sessionId: String, label: TraceLabel): String? = mutex.withLock {
        val session = store.read(sessionId) ?: return@withLock "NotFound"
        store.write(session.copy(label = label)).also { lastFailure = it }
    }

    /** Newest first. For the labelling UI. */
    fun sessions(): List<TraceSession> = store.list()

    /**
     * Counts for the diagnostics report.
     *
     * Reads every trace file, which is why it is called from the export and never from an
     * append: the recording path must not pay for the reporting path.
     */
    suspend fun summary(): TraceSummary {
        val sessions = store.list()
        return TraceSummary(
            sessionCount = sessions.size,
            eventCount = sessions.sumOf { it.events.size },
            discardedSessionCount = stateStore.readTraceDiscardedSessionCountOnce(),
            unlabelledSessionCount = sessions.count { !it.isLabelled },
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
                }
                val discarded = store.prune(keepSessionId = updated.sessionId)
                if (discarded > 0) stateStore.addTraceDiscardedSessions(discarded)
            }
        }
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

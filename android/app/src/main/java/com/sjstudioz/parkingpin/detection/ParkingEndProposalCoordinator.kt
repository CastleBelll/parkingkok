package com.sjstudioz.parkingpin.detection

import android.util.Log
import com.sjstudioz.parkingpin.analytics.AnalyticsEvent
import com.sjstudioz.parkingpin.analytics.AnalyticsRecording
import com.sjstudioz.parkingpin.analytics.DisabledAnalyticsRecorder
import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.domain.detection.ParkingEndProposal
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import com.sjstudioz.parkingpin.domain.parking.usecase.ActiveParkingEnd
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * The whole life of a departure proposal (docs/05_PARKING_DETECTION_ENGINE.md §11a,
 * DECIDED 2026-09-29): asked, then answered, superseded, or withdrawn.
 *
 * The engine confirms a departure and **asks**; it never closes the record. This class holds
 * the one question it asked — stored, so an ignored proposal survives process death — and
 * is the only thing that closes a record at the departure time:
 *
 * - `주차 종료` ([accept]) closes it at [ParkingEndProposal.departedAtMillis], not now.
 * - `아직 주차 중` ([keep]) keeps it and tells the engine `user_kept_parking`.
 * - The next parking saved while one is pending closes the previous record at the
 *   departure time, not at the save time, in the same write as the insert ([pendingEnd],
 *   then [retire] once it committed).
 * - Ending the parking by hand ([withdraw]) drops the question, and so does finding it stale
 *   ([dropIfStale]): its record is no longer the open one.
 *
 * `parking_auto_end` (docs/17) is reported by [accept] alone, and only when it closed a record.
 *
 * Every answer takes the proposal out of the store in one edit before acting, so a
 * notification action and the home card answering together act once. The next parking is
 * the exception: it reads the proposal, and retires it only after its write committed. An
 * answer whose write fails puts the proposal back and asks again, so it can be retried.
 */
class ParkingEndProposalCoordinator(
    private val store: DetectionStateStore,
    /** A provider for the reason [ParkingCandidateCoordinator]'s is one: most wakes never read Room. */
    private val repository: () -> ParkingRepository,
    private val notifier: ParkingEndProposalNotifying,
    private val clock: Clock,
    private val analytics: AnalyticsRecording = DisabledAnalyticsRecorder,
    /**
     * Where `아직 주차 중` reaches the §3a machine (`user_kept_parking`). A provider-shaped hook
     * rather than the runtime itself, because the runtime already holds this class as a
     * provider and the two must not construct each other. Null where there is no engine.
     */
    private val keptParking: (suspend (atMillis: Long) -> Unit)? = null,
) {

    /** The proposal waiting for an answer, as stored. The home card matches it to the open record. */
    fun observePending(): Flow<ParkingEndProposal?> = store.parkingEndProposal

    /**
     * The engine confirmed a departure ([com.sjstudioz.parkingpin.domain.detection.DetectionEffect.ProposeParkingEnd]).
     *
     * Asks about the parking open now, replacing any earlier question — one at a time. With
     * nothing open there is nothing to ask: a departure from a parking the user already ended
     * by hand is not a question. Notification permission is not a condition of correctness:
     * denied, the proposal is stored and the home card is the only place it is asked.
     */
    suspend fun propose(departedAtMillis: Long): ParkingEndProposal? {
        val active = repository().findActive() ?: return null
        val proposal = ParkingEndProposal(recordId = active.id, departedAtMillis = departedAtMillis)
        store.writeParkingEndProposal(proposal)
        if (notifier.isAuthorized()) notifier.post(active)
        return proposal
    }

    /**
     * `주차 종료`: closes the asked-about record at the departure time and reports
     * `parking_auto_end`. Returns the closed record, or null when there was nothing to close —
     * no proposal, or the record it asked about is no longer the open one.
     */
    suspend fun accept(recordId: String? = null): ParkingRecord? {
        val proposal = takeMatching(recordId) ?: return null
        val closed = reaskingOnFailure(proposal) { closeAtDeparture(proposal) } ?: return null
        // docs/17: reported on the user's acceptance, and only when a record was closed.
        analytics.record(AnalyticsEvent.ParkingAutoEnd)
        return closed
    }

    /**
     * `아직 주차 중`: the record stays open, and the engine hears `user_kept_parking` so it
     * drops the drive the departure opened and watches this parking again. Returns whether a
     * live proposal was answered; a stale tap tells the engine nothing.
     */
    suspend fun keep(recordId: String? = null): Boolean {
        val proposal = takeMatching(recordId) ?: return false
        return reaskingOnFailure(proposal) {
            if (repository().findActive()?.id != proposal.recordId) return false
            keptParking?.invoke(clock.nowEpochMillis())
            true
        }
    }

    /**
     * [accept] for a caller with nowhere to throw to — the notification action on the
     * application scope, the home card on the view-model scope. A Room or DataStore failure
     * (disk full, IO) is logged by type and swallowed instead of taking the process down;
     * the question is still pending and on screen, so the user can answer again. Returns
     * whether the answer ran to completion.
     */
    suspend fun tryAccept(recordId: String? = null): Boolean = answering("accept") { accept(recordId) }

    /** [keep], with [tryAccept]'s failure handling. */
    suspend fun tryKeep(recordId: String? = null): Boolean = answering("keep") { keep(recordId) }

    /**
     * What the next parking, saved by hand or from a candidate, may close: the asked-about
     * record at the departure time — not at the save time. Read, never taken: the question
     * stays stored and on screen until [retire] runs after the write committed, so a save
     * that writes nothing leaves it pending (§11a).
     */
    suspend fun pendingEnd(): ActiveParkingEnd? =
        store.readParkingEndProposalOnce()?.let { ActiveParkingEnd(it.recordId, it.departedAtMillis) }

    /**
     * The next parking was written and closed [endedRecordId] in the same write: the
     * question about it is answered. A question about any other record is left alone.
     */
    suspend fun retire(endedRecordId: String) {
        takeMatching(endedRecordId)
    }

    /** The parking was ended or removed by hand: the question is moot. */
    suspend fun withdraw() {
        takeMatching(recordId = null)
    }

    /**
     * Drops a question whose record is no longer the open one (§11a: "dropped and its
     * notification withdrawn the next time anything looks"). Run when the app comes forward.
     */
    suspend fun dropIfStale() {
        val stored = store.readParkingEndProposalOnce() ?: return
        if (repository().findActive()?.id == stored.recordId) return
        takeMatching(stored.recordId)
    }

    /**
     * Takes the stored proposal — only when it asks about [recordId], if one is given — and
     * withdraws its notification either way, since the tap that got here has been acted on.
     */
    private suspend fun takeMatching(recordId: String?): ParkingEndProposal? {
        val stored = store.readParkingEndProposalOnce()
        if (stored == null || (recordId != null && stored.recordId != recordId)) {
            if (stored == null) notifier.withdraw()
            return null
        }
        val taken = store.takeParkingEndProposal()
        notifier.withdraw()
        return taken
    }

    private suspend fun answering(answer: String, act: suspend () -> Unit): Boolean =
        try {
            act()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // By type only: a Room message may quote the row, and the row holds the location.
            Log.w(TAG, "departure $answer failed: ${failure.javaClass.simpleName}")
            false
        }

    /**
     * Runs an answer to a proposal that was already taken out of the store. If the answer
     * fails, the proposal is put back and asked again, so a failed write ends nothing and
     * leaves the question where the user can retry it (§11a); the failure is rethrown.
     */
    private suspend inline fun <T> reaskingOnFailure(proposal: ParkingEndProposal, answer: () -> T): T =
        try {
            answer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            try {
                reask(proposal)
            } catch (@Suppress("TooGenericExceptionCaught") alsoFailed: Exception) {
                failure.addSuppressed(alsoFailed)
            }
            throw failure
        }

    private suspend fun reask(proposal: ParkingEndProposal) {
        store.writeParkingEndProposal(proposal)
        val record = repository().findActive()?.takeIf { it.id == proposal.recordId } ?: return
        if (notifier.isAuthorized()) notifier.post(record)
    }

    private suspend fun closeAtDeparture(proposal: ParkingEndProposal): ParkingRecord? {
        val repository = repository()
        if (repository.findActive()?.id != proposal.recordId) return null
        return EndParkingUseCase(repository, clock)(proposal.departedAtMillis)
    }

    private companion object {
        const val TAG = "PkDetection"
    }
}

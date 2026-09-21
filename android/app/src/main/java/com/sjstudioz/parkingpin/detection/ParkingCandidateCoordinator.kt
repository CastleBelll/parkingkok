package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.AnalyticsEvent
import com.sjstudioz.parkingpin.analytics.AnalyticsRecording
import com.sjstudioz.parkingpin.analytics.DetectionProperties
import com.sjstudioz.parkingpin.analytics.DisabledAnalyticsRecorder
import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.domain.detection.CandidateHistoryEntry
import com.sjstudioz.parkingpin.domain.detection.CandidateOutcome
import com.sjstudioz.parkingpin.domain.detection.ParkingCandidate
import com.sjstudioz.parkingpin.domain.detection.ReliableLocation
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import com.sjstudioz.parkingpin.domain.parking.Floor
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What [ParkingCandidateCoordinator.confirm] did. */
sealed interface ConfirmCandidateResult {

    data class Confirmed(val record: ParkingRecord) : ConfirmCandidateResult

    /**
     * The candidate expired, was superseded, or was already answered.
     *
     * docs/05 §10a: a tap on a notification with nothing behind it lands on home, and the
     * app does not apologise for it in a dialog. [alreadyBecame] is the other half of that
     * clause — when the candidate *was* answered, the screen opens on the record it became
     * rather than on home.
     */
    data class Gone(val alreadyBecame: String? = null) : ConfirmCandidateResult

    /**
     * A parking session is already open, so nothing was written and the candidate is left
     * alone.
     *
     * Same refusal [com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase]
     * makes, and for the same reason: silently ending the open session would throw away a
     * record the user never asked to close, and a second open record would leave two cars
     * parked. Detection is a guess — it is the last thing that should overrule a session
     * the user established themselves.
     */
    data class AlreadyActive(val existing: ParkingRecord) : ConfirmCandidateResult
}

/**
 * The whole life of a candidate: created, announced, answered, or expired
 * (docs/05_PARKING_DETECTION_ENGINE.md §10a).
 *
 * ### Why it is one class
 * Create, confirm, reject and expire are four views of a single invariant — *at most one
 * candidate exists, and the notification on screen is the one it describes*. Split across
 * four use cases, each would have to re-derive that rule, and the interesting bugs
 * (a superseded candidate whose notification stayed up, a confirm that raced an expiry)
 * live exactly in the seams between them. Every mutation here goes through
 * [DetectionStateStore.updateCandidate], which serializes them.
 *
 * ### What it never does
 * It never auto-confirms. docs/05 §9's last line forbids it until field precision is
 * measured, so a candidate becomes a record only when the user says so.
 */
class ParkingCandidateCoordinator(
    private val store: DetectionStateStore,
    /**
     * A provider, not a value. `주차 아님` is answered from a broadcast-started process that
     * never reads parking history, and resolving Room there would open a database for a
     * delete of one DataStore key. Only [confirm] calls it.
     */
    private val repository: () -> ParkingRepository,
    private val notifier: CandidateNotifying,
    private val clock: Clock,
    private val idGenerator: () -> String,
    /** Defaulted so a composition without an analytics stack still works. */
    private val analytics: AnalyticsRecording = DisabledAnalyticsRecorder,
) {

    /**
     * The candidate the user should be asked about, or null.
     *
     * Expired candidates are filtered out here rather than deleted, because a collector is
     * a reader: the removal is [expireIfDue]'s, and it runs when the app comes forward.
     */
    fun observePending(): Flow<ParkingCandidate?> =
        store.candidate.map { candidate ->
            candidate?.takeUnless { it.isExpired(clock.nowEpochMillis()) }
        }

    /**
     * The resolved candidates the bell's screen lists, oldest first
     * (docs/10_DESIGN_UX_SPEC.md §7b).
     *
     * Exposed here rather than straight off the store because this class is what decides
     * a candidate's outcome, and the list is only ever as truthful as those decisions.
     */
    fun observeHistory(): Flow<List<CandidateHistoryEntry>> = store.candidateHistory

    /** The pending candidate with [id], or null when it is gone or expired. */
    suspend fun pending(id: String): ParkingCandidate? =
        store.readCandidateOnce()
            ?.takeIf { it.id == id }
            ?.takeUnless { it.isExpired(clock.nowEpochMillis()) }

    /**
     * The record [candidateId] became, when it was confirmed rather than expired.
     *
     * §10a: "if the candidate has since expired or been handled, the screen opens on the
     * record it became, or on home when there is nothing left to show". Null is the second
     * half of that sentence and is the ordinary answer.
     */
    suspend fun confirmedRecordId(candidateId: String): String? =
        store.readConfirmedRecordIdOnce(candidateId)

    /**
     * Records a new candidate on entry to `CANDIDATE_PENDING` and announces it.
     *
     * Three rules from §10a land here together, because they are one atomic decision:
     *
     * 1. **A new travel session retires the previous candidate immediately.** Its
     *    notification is withdrawn before the new one is posted — "a stale prompt about a
     *    previous trip is worse than no prompt".
     * 2. **`low` posts nothing** (§9) but is still stored, so the app can show it and the
     *    trace keeps the evidence.
     * 3. **Notification permission is not a condition of correctness.** Denied, this
     *    returns the same candidate it would otherwise have announced.
     *
     * `parking_candidate_created` is reported for every candidate including `low`: the
     * event is about detection, not about notification.
     */
    suspend fun create(
        evidence: DetectionProperties,
        lastReliableLocation: ReliableLocation?,
        /**
         * The id the engine already minted, when one did.
         *
         * [com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine] records the
         * candidate id in its own state and in the §14 checkpoint at the moment it decides
         * to create one, so the store has to use *that* id: a second id generated here
         * would leave the checkpoint naming a candidate the notification is not about, and
         * §10a's whole deduplication rule rests on the two being the same string.
         */
        candidateId: String? = null,
    ): ParkingCandidate {
        val now = clock.nowEpochMillis()
        val candidate = ParkingCandidate.of(
            id = candidateId ?: idGenerator(),
            detectedAtMillis = now,
            lastReliableLocation = lastReliableLocation,
            evidence = evidence,
        )

        var superseded: String? = null
        // §10a: the older candidate "expires immediately" — an unanswered guess about a
        // previous trip, which is exactly §7b's 응답 없음.
        store.updateCandidate(resolvedAs = CandidateOutcome.EXPIRED) { previous ->
            if (previous != null && previous.id != candidate.id) superseded = previous.id
            candidate
        }
        superseded?.let(notifier::withdraw)

        if (candidate.isNotifiable && notifier.isAuthorized()) notifier.post(candidate)
        analytics.record(AnalyticsEvent.ParkingCandidateCreated(candidate.evidence))
        return candidate
    }

    /**
     * Turns the candidate into a parking record — §10a: `source = detected`, the
     * candidate's `lastReliableLocation`, and the floor the user chose.
     *
     * The record starts at [ParkingCandidate.parkedAtMillis], not at the moment of the
     * tap: the elapsed time on the home screen has to count from when the car was left,
     * not from when the user got round to answering.
     */
    suspend fun confirm(candidateId: String, details: ConfirmedCandidateDetails): ConfirmCandidateResult {
        val now = clock.nowEpochMillis()
        val candidate = store.readCandidateOnce()
            ?.takeIf { it.id == candidateId }
            ?.takeUnless { it.isExpired(now) }
            ?: run {
                // Nothing to confirm, but the shade may still be showing it.
                notifier.withdraw(candidateId)
                return ConfirmCandidateResult.Gone(store.readConfirmedRecordIdOnce(candidateId))
            }

        val repository = repository()
        repository.findActive()?.let { return ConfirmCandidateResult.AlreadyActive(it) }

        val record = ParkingRecord(
            id = idGenerator(),
            startedAtMillis = candidate.parkedAtMillis,
            endedAtMillis = null,
            source = ParkingSource.DETECTED,
            confidenceBucket = candidate.confidenceBucket,
            location = candidate.lastReliableLocation?.toParkingLocation(),
            floor = details.floor,
            zone = details.zone,
            spot = details.spot,
            memo = details.memo,
            photoRelativePath = null,
            createdAtMillis = now,
            updatedAtMillis = now,
            revision = 1,
        )
        repository.insert(record)
        // Clears the candidate and remembers what it became, so a tap that beats the
        // notification's withdrawal opens the record rather than home (§10a).
        store.resolveCandidate(candidate.id, record.id, candidate.detectedAtMillis)
        notifier.withdraw(candidate.id)
        // After the write, never before: an event for a record that failed to insert would
        // overstate the feature.
        analytics.record(AnalyticsEvent.ParkingCandidateConfirmed(candidate.evidence))
        return ConfirmCandidateResult.Confirmed(record)
    }

    /**
     * Discards the candidate — `주차 아님`.
     *
     * **The event is reported even when the candidate has already gone.** docs/05 §10a:
     * "rejection is the event that pays for the whole feature, so it is never dropped".
     * A user who rejects a prompt that expired a second earlier has still told the
     * detector it was wrong, and losing that is worse than an occasional event about a
     * candidate the store no longer holds — so the evidence is read before the delete and
     * the report happens either way. Returns whether a stored candidate was actually
     * discarded.
     */
    suspend fun reject(candidateId: String): Boolean {
        val stored = store.readCandidateOnce()?.takeIf { it.id == candidateId }
        if (stored != null) {
            clear(candidateId, CandidateOutcome.REJECTED)
        } else {
            notifier.withdraw(candidateId)
        }
        analytics.record(
            AnalyticsEvent.ParkingCandidateRejected(stored?.evidence ?: lastKnownEvidence()),
        )
        return stored != null
    }

    /**
     * Drops the candidate once it is past its 45 minutes and takes its notification down
     * (§10a expiry).
     *
     * No record is created and nothing is reported: an unanswered guess is not an event,
     * and the user is not told about it. Called when the app comes forward — the OS's own
     * `setTimeoutAfter` removes the notification meanwhile, so an expiry nobody was
     * running for still looks right in the shade.
     */
    suspend fun expireIfDue(): ParkingCandidate? {
        val now = clock.nowEpochMillis()
        var expired: ParkingCandidate? = null
        store.updateCandidate(resolvedAs = CandidateOutcome.EXPIRED) { candidate ->
            if (candidate != null && candidate.isExpired(now)) {
                expired = candidate
                null
            } else {
                candidate
            }
        }
        expired?.let { notifier.withdraw(it.id) }
        return expired
    }

    /**
     * Takes the candidate down without recording an answer.
     *
     * Two §3a paths land here and neither is a user answer: the car link reconnecting
     * ("disconnect, pump, get back in, and the candidate is retired and its notification
     * withdrawn before it is worth anything"), and the 45-minute expiry reached while the
     * process is alive.
     *
     * **Deliberately silent in analytics, not in the history.** §7b lists a candidate
     * nobody answered as 응답 없음 whatever took it away, so this writes that line; what it
     * does not do is report an event. [reject] reports `parking_candidate_rejected` because the
     * user said the detector was wrong; nobody said anything here, and reporting a
     * rejection would corrupt the one distribution §10a calls the event that pays for the
     * whole feature. [expireIfDue] is the same decision arrived at from the app side.
     *
     * @return whether a stored candidate was actually removed.
     */
    suspend fun retire(candidateId: String): Boolean {
        val stored = store.readCandidateOnce()?.takeIf { it.id == candidateId }
        clear(candidateId, CandidateOutcome.EXPIRED)
        return stored != null
    }

    /**
     * Drops [candidateId] from the slot, writes its [outcome] to the §7b history, and
     * takes the notification down.
     *
     * The history line is written by the same edit that empties the slot, so there is no
     * window in which a candidate is neither pending nor accounted for.
     */
    private suspend fun clear(candidateId: String, outcome: CandidateOutcome) {
        store.updateCandidate(resolvedAs = outcome) { current ->
            current?.takeIf { it.id != candidateId }
        }
        notifier.withdraw(candidateId)
    }

    /**
     * The properties a rejection carries when the candidate itself is gone.
     *
     * `LOW` rather than a guess at what it was: the rejection is real and must be
     * reported, but claiming a confidence the store can no longer prove would corrupt the
     * one distribution this feature exists to measure. Nothing else is knowable, and
     * [DetectionProperties] has no field that could hold a coordinate anyway.
     */
    private fun lastKnownEvidence(): DetectionProperties = DetectionProperties(
        confidenceBucket = ConfidenceBucket.LOW,
        walkingEvidence = false,
        gpsDegradation = false,
        optionalVehicleSignal = false,
    )
}

/**
 * What the user supplied when they confirmed — the floor, and the optional fields the
 * manual form also offers.
 *
 * A parsed [Floor] rather than a raw string, so the
 * parsing decision stays with the screen that collected the text and this class has one
 * job.
 */
data class ConfirmedCandidateDetails(
    val floor: Floor?,
    val zone: String? = null,
    val spot: String? = null,
    val memo: String? = null,
)

/**
 * §10a: a confirmed record carries the candidate's `lastReliableLocation`.
 *
 * The two types are the same four numbers in two layers — detection's view and the
 * record's — so this is the one place they are bridged.
 */
private fun ReliableLocation.toParkingLocation(): ParkingLocation = ParkingLocation(
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyM = horizontalAccuracyM,
    capturedAtMillis = capturedAtMillis,
)

package kr.parkingpin.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kr.parkingpin.app.domain.detection.CandidateHistoryEntry
import kr.parkingpin.app.domain.detection.CandidateOutcome
import kr.parkingpin.app.domain.detection.DetectionCheckpoint
import kr.parkingpin.app.domain.detection.DetectionEngineState
import kr.parkingpin.app.domain.detection.ParkingCandidate
import kr.parkingpin.app.domain.detection.MotionDomainEvent
import kr.parkingpin.app.domain.location.LocationSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Recorded fact that a transition registration was successfully installed. */
data class RegistrationRecord(
    val specVersion: Int,
    val registeredAtMillis: Long,
)

/**
 * Persists detection state: the rehydration checkpoint, the desired/actual registration
 * state, and a bounded log of received transition events.
 *
 * Every write goes through DataStore's `edit {}`, which serializes writers and replaces
 * the backing file atomically, satisfying the atomic-checkpoint requirement.
 *
 * This class owns no CoroutineScope — callers supply one
 * (docs/16_CODING_STANDARDS.md §2: repositories do not create global scopes).
 */
class DetectionStateStore(
    private val dataStore: DataStore<Preferences>,
    private val maxLoggedEvents: Int = DEFAULT_MAX_LOGGED_EVENTS,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val checkpoint: Flow<DetectionCheckpoint?> = dataStore.data.map { it.readCheckpoint() }

    val registrationRecord: Flow<RegistrationRecord?> = dataStore.data.map { it.readRecord() }

    val desiredEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_DESIRED_ENABLED] ?: false }

    val recentEvents: Flow<List<MotionDomainEvent>> = dataStore.data.map { it.readEvents() }

    /**
     * The pending candidate, or null when there is none
     * (docs/05_PARKING_DETECTION_ENGINE.md §10a).
     *
     * Expiry is **not** applied here: this emits what is stored, and
     * [kr.parkingpin.app.detection.ParkingCandidateCoordinator] is the one place that
     * knows what the clock says about it. A store that quietly filtered on `now` would
     * emit a different value on every collection for no observable reason.
     */
    val candidate: Flow<ParkingCandidate?> = dataStore.data.map { it.readCandidate() }

    /**
     * The last [CandidateHistoryEntry.MAX_ENTRIES] resolved candidates, oldest first
     * (docs/10_DESIGN_UX_SPEC.md §7b, docs/05 §10a "History").
     *
     * Oldest first because that is the order it is appended in; the screen reverses it.
     * Storing it the other way round would make every append rewrite the head of the
     * list for a presentation decision.
     */
    val candidateHistory: Flow<List<CandidateHistoryEntry>> =
        dataStore.data.map { it.readCandidateHistory() }

    val locationSessionState: Flow<LocationSessionState> = dataStore.data.map { it.readSessionState() }

    /**
     * The detection state machine's own state
     * (docs/05_PARKING_DETECTION_ENGINE.md §3a, §16).
     *
     * Kept beside the checkpoint rather than inside it because the two answer different
     * questions. The checkpoint is the cross-platform §14 structure both engines write
     * field for field; this is everything the Kotlin reducer needs to resume mid-trip —
     * accumulated reason codes, the §7 evidence object, whether §12's one candidate has
     * been spent — and iOS has no business reading it.
     */
    val engineState: Flow<DetectionEngineState?> = dataStore.data.map { it.readEngineState() }

    suspend fun readCheckpointOnce(): DetectionCheckpoint? = dataStore.data.first().readCheckpoint()

    suspend fun readRegistrationRecordOnce(): RegistrationRecord? = dataStore.data.first().readRecord()

    suspend fun readLocationSessionStateOnce(): LocationSessionState = dataStore.data.first().readSessionState()

    suspend fun readDesiredEnabledOnce(): Boolean = dataStore.data.first()[KEY_DESIRED_ENABLED] ?: false

    suspend fun setDesiredEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DESIRED_ENABLED] = enabled }
    }

    /**
     * docs/06 §7b: whether the ongoing notice is shown in the shade while parked.
     *
     * **Off by default.** The lock screen is served by the keyguard widget; this is the
     * fallback for a host that has no lock-screen slot, and a persistent notification
     * nobody asked for is why people uninstall utilities.
     */
    val lockScreenNoticeEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_LOCK_SCREEN_NOTICE] ?: false }

    suspend fun readLockScreenNoticeEnabledOnce(): Boolean =
        dataStore.data.first()[KEY_LOCK_SCREEN_NOTICE] ?: false

    suspend fun setLockScreenNoticeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_LOCK_SCREEN_NOTICE] = enabled }
    }

    suspend fun recordRegistered(specVersion: Int, atMillis: Long) {
        dataStore.edit {
            it[KEY_REGISTERED_SPEC_VERSION] = specVersion
            it[KEY_REGISTERED_AT] = atMillis
        }
    }

    suspend fun clearRegistrationRecord() {
        dataStore.edit {
            it.remove(KEY_REGISTERED_SPEC_VERSION)
            it.remove(KEY_REGISTERED_AT)
        }
    }

    suspend fun readCandidateOnce(): ParkingCandidate? = dataStore.data.first().readCandidate()

    suspend fun readCandidateHistoryOnce(): List<CandidateHistoryEntry> =
        dataStore.data.first().readCandidateHistory()

    /**
     * The record the most recently confirmed candidate became, if [candidateId] is that
     * candidate.
     *
     * docs/05_PARKING_DETECTION_ENGINE.md §10a: a tap on a candidate that has been handled
     * "opens on the record it became". Confirming withdraws the notification, so this is a
     * narrow race — one notification, already answered, tapped before the shade caught up
     * — and one entry is exactly as much history as that needs. Anything older lands on
     * home, which is what §10a asks for when there is nothing left to show.
     */
    suspend fun readConfirmedRecordIdOnce(candidateId: String): String? {
        val prefs = dataStore.data.first()
        if (prefs[KEY_CONFIRMED_CANDIDATE] != candidateId) return null
        return prefs[KEY_CONFIRMED_RECORD]
    }

    /**
     * Reads, transforms and writes the candidate inside one edit.
     *
     * Every candidate mutation is a read-modify-write — supersede the previous one, drop
     * an expired one, clear the one just confirmed — and two of them can arrive together:
     * a notification action and a new travel session are delivered to the same process
     * from different broadcasts. A read-then-write pair would let the later writer
     * resurrect a candidate the earlier one retired.
     *
     * Returns what is now stored.
     */
    suspend fun updateCandidate(
        /**
         * What to record in the notification history when [transform] drops or replaces
         * the stored candidate (docs/10 §7b).
         *
         * It is a parameter of this call rather than a second call the caller makes
         * afterwards because "the slot no longer holds this candidate" and "the history
         * says what became of it" have to land in the same edit. Two edits would let a
         * process death between them lose the line, and the candidate is exactly the
         * thing the user is trying to find again.
         *
         * Null for a transform that is not a resolution — [kr.parkingpin.app.detection
         * .ParkingCandidateCoordinator.confirm] uses [resolveCandidate] instead, because
         * it also has a record id to write.
         */
        resolvedAs: CandidateOutcome? = null,
        transform: (ParkingCandidate?) -> ParkingCandidate?,
    ): ParkingCandidate? {
        var updated: ParkingCandidate? = null
        dataStore.edit { prefs ->
            val previous = prefs.readCandidate()
            updated = transform(previous)
            val next = updated
            if (next == null) prefs.remove(KEY_CANDIDATE) else prefs[KEY_CANDIDATE] = json.encodeToString(next)
            // Left the slot: removed outright, or replaced by a different candidate.
            // Re-storing the same id is the engine rewriting its own candidate, not a
            // resolution, and must not produce a line.
            if (resolvedAs != null && previous != null && previous.id != next?.id) {
                prefs.appendCandidateHistory(
                    CandidateHistoryEntry(
                        candidateId = previous.id,
                        raisedAtMillis = previous.detectedAtMillis,
                        outcome = resolvedAs,
                    ),
                )
            }
        }
        return updated
    }

    /**
     * Drops the candidate and remembers what it became, in one edit.
     *
     * Two keys written together because a resolution pointing at a candidate still stored
     * as pending would be a candidate the app offers to confirm twice.
     */
    suspend fun resolveCandidate(candidateId: String, recordId: String, raisedAtMillis: Long) {
        dataStore.edit { prefs ->
            val current = prefs.readCandidate()
            if (current != null && current.id == candidateId) prefs.remove(KEY_CANDIDATE)
            prefs[KEY_CONFIRMED_CANDIDATE] = candidateId
            prefs[KEY_CONFIRMED_RECORD] = recordId
            prefs.appendCandidateHistory(
                CandidateHistoryEntry(
                    candidateId = candidateId,
                    raisedAtMillis = raisedAtMillis,
                    outcome = CandidateOutcome.CONFIRMED,
                    recordId = recordId,
                ),
            )
        }
    }

    suspend fun readEngineStateOnce(): DetectionEngineState? = dataStore.data.first().readEngineState()

    /**
     * Writes the engine state and the checkpoint it implies in one edit.
     *
     * They must land together: a checkpoint naming a candidate the engine state has not
     * recorded — or an engine state in `CANDIDATE_PENDING` under a checkpoint that still
     * says `IDLE` — is a process restart that resumes into a trip that never happened.
     *
     * [DetectionCheckpoint.revision] is rewritten here rather than taken from the caller.
     * The engine is a pure function and cannot read a counter; this is the only place that
     * serializes writers, so it is the only place that can advance one.
     */
    suspend fun writeEngineStateAndCheckpoint(state: DetectionEngineState, checkpoint: DetectionCheckpoint) {
        dataStore.edit { prefs ->
            prefs[KEY_ENGINE_STATE] = json.encodeToString(state)
            val previous = prefs.readCheckpoint()?.revision ?: 0L
            prefs[KEY_CHECKPOINT] = json.encodeToString(checkpoint.copy(revision = previous + 1))
        }
    }

    suspend fun writeCheckpoint(checkpoint: DetectionCheckpoint) {
        dataStore.edit { it[KEY_CHECKPOINT] = json.encodeToString(checkpoint) }
    }

    /**
     * Appends [event] to the bounded log and updates the checkpoint in the same atomic
     * edit, so the log can never show an event the checkpoint has not accounted for.
     */
    suspend fun appendEventAndCheckpoint(event: MotionDomainEvent, checkpoint: DetectionCheckpoint) {
        dataStore.edit { prefs ->
            val updated = (prefs.readEvents() + event).takeLast(maxLoggedEvents)
            prefs[KEY_EVENT_LOG] = json.encodeToString(updated)
            prefs[KEY_CHECKPOINT] = json.encodeToString(checkpoint)
        }
    }

    suspend fun clearEventLog() {
        dataStore.edit { it.remove(KEY_EVENT_LOG) }
    }

    /**
     * Which trace session is currently being appended to, or null when none is open.
     *
     * Kept here rather than in the trace directory so it lands in the same atomic edit
     * discipline as the rest of the detection state: receivers run in a process that can
     * die between two events, and a pointer that disagreed with the checkpoint about
     * whether recording was in progress would split one trip across two files on every
     * restart. The sessions themselves are files — see
     * [kr.parkingpin.app.trace.FileTraceStore] — because the rolling cap evicts whole
     * sessions.
     */
    suspend fun readTraceOpenSessionIdOnce(): String? = dataStore.data.first()[KEY_TRACE_OPEN_SESSION]

    suspend fun setTraceOpenSessionId(sessionId: String?) {
        dataStore.edit {
            if (sessionId == null) it.remove(KEY_TRACE_OPEN_SESSION) else it[KEY_TRACE_OPEN_SESSION] = sessionId
        }
    }

    suspend fun readTraceDiscardedSessionCountOnce(): Int = dataStore.data.first()[KEY_TRACE_DISCARDED] ?: 0

    /**
     * Records that the rolling cap threw sessions away.
     *
     * Cumulative and never reset: docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 requires a
     * cap, and a field run that looks thin because the cap quietly ate half of it has to be
     * able to say so. Read back inside an `edit` so two concurrent prunes cannot lose an
     * increment the way a read-then-write pair would.
     */
    suspend fun addTraceDiscardedSessions(count: Int) {
        if (count <= 0) return
        dataStore.edit { it[KEY_TRACE_DISCARDED] = (it[KEY_TRACE_DISCARDED] ?: 0) + count }
    }

    suspend fun readTraceNonViableDropCountOnce(): Int = dataStore.data.first()[KEY_TRACE_NON_VIABLE] ?: 0

    /**
     * Records that rotation threw away a session holding one event or none
     * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "비생존 세션은 버린다").
     *
     * Kept apart from [addTraceDiscardedSessions] because the two numbers mean opposite
     * things: the rolling cap climbing says the device is recording more than it can hold,
     * while this climbing says the boundary is manufacturing single-event sessions and the
     * 30-minute threshold is wrong. A single total would hide which one is happening.
     */
    suspend fun addTraceNonViableDrops(count: Int) {
        if (count <= 0) return
        dataStore.edit { it[KEY_TRACE_NON_VIABLE] = (it[KEY_TRACE_NON_VIABLE] ?: 0) + count }
    }

    suspend fun readTraceLabelPromptSuppressedCountOnce(): Int =
        dataStore.data.first()[KEY_TRACE_LABEL_PROMPT_SUPPRESSED] ?: 0

    /**
     * Records that a closed session went unprompted because notifications are not permitted
     * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 labelling).
     *
     * Persisted rather than held in memory, and for the sharper of the two reasons the drop
     * counters are: the prompt is posted from a process that dies between two PendingIntent
     * deliveries, so an in-memory count would read zero by the time anyone opened the
     * diagnostics screen. It is the only thing that tells a field weekend which collected no
     * labels apart from a field weekend where nobody travelled. Same name on iOS.
     */
    suspend fun addTraceLabelPromptSuppressed(count: Int) {
        if (count <= 0) return
        dataStore.edit {
            it[KEY_TRACE_LABEL_PROMPT_SUPPRESSED] = (it[KEY_TRACE_LABEL_PROMPT_SUPPRESSED] ?: 0) + count
        }
    }

    /**
     * Reads, transforms, and writes the location session state inside one [androidx.datastore.core.DataStore.updateData]
     * transform, so two location batches arriving back to back cannot lose each other's
     * counter increments the way a read-then-write pair would.
     */
    suspend fun updateLocationSessionState(
        transform: (LocationSessionState) -> LocationSessionState,
    ): LocationSessionState {
        var updated = LocationSessionState()
        dataStore.edit { prefs ->
            updated = transform(prefs.readSessionState())
            prefs[KEY_LOCATION_SESSION] = json.encodeToString(updated)
        }
        return updated
    }

    /**
     * Writes the session state and the checkpoint together.
     *
     * A location delivery updates both, and they must not disagree: a checkpoint holding a
     * reliable fix the session state never counted would make the P0 numbers unreadable.
     */
    suspend fun updateLocationSessionAndCheckpoint(
        transform: (LocationSessionState, DetectionCheckpoint?) -> Pair<LocationSessionState, DetectionCheckpoint?>,
    ): LocationSessionState {
        var updated = LocationSessionState()
        dataStore.edit { prefs ->
            val (state, checkpoint) = transform(prefs.readSessionState(), prefs.readCheckpoint())
            updated = state
            prefs[KEY_LOCATION_SESSION] = json.encodeToString(state)
            if (checkpoint != null) prefs[KEY_CHECKPOINT] = json.encodeToString(checkpoint)
        }
        return updated
    }

    private fun Preferences.readEngineState(): DetectionEngineState? =
        decode(this[KEY_ENGINE_STATE]) { json.decodeFromString<DetectionEngineState>(it) }

    private fun Preferences.readCandidate(): ParkingCandidate? =
        decode(this[KEY_CANDIDATE]) { json.decodeFromString<ParkingCandidate>(it) }

    private fun Preferences.readCandidateHistory(): List<CandidateHistoryEntry> =
        decode(this[KEY_CANDIDATE_HISTORY]) { json.decodeFromString<List<CandidateHistoryEntry>>(it) }
            ?: emptyList()

    /**
     * Appends [entry] and drops anything past §7b's thirty.
     *
     * A [androidx.datastore.preferences.core.MutablePreferences] receiver rather than a
     * suspending function of its own, so it can only be called from inside an `edit` —
     * an append that read and wrote on its own would lose a line whenever a notification
     * action and an expiry arrived together.
     */
    private fun MutablePreferences.appendCandidateHistory(entry: CandidateHistoryEntry) {
        val updated = (readCandidateHistory() + entry).takeLast(CandidateHistoryEntry.MAX_ENTRIES)
        this[KEY_CANDIDATE_HISTORY] = json.encodeToString(updated)
    }

    private fun Preferences.readCheckpoint(): DetectionCheckpoint? =
        decode(this[KEY_CHECKPOINT]) { json.decodeFromString<DetectionCheckpoint>(it) }

    private fun Preferences.readSessionState(): LocationSessionState =
        decode(this[KEY_LOCATION_SESSION]) { json.decodeFromString<LocationSessionState>(it) }
            ?: LocationSessionState()

    private fun Preferences.readEvents(): List<MotionDomainEvent> =
        decode(this[KEY_EVENT_LOG]) { json.decodeFromString<List<MotionDomainEvent>>(it) }
            ?: emptyList()

    private fun Preferences.readRecord(): RegistrationRecord? {
        val version = this[KEY_REGISTERED_SPEC_VERSION] ?: return null
        return RegistrationRecord(
            specVersion = version,
            registeredAtMillis = this[KEY_REGISTERED_AT] ?: 0L,
        )
    }

    /** A corrupt payload must degrade to "no state", never crash the detection path. */
    private fun <T> decode(raw: String?, parse: (String) -> T): T? {
        if (raw == null) return null
        return try {
            parse(raw)
        } catch (error: SerializationException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val DEFAULT_MAX_LOGGED_EVENTS = 50

        val KEY_CHECKPOINT = stringPreferencesKey("checkpoint")
        val KEY_ENGINE_STATE = stringPreferencesKey("detection_engine_state")
        val KEY_CANDIDATE = stringPreferencesKey("parking_candidate")
        val KEY_CANDIDATE_HISTORY = stringPreferencesKey("parking_candidate_history")
        val KEY_CONFIRMED_CANDIDATE = stringPreferencesKey("parking_candidate_confirmed_id")
        val KEY_CONFIRMED_RECORD = stringPreferencesKey("parking_candidate_confirmed_record")
        val KEY_EVENT_LOG = stringPreferencesKey("event_log")
        val KEY_DESIRED_ENABLED = booleanPreferencesKey("registration_desired_enabled")
        val KEY_LOCK_SCREEN_NOTICE = booleanPreferencesKey("lock_screen_notice_enabled")
        val KEY_REGISTERED_SPEC_VERSION = intPreferencesKey("registration_spec_version")
        val KEY_REGISTERED_AT = longPreferencesKey("registration_registered_at")
        val KEY_LOCATION_SESSION = stringPreferencesKey("location_session")
        val KEY_TRACE_OPEN_SESSION = stringPreferencesKey("trace_open_session_id")
        val KEY_TRACE_DISCARDED = intPreferencesKey("trace_discarded_session_count")
        val KEY_TRACE_NON_VIABLE = intPreferencesKey("trace_non_viable_drop_count")
        val KEY_TRACE_LABEL_PROMPT_SUPPRESSED = intPreferencesKey("trace_label_prompt_suppressed_count")
    }
}

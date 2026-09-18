package com.parkingkok.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.location.LocationSessionState
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

    val locationSessionState: Flow<LocationSessionState> = dataStore.data.map { it.readSessionState() }

    suspend fun readCheckpointOnce(): DetectionCheckpoint? = dataStore.data.first().readCheckpoint()

    suspend fun readRegistrationRecordOnce(): RegistrationRecord? = dataStore.data.first().readRecord()

    suspend fun readLocationSessionStateOnce(): LocationSessionState = dataStore.data.first().readSessionState()

    suspend fun readDesiredEnabledOnce(): Boolean = dataStore.data.first()[KEY_DESIRED_ENABLED] ?: false

    suspend fun setDesiredEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DESIRED_ENABLED] = enabled }
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
     * [com.parkingkok.app.trace.FileTraceStore] — because the rolling cap evicts whole
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
        val KEY_EVENT_LOG = stringPreferencesKey("event_log")
        val KEY_DESIRED_ENABLED = booleanPreferencesKey("registration_desired_enabled")
        val KEY_REGISTERED_SPEC_VERSION = intPreferencesKey("registration_spec_version")
        val KEY_REGISTERED_AT = longPreferencesKey("registration_registered_at")
        val KEY_LOCATION_SESSION = stringPreferencesKey("location_session")
        val KEY_TRACE_OPEN_SESSION = stringPreferencesKey("trace_open_session_id")
        val KEY_TRACE_DISCARDED = intPreferencesKey("trace_discarded_session_count")
        val KEY_TRACE_NON_VIABLE = intPreferencesKey("trace_non_viable_drop_count")
    }
}

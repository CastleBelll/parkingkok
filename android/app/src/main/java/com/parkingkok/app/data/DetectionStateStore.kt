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

    suspend fun readCheckpointOnce(): DetectionCheckpoint? = dataStore.data.first().readCheckpoint()

    suspend fun readRegistrationRecordOnce(): RegistrationRecord? = dataStore.data.first().readRecord()

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

    private fun Preferences.readCheckpoint(): DetectionCheckpoint? =
        decode(this[KEY_CHECKPOINT]) { json.decodeFromString<DetectionCheckpoint>(it) }

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
    }
}

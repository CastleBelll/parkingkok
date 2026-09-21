package kr.parkingpin.app.analytics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The analytics opt-in.
 *
 * docs/07 "동의": default off, persisted locally, revocable at any time, and a revocation
 * stops transmission immediately.
 *
 * **There is deliberately no event for a change to this flag.** Reporting a grant would be
 * a transmission decided by the state *before* the grant existed; reporting a revocation
 * would be a transmission after it was withdrawn. Both are the thing docs/07 forbids, so
 * this class has no recorder and [AnalyticsEvent] has no case to put one in.
 *
 * Shares the detection DataStore instance rather than opening a second file for one
 * boolean: DataStore requires one instance per file, `detectionDataStore()` returns that
 * instance, and a preference store per setting would be over-engineering (CLAUDE.md).
 */
class AnalyticsConsentStore(private val dataStore: DataStore<Preferences>) {

    /**
     * Off unless explicitly granted. An absent key reads as `false`, which is exactly the
     * required default and needs no migration on first launch.
     */
    val granted: Flow<Boolean> = dataStore.data.map { it[KEY_GRANTED] ?: false }

    suspend fun isGrantedOnce(): Boolean = dataStore.data.first()[KEY_GRANTED] ?: false

    suspend fun setGranted(granted: Boolean) {
        dataStore.edit { it[KEY_GRANTED] = granted }
    }

    private companion object {
        val KEY_GRANTED = booleanPreferencesKey("analytics_consent_granted")
    }
}

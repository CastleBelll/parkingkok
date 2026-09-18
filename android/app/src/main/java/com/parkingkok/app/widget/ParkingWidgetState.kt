package com.parkingkok.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.parkingkok.app.domain.widget.ParkingWidgetProjection
import kotlinx.serialization.json.Json

/**
 * How a [ParkingWidgetProjection] travels through Glance's per-widget state.
 *
 * One JSON string rather than a key per field: the projection is written and read whole,
 * and a half-applied snapshot — new floor, old session id — is not a state docs/06 §7a
 * should have to reason about.
 */
internal object ParkingWidgetState {

    val ProjectionKey: Preferences.Key<String> = stringPreferencesKey("parking-projection")

    /**
     * Lenient on read: a state file written by an older build, or truncated by a crash
     * mid-write, degrades to "nothing parked" — which prompts the user to open the app,
     * where Room still has the truth. Throwing would leave an error view on the home
     * screen that no user action could clear.
     */
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(projection: ParkingWidgetProjection): String = json.encodeToString(projection)

    fun decode(raw: String?): ParkingWidgetProjection = runCatching {
        raw?.let { json.decodeFromString<ParkingWidgetProjection>(it) }
    }.getOrNull() ?: ParkingWidgetProjection.Empty
}

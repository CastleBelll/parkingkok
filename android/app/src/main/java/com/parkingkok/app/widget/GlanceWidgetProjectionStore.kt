package com.parkingkok.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.parkingkok.app.domain.widget.ParkingWidgetProjection
import com.parkingkok.app.domain.widget.WidgetProjectionStore

/**
 * Writes a projection into every placed widget's Glance state and asks the host to redraw
 * — steps 4 and 5 of docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a's mutation sequence.
 *
 * Glance keeps this state in its own per-widget file, so the last known parking is still
 * on the home screen after the process dies and after a reboot, without the app having
 * run. That durability is the reason the projection exists at all; see [ParkingWidget].
 *
 * Every failure here is swallowed. The widget manager throws when no host is available —
 * a profile shutting down, a device still in direct boot — and a redraw that could not be
 * delivered is not a reason to fail a mutation Room has already committed. The next
 * `onUpdate` repairs the display.
 */
class GlanceWidgetProjectionStore(context: Context) : WidgetProjectionStore {

    private val appContext: Context = context.applicationContext

    override suspend fun write(projection: ParkingWidgetProjection) {
        val encoded = ParkingWidgetState.encode(projection)
        val widget = ParkingWidget()
        for (glanceId in placedWidgets(widget)) {
            runCatching {
                updateAppWidgetState(appContext, PreferencesGlanceStateDefinition, glanceId) {
                    it.toMutablePreferences().apply {
                        this[ParkingWidgetState.ProjectionKey] = encoded
                    }
                }
            }
        }
        runCatching { widget.updateAll(appContext) }
    }

    /**
     * Every placed instance of both providers: `getGlanceIds` resolves by widget class,
     * and the 2x2 and the 4x2 receiver deliberately share one.
     */
    private suspend fun placedWidgets(widget: ParkingWidget): List<GlanceId> =
        runCatching { GlanceAppWidgetManager(appContext).getGlanceIds(widget.javaClass) }
            .getOrDefault(emptyList())
}

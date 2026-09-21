package com.parkingpin.app.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.parkingpin.app.core.SystemClock

/** The two cell footprints docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a fixes for Android. */
internal object ParkingWidgetSizes {

    /** 2x2 — floor and elapsed duration. */
    val COMPACT: DpSize = DpSize(110.dp, 110.dp)

    /** 4x2 — adds `zone · spot` and, when entitled, the stepper. */
    val WIDE: DpSize = DpSize(250.dp, 110.dp)
}

/**
 * The 주차핀 home-screen widget (docs/04_ANDROID_IMPLEMENTATION.md §10).
 *
 * It reads its Glance state and nothing else. Room stays canonical and
 * `ParkingWidgetSync` is what puts a projection here (docs/06 §4, §7) — composing off the
 * database instead would mean opening it on every host redraw, including the one that
 * arrives at boot before the app has run.
 *
 * The state is Glance's own per-widget preferences file, which is why a placed widget
 * still shows the last known parking after process death and after a reboot.
 */
internal class ParkingWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    /**
     * Both layouts are generated up front and the host picks, so a resize does not have to
     * wait for the app to be woken for a recomposition.
     */
    override val sizeMode: SizeMode =
        SizeMode.Responsive(setOf(ParkingWidgetSizes.COMPACT, ParkingWidgetSizes.WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val projection = ParkingWidgetState.decode(
                currentState(ParkingWidgetState.ProjectionKey),
            )
            GlanceTheme(colors = ParkingpinGlanceColors) {
                // Read at composition rather than carried in the projection: the snapshot
                // stores when the session started, so the elapsed line is recomputed on
                // every redraw instead of going stale the moment it is written.
                ParkingWidgetContent(projection, SystemClock.nowEpochMillis())
            }
        }
    }
}

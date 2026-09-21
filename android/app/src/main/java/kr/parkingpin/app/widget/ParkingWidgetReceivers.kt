package kr.parkingpin.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kr.parkingpin.app.ParkingpinApplication

/**
 * The two providers of docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a, 2x2 and 4x2.
 *
 * They are separate providers rather than one resizable entry so both sizes appear in the
 * picker and land at the size the user chose. They share a single [ParkingWidget], which
 * is also what lets one `updateAll` reach every placed instance of either size.
 */
abstract class ParkingWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget get() = ParkingWidget()

    /**
     * Fires when a widget is added and again after a reboot.
     *
     * A newly placed widget has an empty state file, so without this it would draw the
     * "open the app" line while a parking is in fact open. The refresh is also docs/06 §8's
     * startup repair on the path that matters most — the host asking for a redraw in a
     * process that may have just been created for this broadcast.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        ParkingpinApplication.containerOf(context)?.syncParkingWidgets()
    }
}

/** 2x2. */
class ParkingCompactWidgetReceiver : ParkingWidgetReceiver()

/** 4x2. */
class ParkingWideWidgetReceiver : ParkingWidgetReceiver()

/**
 * Whether the user has placed any 주차핀 widget.
 *
 * A binder call to the widget manager, never a database read: `AppContainer` opens Room
 * lazily so a process started by a detection broadcast pays nothing for storage it will
 * not touch, and asking "is a widget on screen" must not be what breaks that.
 */
internal fun anyParkingWidgetPlaced(context: Context): Boolean {
    val manager = AppWidgetManager.getInstance(context) ?: return false
    return PROVIDERS.any { provider ->
        manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
    }
}

private val PROVIDERS = listOf(
    ParkingCompactWidgetReceiver::class.java,
    ParkingWideWidgetReceiver::class.java,
)

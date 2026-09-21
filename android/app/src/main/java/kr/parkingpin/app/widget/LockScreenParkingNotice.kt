package kr.parkingpin.app.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.parkingpin.app.MainActivity
import kr.parkingpin.app.R
import kr.parkingpin.app.core.Clock
import kr.parkingpin.app.core.SystemClock
import kr.parkingpin.app.domain.parking.ElapsedTime
import kr.parkingpin.app.domain.widget.ParkingWidgetProjection
import kr.parkingpin.app.domain.widget.WidgetProjectionStore
import kr.parkingpin.app.ui.format.elapsedResource

/**
 * The parking on the lock screen (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7b).
 *
 * ## Why a notification and not a widget
 * Android phones have no lock screen widgets — they were removed in Android 5, and the
 * Android 15 return is tablet-only. An ongoing notification is the platform's way to keep a
 * line on a locked screen, so that is what this is: the same three facts the home-screen
 * widget draws, in the shade and above the clock.
 *
 * ## Why it is a [WidgetProjectionStore]
 * Because it renders exactly the same projection, from exactly the same writes. Plugging in
 * beside `GlanceWidgetProjectionStore` means `ParkingWidgetSync` drives both and there is
 * still only one place a session becomes a snapshot (§7). A second source would be a second
 * thing to keep in step, and docs/06 exists because that never works.
 *
 * ## What it is not
 * **No foreground service.** CLAUDE.md forbids a permanent one, and this needs none: the
 * notification is posted and cancelled by writes that were already happening. It does not
 * keep the process alive and does not try to.
 *
 * **Never a sound.** `IMPORTANCE_LOW`, its own channel. It is a readout; the candidate
 * prompt is the only thing in this app allowed to interrupt.
 *
 * **Never a coordinate.** A floor and a zone, which is why it can be `VISIBILITY_PUBLIC` —
 * there is nothing here to hide from a locked screen (docs/09).
 */
class LockScreenParkingNotice(
    context: Context,
    /**
     * Whether the user wants it. Read on every write rather than captured, so turning the
     * switch off takes effect on the next projection instead of the next process.
     */
    private val enabled: () -> Boolean,
    private val clock: Clock = SystemClock,
) : WidgetProjectionStore {

    private val appContext: Context = context.applicationContext

    override suspend fun write(projection: ParkingWidgetProjection) {
        if (!projection.isActive || !enabled()) {
            cancel()
            return
        }
        post(projection)
    }

    private fun post(projection: ParkingWidgetProjection) {
        try {
            createChannel()
            val floor = projection.floorLabel ?: appContext.getString(R.string.home_no_floor)
            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(contentTitle(floor, projection.zoneSpot))
                .setContentText(elapsedLabel(projection.startedAtMillis))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                // §7b: a floor and a zone, so there is nothing to withhold on a locked
                // screen. Hiding it would defeat the entire point of the feature.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS revoked. Ordinary: the widget and the app still have it.
            Log.i(TAG, "lock screen notice not permitted: ${denied.javaClass.simpleName}")
        } catch (error: Exception) {
            // Best-effort by contract, and the class name only — the projection carries a
            // floor and a zone, which do not belong in logcat.
            Log.e(TAG, "lock screen notice failed: ${error.javaClass.simpleName}")
        }
    }

    private fun cancel() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    /** `B3 · A구역 · 142`, or just the floor when the record carries neither. */
    private fun contentTitle(floor: String, zoneSpot: String?): String =
        if (zoneSpot == null) floor else "$floor · $zoneSpot"

    private fun elapsedLabel(startedAtMillis: Long): String {
        val (id, args) = elapsedResource(ElapsedTime.since(startedAtMillis, clock.nowEpochMillis()))
        return appContext.getString(id, *args)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.lock_screen_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.lock_screen_channel_description)
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "LockScreenNotice"
        const val CHANNEL_ID = "parking_lock_screen"

        /** One parking at a time, so one fixed id: a new one replaces the old in place. */
        const val NOTIFICATION_ID = 0x9A2C
    }
}

/**
 * Writes one projection to several stores.
 *
 * The widget and the lock screen are two renderings of one snapshot, and §7 allows exactly
 * one place where a session becomes one. This is that place staying single while the
 * renderings multiply.
 *
 * A store that throws must not stop the others: a failed notification is not a reason for
 * the home-screen widget to go stale.
 */
class CompositeWidgetProjectionStore(
    private val stores: List<WidgetProjectionStore>,
) : WidgetProjectionStore {

    override suspend fun write(projection: ParkingWidgetProjection) {
        for (store in stores) {
            try {
                store.write(projection)
            } catch (error: Exception) {
                Log.e("WidgetProjection", "store failed: ${error.javaClass.simpleName}")
            }
        }
    }
}

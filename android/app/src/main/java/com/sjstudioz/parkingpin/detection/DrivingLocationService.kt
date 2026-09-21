package com.sjstudioz.parkingpin.detection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sjstudioz.parkingpin.MainActivity
import com.sjstudioz.parkingpin.ParkingpinApplication
import com.sjstudioz.parkingpin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the bounded driving capture alive while the app is in the background
 * (docs/04_ANDROID_IMPLEMENTATION.md §2a).
 *
 * ## Why this exists, measured rather than assumed
 * `FusedLocationSessionRegistrar` hands Play services a PendingIntent precisely so the
 * subscription survives process death. It does — but surviving is not the same as being
 * *delivered*, and Android throttles background location for an app with no foreground
 * service to a few updates an hour regardless of how the subscription was made.
 *
 * Measured on the Galaxy S21 on 2026-09-20, one `DRIVING` session at a 15-second interval:
 *
 * | condition | deliveries in 3 minutes | expected |
 * |---|---|---|
 * | app foreground | 3 in the first 25s | ~12 |
 * | backgrounded, screen off | **0** | ~12 |
 * | backgrounded, screen off, exempt from battery optimisation | **1** | ~12 |
 * | backgrounded, screen off, with this service | **45** | ~12 |
 *
 * That third row is the one that decided this design: the doze exemption alone does not
 * lift the throttle. A whole day of real driving produced three fixes, the last reliable
 * one at noon, and a candidate at 17:32 inherited it.
 *
 * ## Why this does not break the standing rule
 * CLAUDE.md forbids running a foreground service **permanently**. This one exists only
 * between [start] and [stop], which bracket exactly one bounded Fused Location request —
 * so its lifetime is the drive's, and `LocationSessionPlanner`'s own deadline bounds that.
 * A parked phone runs no service. If this is ever seen alive while `sessionMode` is `IDLE`,
 * that is a leak and a bug, not a design.
 *
 * ## The notification
 * Mandatory for a foreground service, so it is written to be worth its space: it says the
 * app is recording a drive and stops when the drive does. `IMPORTANCE_LOW`, its own
 * channel, never a sound. It carries no floor, no zone and no coordinate — there is nothing
 * to say yet, and docs/09 would not allow the last of those anywhere.
 */
class DrivingLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        startTicking()
        // Not sticky: the drive is what justifies this service, and the system restarting
        // it on its own — with no session behind it — is the leak the class doc rules out.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The scheduled half of §3a's timeout rows.
     *
     * The engine settles those rows against each event's own timestamp, which covers every
     * drive that keeps producing events. It does not cover the one that stops: underground,
     * with no `vehicle_exit`, no fixes because there is no sky, and no walk transition
     * delivered. That session stays `DRIVING` — and this service, whose whole justification
     * is a live capture, stays up behind it.
     *
     * **A loop here rather than an alarm, and that is the design.** The costly state and
     * this service have the same lifetime, so the tick exists exactly while the silence
     * would cost something and a parked phone ticks never — no `AlarmManager`, no exact-alarm
     * permission, nothing to leak. `AndroidOS` doze restrictions do not apply while a
     * foreground service is running, which is the other reason this is the right place.
     *
     * One minute, against a shortest window of 180 s (`movementIdleWindow`): three chances
     * to notice each boundary.
     */
    private fun startTicking() {
        if (ticking) return
        val runtime = ParkingpinApplication.containerOf(this)?.parkingDetectionRuntime ?: return
        ticking = true
        scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                // Wall-clock, because every window in §3a is expressed in it and the
                // engine's own events carry it. A tick on a different clock could not be
                // compared with them.
                runtime.handleTick(System.currentTimeMillis())
            }
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(getString(R.string.driving_capture_title))
            .setContentText(getString(R.string.driving_capture_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.driving_capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.driving_capture_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DrivingCapture"

        /** See [startTicking]: a third of the shortest §3a window. */
        private const val TICK_INTERVAL_MILLIS = 60_000L
        private const val CHANNEL_ID = "parking_driving_capture"
        private const val NOTIFICATION_ID = 0x9A2D

        /**
         * Raise the service for the request that is about to be made.
         *
         * Best-effort and deliberately silent on failure. Android 12+ refuses a foreground
         * service started from the background unless the app is exempt from battery
         * optimisation, and a refusal must not take the location request down with it: a
         * throttled session still beats none, and it is what the app had before this class.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, DrivingLocationService::class.java))
            } catch (error: Exception) {
                // Class name only. `ForegroundServiceStartNotAllowedException` is the
                // expected one and is ordinary on a device the user has not exempted.
                Log.i(TAG, "capture service not started: ${error.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DrivingLocationService::class.java))
            } catch (error: Exception) {
                Log.i(TAG, "capture service not stopped: ${error.javaClass.simpleName}")
            }
        }
    }
}

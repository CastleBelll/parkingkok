package kr.parkingpin.app.trace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.parkingpin.app.R
import kr.parkingpin.app.data.DetectionStateStore
import kr.parkingpin.app.domain.trace.TraceLabelPrompt
import kr.parkingpin.app.domain.trace.TraceMode

/**
 * What [TraceRecorder] is allowed to know about prompting for a label.
 *
 * One method, no return value, never throws: the closed session is already on disk when
 * this is called, and a notification that cannot be posted must not be able to break the
 * recording path (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 best-effort).
 */
interface TraceLabelPrompting {
    /** Best-effort. Deciding that permission is missing is the implementation's business. */
    suspend fun requestPrompt(prompt: TraceLabelPrompt)
}

/** Used wherever prompting is not wired, so a collaborator is never null. */
object NoOpTraceLabelPrompting : TraceLabelPrompting {
    override suspend fun requestPrompt(prompt: TraceLabelPrompt) = Unit
}

/**
 * The notification system, reduced to the two things prompting needs.
 *
 * Exists so the decision in [TraceLabelPrompter] — post, or count a suppression — is
 * testable. `NotificationManager` cannot be reached from a JVM unit test.
 */
interface LabelPromptDelivering {
    /**
     * Whether a notification would be shown at all. `false` covers a denied
     * `POST_NOTIFICATIONS`, notifications switched off for the app, and a blocked channel;
     * none of them is an error and none is retried.
     */
    fun isAuthorized(): Boolean

    /** Posts. Swallows its own failures — see [TraceLabelPrompting]. */
    fun post(prompt: TraceLabelPrompt)
}

/**
 * Decides whether a closed session is worth a notification, and counts it when it is not.
 *
 * The decision is the whole of this class, which is why it is separate from the delivery it
 * drives: a missing permission has to be *visible* rather than silent, because a field
 * weekend that collected nothing would otherwise look exactly like a weekend nobody
 * travelled (§9 "조용히 버리지 마라"). The counter is persisted for the same reason the drop
 * counters are — the prompt is posted from a process that dies between two PendingIntent
 * deliveries, so an in-memory count would always read zero by the time anyone looked.
 */
class TraceLabelPrompter(
    private val delivery: LabelPromptDelivering,
    private val stateStore: DetectionStateStore,
) : TraceLabelPrompting {

    override suspend fun requestPrompt(prompt: TraceLabelPrompt) {
        if (!delivery.isAuthorized()) {
            stateStore.addTraceLabelPromptSuppressed(1)
            return
        }
        delivery.post(prompt)
    }
}

/**
 * The names the notification and the tap that comes back have to agree on.
 *
 * **Its own channel**, never docs/04_ANDROID_IMPLEMENTATION.md §9's `parking_detection` or
 * `parking_status`. This is diagnostics: it asks about a trip that already ended, and a
 * prompt sharing a channel with a product notification would make "the user muted the wrong
 * one" unanswerable — and would silence real parking alerts the day someone gets tired of
 * being asked what the bus was.
 */
object TraceLabelPromptChannel {
    const val ID: String = "trace_label_prompt_diagnostics"
    const val ACTION_PREFIX: String = "kr.parkingpin.app.TRACE_LABEL."
    const val EXTRA_SESSION_ID: String = "kr.parkingpin.app.extra.TRACE_SESSION_ID"
    const val EXTRA_MODE: String = "kr.parkingpin.app.extra.TRACE_MODE"

    /**
     * Android shows at most three action buttons on a notification, so the tail of
     * [TraceLabelPrompt.OFFERED_MODES] is dropped rather than silently ignored by the OS.
     * iOS fits four; the label written to disk is identical either way, and the mode that
     * drops off is the one still reachable from the in-app screen.
     */
    const val MAX_ACTIONS: Int = 3

    fun actionFor(mode: TraceMode): String = ACTION_PREFIX + mode.name

    /** `null` for an intent that is not one of ours. */
    fun modeOf(action: String?): TraceMode? {
        if (action == null || !action.startsWith(ACTION_PREFIX)) return null
        val name = action.removePrefix(ACTION_PREFIX)
        return TraceMode.entries.firstOrNull { it.name == name }
    }

    /** A stable id per session, so the right notification is the one that goes away. */
    fun notificationId(sessionId: String): Int = sessionId.hashCode()
}

/**
 * Posts through [NotificationManagerCompat].
 *
 * The channel is created here rather than in `Application.onCreate` so the whole feature is
 * one file to delete; `createNotificationChannel` is idempotent and costs nothing on a
 * channel that already exists.
 *
 * `IMPORTANCE_DEFAULT` on purpose: the point is that the user notices while they still
 * remember the trip. `LOW` would file it silently into the shade, which is where the in-app
 * screen already was.
 */
class NotificationLabelPromptDelivery(context: Context) : LabelPromptDelivering {

    private val appContext: Context = context.applicationContext

    override fun isAuthorized(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    override fun post(prompt: TraceLabelPrompt) {
        try {
            createChannel()
            val builder = NotificationCompat.Builder(appContext, TraceLabelPromptChannel.ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(TraceLabelPrompt.TITLE)
                .setContentText(prompt.body())
                .setStyle(NotificationCompat.BigTextStyle().bigText(prompt.body()))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // The body opens the app, where the labelling screen holds the modes that
                // did not fit as actions.
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)

            for (mode in TraceLabelPrompt.OFFERED_MODES.take(TraceLabelPromptChannel.MAX_ACTIONS)) {
                builder.addAction(
                    0,
                    TraceLabelPrompt.actionTitle(mode),
                    labelIntent(prompt.sessionId, mode),
                )
            }

            NotificationManagerCompat.from(appContext)
                .notify(TraceLabelPromptChannel.notificationId(prompt.sessionId), builder.build())
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS revoked between [isAuthorized] and here. Caught explicitly
            // rather than through the clause below, because it is the one failure that is
            // ordinary rather than unexpected — and because a notify() with no visible
            // SecurityException handler is a permission bug everywhere else in this app.
            Log.i(TAG, "trace label prompt not permitted: ${denied.javaClass.simpleName}")
        } catch (error: Exception) {
            // Best-effort: the trace is already on disk and the in-app screen still works.
            // The class name only, for the same reason the recording path logs no message.
            Log.e(TAG, "trace label prompt failed: ${error.javaClass.simpleName}")
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            TraceLabelPromptChannel.ID,
            appContext.getString(R.string.trace_label_prompt_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.trace_label_prompt_channel_description)
            setShowBadge(false)
        }
        appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * `data` as well as a distinct action, because [Intent.filterEquals] ignores extras: two
     * PendingIntents differing only in their session id would otherwise be the same one, and
     * the second trip's tap would label the first trip's session.
     */
    private fun labelIntent(sessionId: String, mode: TraceMode): PendingIntent {
        val intent = Intent(appContext, TraceLabelPromptReceiver::class.java).apply {
            action = TraceLabelPromptChannel.actionFor(mode)
            data = Uri.fromParts("parkingkok-trace-label", "$sessionId/${mode.name}", null)
            putExtra(TraceLabelPromptChannel.EXTRA_SESSION_ID, sessionId)
            putExtra(TraceLabelPromptChannel.EXTRA_MODE, mode.name)
        }
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        return PendingIntent.getActivity(
            appContext,
            0,
            intent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "ParkingpinTrace"
    }
}

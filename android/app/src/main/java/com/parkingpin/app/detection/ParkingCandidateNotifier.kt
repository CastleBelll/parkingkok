package com.parkingpin.app.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.parkingpin.app.MainActivity
import com.parkingpin.app.R
import com.parkingpin.app.domain.detection.ParkingCandidate
import com.parkingpin.app.domain.detection.ParkingCandidateNotice

/**
 * The notification system, reduced to the three things a candidate needs.
 *
 * It exists so [ParkingCandidateCoordinator]'s decisions — post, skip, withdraw — are
 * testable on the JVM; `NotificationManager` cannot be reached from a unit test.
 *
 * Nothing here may throw. docs/05 §10a is explicit that notification permission is not a
 * condition of correctness: a candidate that could not be announced is still a candidate,
 * and the app shows it on next launch.
 */
interface CandidateNotifying {

    /**
     * Whether a notification would be shown at all. `false` covers a denied
     * `POST_NOTIFICATIONS`, notifications switched off for the app, and a blocked channel.
     * None of them is an error and none is retried.
     */
    fun isAuthorized(): Boolean

    /** Posts, replacing any notification already showing for the same candidate id. */
    fun post(candidate: ParkingCandidate)

    /** Takes the notification down. Safe to call for a candidate that never had one. */
    fun withdraw(candidateId: String)
}

/** Used wherever notifying is not wired, so the collaborator is never null. */
object NoOpCandidateNotifying : CandidateNotifying {
    override fun isAuthorized(): Boolean = false
    override fun post(candidate: ParkingCandidate) = Unit
    override fun withdraw(candidateId: String) = Unit
}

/**
 * The names the notification, the tap and the action share.
 *
 * **`parking_detection`** — docs/04_ANDROID_IMPLEMENTATION.md §9's product channel, never
 * the trace label prompt's `trace_label_prompt_diagnostics`. The two ask completely
 * different questions: one is about a trip that already ended and exists to collect field
 * data, this one is the product. Muting the diagnostics prompt must never silence a real
 * parking alert, which is only true while they are separate channels.
 */
object ParkingCandidateChannel {

    const val ID: String = "parking_detection"

    const val ACTION_REJECT: String = "com.parkingpin.app.CANDIDATE_REJECT"

    const val EXTRA_CANDIDATE_ID: String = "com.parkingpin.app.extra.CANDIDATE_ID"

    /**
     * §10a: the notification is posted under the candidate's own id, so re-posting the
     * same candidate replaces it rather than stacking a second one, and withdrawing hits
     * the right one.
     */
    fun notificationId(candidateId: String): Int = candidateId.hashCode()
}

/**
 * Posts through [NotificationManagerCompat].
 *
 * The channel is created on the way in rather than in `Application.onCreate`, so the
 * feature is self-contained; `createNotificationChannel` is idempotent.
 *
 * `IMPORTANCE_DEFAULT`: the user is walking away from the car right now, and a silent
 * entry in the shade would be found long after the 45-minute window closed.
 */
class NotificationCandidateDelivery(context: Context) : CandidateNotifying {

    private val appContext: Context = context.applicationContext

    override fun isAuthorized(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    override fun post(candidate: ParkingCandidate) {
        try {
            createChannel()
            val builder = NotificationCompat.Builder(appContext, ParkingCandidateChannel.ID)
                // The app's own car glyph: a 24dp white-on-transparent silhouette, which
                // is exactly what a status-bar icon has to be.
                .setSmallIcon(R.drawable.ic_car)
                // Fixed copy (docs/02 §5). Nothing is interpolated, so there is no way for
                // a floor, an address or a coordinate to reach the shade (docs/09 §9).
                .setContentTitle(ParkingCandidateNotice.TITLE)
                .setContentText(ParkingCandidateNotice.BODY)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(openConfirmationIntent(candidate.id))
                .addAction(0, ParkingCandidateNotice.ACTION_OPEN, openConfirmationIntent(candidate.id))
                .addAction(0, ParkingCandidateNotice.ACTION_REJECT, rejectIntent(candidate.id))
                // §10a expiry: the OS withdraws it at the deadline even if this process
                // never runs again. It is a backstop for the visible half only — the
                // stored candidate is what decides whether anything can still be created
                // from it, and that check is the coordinator's.
                .setTimeoutAfter(candidate.expiresAtMillis - candidate.detectedAtMillis)
                // A guess the user may simply not want to answer. Swiping it away must not
                // be mistaken for 주차 아님, so the candidate outlives the notification and
                // expires on its own.
                .setAutoCancel(true)

            NotificationManagerCompat.from(appContext)
                .notify(ParkingCandidateChannel.notificationId(candidate.id), builder.build())
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS revoked between [isAuthorized] and here. Ordinary rather
            // than unexpected, and the candidate is already stored.
            Log.i(TAG, "candidate notification not permitted: ${denied.javaClass.simpleName}")
        } catch (error: Exception) {
            // Best-effort by contract. The class name only — never the candidate, which
            // would put a coordinate in logcat.
            Log.e(TAG, "candidate notification failed: ${error.javaClass.simpleName}")
        }
    }

    override fun withdraw(candidateId: String) {
        NotificationManagerCompat.from(appContext)
            .cancel(ParkingCandidateChannel.notificationId(candidateId))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            ParkingCandidateChannel.ID,
            appContext.getString(R.string.candidate_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.candidate_channel_description)
            setShowBadge(true)
        }
        appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Opens the confirmation screen for this candidate (§10a "What a tap does").
     *
     * `CLEAR_TOP or SINGLE_TOP` so the tap reuses the running activity and lands on the
     * screen rather than stacking a second copy of the app. `data` as well as the extra,
     * because [Intent.filterEquals] ignores extras: two candidates' PendingIntents would
     * otherwise be the same object, and the second trip's tap would open the first trip's
     * candidate.
     */
    private fun openConfirmationIntent(candidateId: String): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            data = Uri.fromParts("parkingkok-candidate", candidateId, null)
            putExtra(ParkingCandidateChannel.EXTRA_CANDIDATE_ID, candidateId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** `주차 아님`, answered from the shade without the app coming forward. */
    private fun rejectIntent(candidateId: String): PendingIntent {
        val intent = Intent(appContext, ParkingCandidateReceiver::class.java).apply {
            action = ParkingCandidateChannel.ACTION_REJECT
            data = Uri.fromParts("parkingkok-candidate-reject", candidateId, null)
            putExtra(ParkingCandidateChannel.EXTRA_CANDIDATE_ID, candidateId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "PkDetection"
    }
}

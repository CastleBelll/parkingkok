package com.sjstudioz.parkingpin.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sjstudioz.parkingpin.MainActivity
import com.sjstudioz.parkingpin.R
import com.sjstudioz.parkingpin.domain.detection.ParkingEndProposalNotice
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord

/**
 * The notification system, reduced to what a departure proposal needs (docs/05 §11a).
 *
 * Testable on the JVM for the reason [CandidateNotifying] is. Nothing here may throw:
 * notification permission is not a condition of correctness, and a proposal that could not
 * be announced is still asked on the home card.
 */
interface ParkingEndProposalNotifying {

    /** Whether a notification would be shown at all (permission, app switch, channel). */
    fun isAuthorized(): Boolean

    /** Posts the question about [record], replacing the one already showing. */
    fun post(record: ParkingRecord)

    /** Takes the question down. Safe when none is showing. */
    fun withdraw()
}

/**
 * The names the proposal notification and its two actions share.
 *
 * It posts on the candidate's product channel ([ParkingCandidateChannel.ID]): both are the
 * detector asking the user something, and a second channel would be a second switch the user
 * has to find to hear from the same feature.
 */
object ParkingEndProposalChannel {

    const val ACTION_END: String = "com.sjstudioz.parkingpin.PARKING_END_ACCEPT"

    const val ACTION_KEEP: String = "com.sjstudioz.parkingpin.PARKING_END_KEEP"

    const val EXTRA_RECORD_ID: String = "com.sjstudioz.parkingpin.extra.PARKING_END_RECORD_ID"

    /** One question at a time (§11a), so one fixed id: a later departure replaces it in place. */
    const val NOTIFICATION_ID: Int = 0x5045_4E44
}

/** Posts through [NotificationManagerCompat], on the candidate channel. */
class NotificationParkingEndProposalDelivery(context: Context) : ParkingEndProposalNotifying {

    private val appContext: Context = context.applicationContext

    override fun isAuthorized(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    override fun post(record: ParkingRecord) {
        try {
            createChannel()
            val builder = NotificationCompat.Builder(appContext, ParkingCandidateChannel.ID)
                .setSmallIcon(R.drawable.ic_car)
                // A guess stated as a guess, then a question (docs/10 §7). The place is the
                // user's own floor and spot — never an address or a coordinate.
                .setContentTitle(ParkingEndProposalNotice.TITLE)
                .setContentText(ParkingEndProposalNotice.body(record))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(openHomeIntent())
                .addAction(0, ParkingEndProposalNotice.ACTION_END, answerIntent(ParkingEndProposalChannel.ACTION_END, record.id))
                .addAction(0, ParkingEndProposalNotice.ACTION_KEEP, answerIntent(ParkingEndProposalChannel.ACTION_KEEP, record.id))
                // Swiping it away is not an answer: the proposal stays pending and the home
                // card keeps asking.
                .setAutoCancel(true)

            NotificationManagerCompat.from(appContext)
                .notify(ParkingEndProposalChannel.NOTIFICATION_ID, builder.build())
        } catch (denied: SecurityException) {
            Log.i(TAG, "departure notification not permitted: ${denied.javaClass.simpleName}")
        } catch (error: Exception) {
            // Best-effort by contract. The class name only — never the record.
            Log.e(TAG, "departure notification failed: ${error.javaClass.simpleName}")
        }
    }

    override fun withdraw() {
        NotificationManagerCompat.from(appContext).cancel(ParkingEndProposalChannel.NOTIFICATION_ID)
    }

    /** The same channel [NotificationCandidateDelivery] creates; creating it is idempotent. */
    private fun createChannel() {
        val channel = NotificationChannel(
            ParkingCandidateChannel.ID,
            appContext.getString(R.string.candidate_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.candidate_channel_description)
            setShowBadge(true)
        }
        appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** A tap on the body opens home, where the same question waits on the parking card. */
    private fun openHomeIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Answered from the shade without the app coming forward. `data` carries the action and
     * the record so the two PendingIntents are distinct ([Intent.filterEquals] ignores extras).
     */
    private fun answerIntent(action: String, recordId: String): PendingIntent {
        val intent = Intent(appContext, ParkingEndProposalReceiver::class.java).apply {
            this.action = action
            data = Uri.fromParts("parkingpin-parking-end", "$action/$recordId", null)
            putExtra(ParkingEndProposalChannel.EXTRA_RECORD_ID, recordId)
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

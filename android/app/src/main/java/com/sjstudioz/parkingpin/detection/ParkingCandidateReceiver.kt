package com.sjstudioz.parkingpin.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.sjstudioz.parkingpin.ParkingpinApplication
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Turns one tap on `주차 아님` into a discarded candidate (docs/05 §10a).
 *
 * A receiver rather than an activity, because docs/10 §7a says the honest answer to a
 * guess must be the cheapest thing on the notification: it is answered from the lock
 * screen and the app never comes forward. Rejecting "returns the user to where they
 * were", and where they were is not this app.
 *
 * The work runs on the application scope with [goAsync], the same shape
 * [ActivityTransitionReceiver] uses — `onReceive` is on the main thread and this writes.
 */
class ParkingCandidateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ParkingCandidateChannel.ACTION_REJECT -> reject(context, intent)
            ParkingCandidateChannel.ACTION_ENTER_FLOOR -> enterFloor(context, intent)
        }
    }

    /**
     * `층 입력` answered inline (docs/02 §5, iOS `CandidateNotificationResponder`): the
     * candidate is confirmed with the typed floor, through the same [CandidateConfirmation]
     * the confirmation screen uses. Empty or unparseable text still confirms — the user said
     * "yes, I parked", FR-006 makes the floor optional, and [FloorParser] keeps whatever was
     * typed. A candidate already gone confirms nothing.
     */
    private fun enterFloor(context: Context, intent: Intent) {
        val candidateId = intent.getStringExtra(ParkingCandidateChannel.EXTRA_CANDIDATE_ID)
        if (candidateId.isNullOrEmpty()) {
            Log.w(TAG, "floor entry ignored: no candidate id")
            return
        }
        val typed = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ParkingCandidateChannel.KEY_FLOOR)
            ?.toString()
            .orEmpty()

        // Taken down now: an inline reply leaves a spinner on the notification until it is
        // replaced or cancelled, and the answer has been given.
        NotificationManagerCompat.from(context)
            .cancel(ParkingCandidateChannel.notificationId(candidateId))

        val container = ParkingpinApplication.containerOf(context)
        if (container == null) {
            Log.w(TAG, "floor entry arrived with no container")
            return
        }
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                container.candidateConfirmation.confirm(candidateId, floorAnswer(typed))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // By type only: the message may quote the row, which holds the location.
                Log.w(TAG, "floor entry failed: ${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun reject(context: Context, intent: Intent) {
        val candidateId = intent.getStringExtra(ParkingCandidateChannel.EXTRA_CANDIDATE_ID)
        if (candidateId.isNullOrEmpty()) {
            Log.w(TAG, "candidate rejection ignored: no candidate id")
            return
        }

        // The tap has been acted on, so the notification goes now rather than after the
        // write: leaving it up invites a second tap, and rejection is idempotent anyway.
        NotificationManagerCompat.from(context)
            .cancel(ParkingCandidateChannel.notificationId(candidateId))

        val container = ParkingpinApplication.containerOf(context)
        if (container == null) {
            // The store was never reached, so the candidate survives to its expiry and the
            // event is lost. Logged rather than swallowed because §10a calls rejection the
            // event that pays for the whole feature.
            Log.w(TAG, "candidate rejection arrived with no container")
            return
        }

        val pending = goAsync()
        container.applicationScope.launch {
            try {
                container.parkingCandidateCoordinator.reject(candidateId)
                // §3a `CANDIDATE_PENDING -> IDLE` on rejection. Without it the machine
                // stays pending and §12's one-candidate rule keeps the next trip silent.
                container.parkingDetectionRuntime.handleUserAnswer(
                    DetectionEvent.UserRejectedParking(container.clock.nowEpochMillis()),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PkDetection"

        /** FR-005's free text, capped like FR-006's short fields so the shade cannot overflow a row. */
        private const val MAX_FLOOR_TEXT = 40

        /** The typed floor as the record stores it; blank is no floor, not a refusal. */
        fun floorAnswer(typed: String): ConfirmedCandidateDetails =
            ConfirmedCandidateDetails(floor = FloorParser.parse(typed.trim().take(MAX_FLOOR_TEXT)))
    }
}

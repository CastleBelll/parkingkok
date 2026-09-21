package com.parkingpin.app.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.parkingpin.app.ParkingpinApplication
import com.parkingpin.app.domain.detection.DetectionEvent
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
        if (intent.action != ParkingCandidateChannel.ACTION_REJECT) return
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

    private companion object {
        const val TAG = "PkDetection"
    }
}

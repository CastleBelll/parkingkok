package kr.parkingpin.app.trace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kr.parkingpin.app.ParkingpinApplication
import kr.parkingpin.app.domain.trace.TraceLabelPrompt
import kotlinx.coroutines.launch

/**
 * Turns one tap on a label prompt into a stored label
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 labelling).
 *
 * A receiver rather than an activity, because the whole point is that labelling costs no app
 * launch: the notification is answered from the lock screen and the app never comes forward.
 *
 * The work happens on the application scope with [goAsync], the same shape
 * [kr.parkingpin.app.detection.ActivityTransitionReceiver] uses — `onReceive` runs on the
 * main thread and the write is a file rewrite.
 */
class TraceLabelPromptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mode = TraceLabelPromptChannel.modeOf(intent.action)
        val sessionId = intent.getStringExtra(TraceLabelPromptChannel.EXTRA_SESSION_ID)
        if (mode == null || sessionId.isNullOrEmpty()) {
            // An intent that is not one of ours, or one whose extras did not survive. Not
            // an error worth a crash on a diagnostics path.
            Log.w(TAG, "trace label prompt tap ignored: action=${intent.action}")
            return
        }

        // The tap has been acted on, so the notification goes now rather than after the
        // write: leaving it up while a file is rewritten invites a second tap on the same
        // session, and the label is idempotent either way.
        NotificationManagerCompat.from(context)
            .cancel(TraceLabelPromptChannel.notificationId(sessionId))

        val container = ParkingpinApplication.containerOf(context)
        if (container == null) {
            Log.w(TAG, "trace label prompt tap arrived with no container")
            return
        }

        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val failure = container.traceRecorder.setLabel(sessionId, TraceLabelPrompt.labelFor(mode))
                if (failure != null) {
                    // A session the rolling cap evicted between the prompt and the tap is an
                    // ordinary outcome, and it is already counted in the recorder's summary.
                    Log.i(TAG, "trace label prompt tap could not be applied: $failure")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ParkingpinTrace"
    }
}

package com.parkingpin.app.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.parkingpin.app.ParkingkokApplication
import kotlinx.coroutines.launch

/**
 * Thin entry point for Play services transition callbacks
 * (docs/04_ANDROID_IMPLEMENTATION.md §5).
 *
 * `onReceive` only decodes the SDK payload and hands it to the application layer via
 * `goAsync()`, which §5 allows for short bounded async work. No DB loop, no network, no
 * location request runs here.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRANSITION) return
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        val container = ParkingkokApplication.containerOf(context) ?: return
        val clock = container.clock
        val nowElapsedNanos = clock.elapsedRealtimeNanos()
        val nowEpochMillis = clock.nowEpochMillis()

        val transitions = result.transitionEvents.mapNotNull { event ->
            val activity = SdkTransitionCodec.toMotionActivity(event.activityType) ?: return@mapNotNull null
            val kind = SdkTransitionCodec.toTransitionKind(event.transitionType) ?: return@mapNotNull null
            TransitionEventIngestor.RawTransition(
                activity = activity,
                transition = kind,
                atMillis = SdkTransitionCodec.eventTimeMillis(
                    eventElapsedRealtimeNanos = event.elapsedRealTimeNanos,
                    nowElapsedRealtimeNanos = nowElapsedNanos,
                    nowEpochMillis = nowEpochMillis,
                ),
            )
        }
        if (transitions.isEmpty()) return

        // Activity type and direction only. No coordinates exist on this path at all.
        Log.i(TAG, "received ${transitions.size} transition(s)")

        val pendingResult = goAsync()
        container.applicationScope.launch {
            try {
                container.transitionEventIngestor.ingest(transitions)
                container.diagnosticsExporter.export()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TRANSITION = "com.parkingpin.app.action.ACTIVITY_TRANSITION"
        private const val TAG = "ParkingkokTransition"
    }
}

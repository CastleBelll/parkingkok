package com.parkingpin.app.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationResult
import com.parkingpin.app.ParkingpinApplication
import com.parkingpin.app.domain.location.LocationSample
import kotlinx.coroutines.launch

/**
 * Thin entry point for Fused Location batches
 * (docs/04_ANDROID_IMPLEMENTATION.md §5: the receiver decodes and delegates, nothing else).
 *
 * `onReceive` maps the SDK payload to domain samples and hands them to the application
 * layer via `goAsync()`. No DB loop, no network, no nested location request runs here.
 */
class LocationUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCATION_UPDATE) return
        if (!LocationResult.hasResult(intent)) return
        val result = LocationResult.extractResult(intent) ?: return

        val container = ParkingpinApplication.containerOf(context) ?: return

        val samples = result.locations.map { location ->
            LocationSample(
                atMillis = location.time,
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracyM = location.accuracy,
                speedMps = if (location.hasSpeed()) location.speed else null,
            )
        }
        if (samples.isEmpty()) return

        // Count only. Logging an accuracy here would still be safe, but the count is what
        // P0 needs and the less this path knows how to print, the less it can ever leak
        // (docs/00_CORE_RULES.md Privacy).
        Log.i(TAG, "received location batch size=${samples.size}")

        val pendingResult = goAsync()
        container.applicationScope.launch {
            try {
                container.locationSessionController.onLocationBatch(samples)
                // After the session controller, which is what folds the fixes into the
                // §7 evidence the diagnostics read, and before the export so the report
                // describes the state the batch actually left behind (docs/05 §3a).
                container.parkingDetectionRuntime.handleLocations(samples)
                container.diagnosticsExporter.export()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOCATION_UPDATE = "com.parkingpin.app.action.LOCATION_UPDATE"
        private const val TAG = "ParkingpinLocation"
    }
}

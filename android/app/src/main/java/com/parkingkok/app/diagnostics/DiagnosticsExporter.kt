package com.parkingkok.app.diagnostics

import com.parkingkok.app.core.Clock
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.detection.DetectionRegistrationCoordinator
import com.parkingkok.app.detection.FusedLocationSessionController
import com.parkingkok.app.trace.TraceRecorder
import kotlinx.coroutines.flow.first

/**
 * Assembles and writes the diagnostics report.
 *
 * The single writer, for the same reason iOS has one: it is the only place that holds the
 * persisted state, the volatile ring buffer, and the permission statuses at the same time.
 * Anything else writing the file would publish a report that was true of nothing.
 *
 * P0 instrumentation. It exists so real-drive evidence can be recovered from a device
 * (docs/05_PARKING_DETECTION_ENGINE.md §18 field tuning), and should be gated off before
 * a production release.
 */
class DiagnosticsExporter(
    private val store: DetectionStateStore,
    private val sessionController: FusedLocationSessionController,
    private val registrationCoordinator: DetectionRegistrationCoordinator,
    private val hasActivityRecognitionPermission: () -> Boolean,
    private val reportStore: DiagnosticsReportStore,
    private val clock: Clock,
    private val traceRecorder: TraceRecorder,
) {

    /** @return null on success, or a short, coordinate-free reason string on failure. */
    suspend fun export(): String? {
        val report = DiagnosticsReport.from(
            nowMillis = clock.nowEpochMillis(),
            checkpoint = store.readCheckpointOnce(),
            sessionState = store.readLocationSessionStateOnce(),
            qualityHistory = sessionController.qualityHistory(),
            transitions = store.recentEvents.first(),
            activityRecognitionGranted = hasActivityRecognitionPermission(),
            foregroundLocationGranted = sessionController.hasForegroundLocationPermission(),
            backgroundLocationGranted = sessionController.hasBackgroundLocationPermission(),
            smartDetectionEnabled = store.readDesiredEnabledOnce(),
            transitionRegistration = registrationCoordinator.status.value,
            trace = traceRecorder.summary(),
        )
        return reportStore.write(report)
    }
}

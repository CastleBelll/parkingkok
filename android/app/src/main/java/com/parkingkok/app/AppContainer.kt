package com.parkingkok.app

import android.content.Context
import com.parkingkok.app.core.Clock
import com.parkingkok.app.core.SystemClock
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.data.detectionDataStore
import com.parkingkok.app.detection.ActivityTransitionRegistrar
import com.parkingkok.app.detection.DetectionRegistrationCoordinator
import com.parkingkok.app.detection.FusedLocationSessionController
import com.parkingkok.app.detection.FusedLocationSessionRegistrar
import com.parkingkok.app.detection.TransitionEventIngestor
import com.parkingkok.app.diagnostics.DiagnosticsExporter
import com.parkingkok.app.diagnostics.FileDiagnosticsReportStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual composition root. A DI framework is not justified at this size
 * (CLAUDE.md: no over-engineering — build only what is needed now).
 *
 * The application scope lives here rather than inside a repository, per
 * docs/16_CODING_STANDARDS.md §2. It is the owner receivers borrow for their bounded
 * `goAsync()` work, and it lives exactly as long as the process.
 */
class AppContainer(context: Context, val clock: Clock = SystemClock) {

    private val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val detectionStateStore: DetectionStateStore = DetectionStateStore(detectionDataStore(appContext))

    private val registrar = ActivityTransitionRegistrar(appContext)

    val registrationCoordinator: DetectionRegistrationCoordinator =
        DetectionRegistrationCoordinator(detectionStateStore, registrar, clock)

    val locationSessionController: FusedLocationSessionController = FusedLocationSessionController(
        store = detectionStateStore,
        registrar = FusedLocationSessionRegistrar(appContext),
        clock = clock,
    )

    val transitionEventIngestor: TransitionEventIngestor =
        TransitionEventIngestor(detectionStateStore, clock, locationSessionController)

    val diagnosticsExporter: DiagnosticsExporter = DiagnosticsExporter(
        store = detectionStateStore,
        sessionController = locationSessionController,
        registrationCoordinator = registrationCoordinator,
        hasActivityRecognitionPermission = ::hasActivityRecognitionPermission,
        reportStore = FileDiagnosticsReportStore(FileDiagnosticsReportStore.defaultFile(appContext)),
        clock = clock,
    )

    fun hasActivityRecognitionPermission(): Boolean = registrar.hasPermission()
}

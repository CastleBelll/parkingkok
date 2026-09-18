package com.parkingkok.app

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
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
import com.parkingkok.app.domain.trace.TraceDeviceInfo
import com.parkingkok.app.trace.FileTraceStore
import com.parkingkok.app.trace.NotificationLabelPromptDelivery
import com.parkingkok.app.trace.TraceLabelPrompter
import com.parkingkok.app.trace.TraceRecorder
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

    /**
     * Hardware identity only — model and OS build, never anything that identifies a
     * person (docs/09_SECURITY_PRIVACY_COMPLIANCE.md). It is what makes a trace comparable
     * across the reference devices docs/05_PARKING_DETECTION_ENGINE.md §18 lists.
     */
    val traceRecorder: TraceRecorder = TraceRecorder(
        store = FileTraceStore(FileTraceStore.defaultDirectory(appContext)),
        stateStore = detectionStateStore,
        device = TraceDeviceInfo(
            model = Build.MODEL,
            osVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            appVersion = APP_VERSION,
        ),
        // §9's labelling problem: the in-app screen went unused for three days because the
        // user carries the phone without opening the app. P0 instrumentation — this wiring
        // and the two `trace/TraceLabelPrompt*` files go together when it is removed.
        prompter = TraceLabelPrompter(
            delivery = NotificationLabelPromptDelivery(appContext),
            stateStore = detectionStateStore,
        ),
    )

    val locationSessionController: FusedLocationSessionController = FusedLocationSessionController(
        store = detectionStateStore,
        registrar = FusedLocationSessionRegistrar(appContext),
        clock = clock,
        traceRecorder = traceRecorder,
    )

    val transitionEventIngestor: TransitionEventIngestor =
        TransitionEventIngestor(detectionStateStore, clock, locationSessionController, traceRecorder)

    val diagnosticsExporter: DiagnosticsExporter = DiagnosticsExporter(
        store = detectionStateStore,
        sessionController = locationSessionController,
        registrationCoordinator = registrationCoordinator,
        hasActivityRecognitionPermission = ::hasActivityRecognitionPermission,
        reportStore = FileDiagnosticsReportStore(FileDiagnosticsReportStore.defaultFile(appContext)),
        clock = clock,
        traceRecorder = traceRecorder,
    )

    fun hasActivityRecognitionPermission(): Boolean = registrar.hasPermission()

    /**
     * Whether a label prompt would be shown at all.
     *
     * Covers more than the runtime grant: notifications switched off for the app, or the
     * diagnostics channel blocked, read the same way here as they do in
     * [com.parkingkok.app.trace.NotificationLabelPromptDelivery], which is the point —
     * the screen and the poster must not disagree about whether prompting works.
     */
    fun hasNotificationPermission(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    private companion object {
        /**
         * Mirrors `versionName (versionCode)` from the build file.
         *
         * Hardcoded because `buildConfig` is switched off for this module and turning it
         * on to read one string would slow every build. A trace whose appVersion is stale
         * is still a readable trace, which is the right failure for a diagnostics field.
         */
        const val APP_VERSION = "0.1.0 (1)"
    }
}

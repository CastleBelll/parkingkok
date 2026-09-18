package com.parkingkok.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.parkingkok.app.analytics.AnalyticsCollectionGate
import com.parkingkok.app.analytics.AnalyticsConsentStore
import com.parkingkok.app.analytics.AnalyticsRecorder
import com.parkingkok.app.analytics.AnalyticsRecording
import com.parkingkok.app.analytics.AnalyticsSink
import com.parkingkok.app.analytics.FirebaseAnalyticsCollectionControl
import com.parkingkok.app.analytics.FirebaseAnalyticsSink
import com.parkingkok.app.analytics.LogAnalyticsSink
import com.parkingkok.app.analytics.NoAnalyticsCollectionControl
import com.parkingkok.app.analytics.NoOpAnalyticsSink
import com.parkingkok.app.core.Clock
import com.parkingkok.app.core.SystemClock
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.data.detectionDataStore
import com.parkingkok.app.data.parking.ParkingDatabase
import com.parkingkok.app.data.parking.RoomParkingRepository
import com.parkingkok.app.data.photo.FileParkingPhotoImageLoader
import com.parkingkok.app.data.photo.FileParkingPhotoStore
import com.parkingkok.app.data.photo.JpegPhotoEncoder
import com.parkingkok.app.data.photo.ParkingPhotoFiles
import com.parkingkok.app.data.photo.ParkingPhotoImageLoader
import com.parkingkok.app.detection.ActivityTransitionRegistrar
import com.parkingkok.app.detection.DetectionRegistrationCoordinator
import com.parkingkok.app.detection.FusedLocationSessionController
import com.parkingkok.app.detection.FusedLocationSessionRegistrar
import com.parkingkok.app.detection.TransitionEventIngestor
import com.parkingkok.app.diagnostics.DiagnosticsExporter
import com.parkingkok.app.diagnostics.FileDiagnosticsReportStore
import com.parkingkok.app.domain.parking.ParkingLocationProvider
import com.parkingkok.app.domain.parking.ParkingRepository
import com.parkingkok.app.domain.parking.usecase.CleanUpOrphanPhotosUseCase
import com.parkingkok.app.domain.photo.ParkingPhotoStore
import com.parkingkok.app.domain.trace.TraceDeviceInfo
import com.parkingkok.app.domain.widget.ParkingWidgetSync
import com.parkingkok.app.entitlement.isWidgetStepperEntitled
import com.parkingkok.app.identity.AnonymousIdentity
import com.parkingkok.app.identity.FirebaseAnonymousSignIn
import com.parkingkok.app.identity.LazyAnonymousIdentity
import com.parkingkok.app.identity.UnavailableAnonymousIdentity
import com.parkingkok.app.location.CheckpointParkingLocationProvider
import com.parkingkok.app.trace.FileTraceStore
import com.parkingkok.app.trace.NotificationLabelPromptDelivery
import com.parkingkok.app.trace.TraceLabelPrompter
import com.parkingkok.app.trace.TraceRecorder
import com.parkingkok.app.widget.GlanceWidgetProjectionStore
import com.parkingkok.app.widget.anyParkingWidgetPlaced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * docs/07 "동의". Off until the user turns it on; [analyticsRecorder] re-reads it on
     * every event, so the settings toggle stops transmission without any further wiring.
     */
    val analyticsConsentStore: AnalyticsConsentStore =
        AnalyticsConsentStore(detectionDataStore(appContext))

    /**
     * The Firebase project, or null on a build that had no `google-services.json`
     * (CI, fork PRs — see the conditional plugin in `app/build.gradle.kts`).
     *
     * Read rather than created: `FirebaseInitProvider` has already run by the time an
     * `Application` exists. Creating the [FirebaseApp] is local work — it reads the
     * generated resources and builds an options object — and the manifest's
     * `firebase_analytics_collection_enabled=false` is what keeps the SDK from doing
     * anything else with it until consent arrives.
     */
    private val firebaseApp: FirebaseApp? = FirebaseApp.getApps(appContext).firstOrNull()

    private val firebaseAnalytics: FirebaseAnalytics? by lazy {
        firebaseApp?.let { FirebaseAnalytics.getInstance(appContext) }
    }

    /**
     * docs/07 "동의", the half the settings toggle alone cannot cover: Firebase collects
     * `session_start` and friends on its own, behind [analyticsRecorder]'s back.
     * [ParkingkokApplication] runs this for the life of the process.
     */
    val analyticsCollectionGate: AnalyticsCollectionGate by lazy {
        AnalyticsCollectionGate(
            consentStore = analyticsConsentStore,
            control = firebaseAnalytics?.let(::FirebaseAnalyticsCollectionControl)
                ?: NoAnalyticsCollectionControl,
        )
    }

    /**
     * docs/07 §2 fixed Firebase Analytics as the transport. A build without a Firebase
     * project keeps the pre-Firebase behaviour — a local readout in a debuggable build,
     * nothing at all otherwise — rather than pretending to have one.
     */
    private val analyticsSink: AnalyticsSink by lazy {
        firebaseAnalytics?.let(::FirebaseAnalyticsSink)
            ?: if (isDebuggable) LogAnalyticsSink else NoOpAnalyticsSink
    }

    /**
     * Lazy so that a process started by a detection broadcast does not spin up
     * AppMeasurement for a recorder it never calls — the same reason the database and the
     * photo store below are lazy.
     */
    val analyticsRecorder: AnalyticsRecording by lazy {
        AnalyticsRecorder(
            consentStore = analyticsConsentStore,
            sink = analyticsSink,
            clock = clock,
        )
    }

    /**
     * docs/07 §4 / §13: the technical anonymous identity, created at the moment a backend
     * call first needs a caller and never before. No screen awaits it — see
     * [AnonymousIdentity].
     */
    val anonymousIdentity: AnonymousIdentity by lazy {
        firebaseApp
            ?.let { LazyAnonymousIdentity(FirebaseAnonymousSignIn(FirebaseAuth.getInstance(it))) }
            ?: UnavailableAnonymousIdentity
    }


    /**
     * Read from the merged manifest rather than `BuildConfig.DEBUG`, which does not exist —
     * `buildConfig` is off for this module and turning it on to read one flag would slow
     * every build. Mirrors iOS's `#if PK_DEV` guard on `OSLogAnalyticsSink`.
     */
    private val isDebuggable: Boolean
        get() = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Local parking storage. Opened lazily so a process started by a detection broadcast
     * does not pay for a database it will not read — the receivers touch the detection
     * DataStore only.
     */
    private val parkingDatabase: ParkingDatabase by lazy { ParkingDatabase.open(appContext) }

    val parkingRepository: ParkingRepository by lazy {
        RoomParkingRepository(parkingDatabase.parkingRecordDao())
    }

    /**
     * Where parking photos live: `filesDir/parking-photos`, app-private (docs/06 §4).
     *
     * Lazy for the same reason the database is — a process started by a detection
     * broadcast never looks at a photo.
     */
    private val parkingPhotoFiles: ParkingPhotoFiles by lazy {
        ParkingPhotoFiles(ParkingPhotoFiles.defaultDirectory(appContext.filesDir))
    }

    val parkingPhotoStore: ParkingPhotoStore by lazy {
        FileParkingPhotoStore(parkingPhotoFiles, JpegPhotoEncoder())
    }

    val parkingPhotoImageLoader: ParkingPhotoImageLoader by lazy {
        FileParkingPhotoImageLoader(parkingPhotoFiles)
    }

    /**
     * The orphan sweep (FR-007 photos are sensitive local data, docs/06 §1).
     *
     * Exposed rather than run from [ParkingkokApplication] because it is the first thing
     * that would open the database on a process a broadcast started, which is the cost the
     * lazy database above exists to avoid. The shell runs it once, when there is a screen.
     */
    val cleanUpOrphanPhotos: CleanUpOrphanPhotosUseCase by lazy {
        CleanUpOrphanPhotosUseCase(parkingRepository, parkingPhotoStore)
    }

    /**
     * docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a "Entitlement", read here and nowhere else.
     *
     * The one function that decides is `isWidgetStepperEntitled`; this property is its
     * only caller, and the answer reaches the widget as a field of the projection so no
     * Glance code has to ask.
     */
    val isWidgetStepperEntitled: Boolean get() = isWidgetStepperEntitled(isDebuggable)

    /**
     * Keeps the home-screen widgets equal to Room (docs/06 §4, §7).
     *
     * Lazy like the database it reads: a process a detection broadcast started never
     * touches this unless [syncParkingWidgets] finds a widget actually on screen.
     */
    val parkingWidgetSync: ParkingWidgetSync by lazy {
        ParkingWidgetSync(
            repository = parkingRepository,
            store = GlanceWidgetProjectionStore(appContext),
            stepperEntitled = isWidgetStepperEntitled,
        )
    }

    private val parkingWidgetSyncStarted = AtomicBoolean(false)

    /**
     * Brings every placed widget up to date and, the first time, keeps it that way.
     *
     * Called from [ParkingkokApplication] on process start and from the widget receivers
     * when the host asks for an update — a newly placed widget has an empty state file,
     * and a reboot delivers `onUpdate` before anything else runs.
     *
     * The guard is what protects the lazy database: with no widget on screen this returns
     * before anything opens Room, which is the same bargain the photo store and the
     * analytics recorder make. The one-shot refresh is docs/06 §8's startup repair — a
     * projection left behind by a session that has since been completed is replaced by
     * what Room actually holds.
     */
    fun syncParkingWidgets() {
        if (!anyParkingWidgetPlaced(appContext)) return
        applicationScope.launch { parkingWidgetSync.refresh() }
        // The collector is what saves every mutating use case from having to remember the
        // widget exists. One per process, hence the flag.
        if (parkingWidgetSyncStarted.compareAndSet(false, true)) {
            applicationScope.launch { parkingWidgetSync.keepInSync() }
        }
    }

    /**
     * Where a manual save gets its coordinates, when there are any.
     *
     * FR-001: this returning null is an ordinary outcome, not a failure — see
     * [CheckpointParkingLocationProvider].
     */
    val parkingLocationProvider: ParkingLocationProvider by lazy {
        CheckpointParkingLocationProvider(detectionStateStore)
    }

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

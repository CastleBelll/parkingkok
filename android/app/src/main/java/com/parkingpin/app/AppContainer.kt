package com.parkingpin.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.parkingpin.app.analytics.AnalyticsCollectionGate
import com.parkingpin.app.analytics.AnalyticsConsentStore
import com.parkingpin.app.analytics.AnalyticsRecorder
import com.parkingpin.app.analytics.AnalyticsRecording
import com.parkingpin.app.analytics.AnalyticsSink
import com.parkingpin.app.analytics.FirebaseAnalyticsCollectionControl
import com.parkingpin.app.analytics.FirebaseAnalyticsSink
import com.parkingpin.app.analytics.LogAnalyticsSink
import com.parkingpin.app.analytics.NoAnalyticsCollectionControl
import com.parkingpin.app.analytics.NoOpAnalyticsSink
import com.parkingpin.app.core.Clock
import com.parkingpin.app.core.SystemClock
import com.parkingpin.app.data.DetectionStateStore
import com.parkingpin.app.data.detectionDataStore
import com.parkingpin.app.data.parking.ParkingDatabase
import com.parkingpin.app.data.parking.RoomParkingRepository
import com.parkingpin.app.data.photo.CameraCaptureFile
import com.parkingpin.app.data.photo.FileParkingPhotoImageLoader
import com.parkingpin.app.data.photo.FileParkingPhotoStore
import com.parkingpin.app.data.photo.JpegPhotoEncoder
import com.parkingpin.app.data.photo.ParkingPhotoFiles
import com.parkingpin.app.data.photo.MlKitPillarTextReader
import com.parkingpin.app.data.photo.ParkingPhotoImageLoader
import com.parkingpin.app.detection.ActivityTransitionRegistrar
import com.parkingpin.app.detection.DetectionRegistrationCoordinator
import com.parkingpin.app.detection.FusedLocationSessionController
import com.parkingpin.app.detection.FusedLocationSessionRegistrar
import com.parkingpin.app.detection.NotificationCandidateDelivery
import com.parkingpin.app.detection.ParkingCandidateCoordinator
import com.parkingpin.app.detection.ParkingDetectionRuntime
import com.parkingpin.app.detection.TransitionEventIngestor
import com.parkingpin.app.diagnostics.DiagnosticsExporter
import com.parkingpin.app.diagnostics.FileDiagnosticsReportStore
import com.parkingpin.app.domain.parking.ParkingLocationProvider
import com.parkingpin.app.domain.parking.ParkingRepository
import com.parkingpin.app.domain.parking.usecase.AttachParkingPhotoUseCase
import com.parkingpin.app.domain.parking.usecase.CleanUpOrphanPhotosUseCase
import com.parkingpin.app.domain.photo.ParkingPhotoStore
import com.parkingpin.app.domain.photo.PillarTextReader
import com.parkingpin.app.domain.photo.ReadPillarSuggestionUseCase
import com.parkingpin.app.ui.manual.PillarPhotoEntry
import com.parkingpin.app.domain.trace.TraceDeviceInfo
import com.parkingpin.app.domain.widget.ParkingWidgetSync
import com.parkingpin.app.entitlement.isWidgetStepperEntitled
import com.parkingpin.app.identity.AnonymousIdentity
import com.parkingpin.app.identity.FirebaseAnonymousSignIn
import com.parkingpin.app.identity.LazyAnonymousIdentity
import com.parkingpin.app.identity.UnavailableAnonymousIdentity
import com.parkingpin.app.location.CheckpointParkingLocationProvider
import com.parkingpin.app.trace.FileTraceStore
import com.parkingpin.app.trace.NotificationLabelPromptDelivery
import com.parkingpin.app.trace.TraceLabelPrompter
import com.parkingpin.app.trace.TraceRecorder
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.PowerManager
import com.parkingpin.app.domain.parking.usecase.EndParkingUseCase
import com.parkingpin.app.widget.CompositeWidgetProjectionStore
import com.parkingpin.app.widget.GlanceWidgetProjectionStore
import com.parkingpin.app.widget.LockScreenParkingNotice
import com.parkingpin.app.widget.anyParkingWidgetPlaced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
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
     * [ParkingpinApplication] runs this for the life of the process.
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
     * Exposed rather than run from [ParkingpinApplication] because it is the first thing
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
     * Whether the OS will let `DrivingLocationService` be raised from a broadcast
     * (docs/04_ANDROID §4b).
     *
     * Android 12+ refuses a background foreground-service start unless the app qualifies,
     * and battery-optimisation exemption is the route available here. Without it the drive
     * capture is throttled to roughly nothing — measured 2026-09-20 — so this is not a
     * nice-to-have, it is the difference between the product working and not.
     */
    fun isIgnoringBatteryOptimizations(): Boolean =
        appContext.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(appContext.packageName) == true

    /**
     * Keeps the home-screen widgets equal to Room (docs/06 §4, §7).
     *
     * Lazy like the database it reads: a process a detection broadcast started never
     * touches this unless [syncParkingWidgets] finds a widget actually on screen.
     */
    val parkingWidgetSync: ParkingWidgetSync by lazy {
        ParkingWidgetSync(
            repository = parkingRepository,
            // docs/06 §7b: the lock-screen notice is a second rendering of the same
            // projection, not a second source. Composing the stores is what keeps §7's
            // "exactly one place where a session becomes a snapshot" true.
            store = CompositeWidgetProjectionStore(
                listOf(
                    GlanceWidgetProjectionStore(appContext),
                    LockScreenParkingNotice(
                        context = appContext,
                        enabled = { lockScreenNoticeEnabled.get() },
                        clock = clock,
                    ),
                ),
            ),
            stepperEntitled = isWidgetStepperEntitled,
        )
    }

    /**
     * The §7b switch, cached so the projection write stays synchronous.
     *
     * Seeded from DataStore on first use and updated by the settings screen through
     * [setLockScreenNoticeEnabled], because a `WidgetProjectionStore.write` is not the place
     * to block on a preference read.
     */
    private val lockScreenNoticeEnabled = AtomicBoolean(false)

    suspend fun refreshLockScreenNoticePreference() {
        lockScreenNoticeEnabled.set(detectionStateStore.readLockScreenNoticeEnabledOnce())
    }

    suspend fun setLockScreenNoticeEnabled(enabled: Boolean) {
        detectionStateStore.setLockScreenNoticeEnabled(enabled)
        lockScreenNoticeEnabled.set(enabled)
        // The switch has to take effect now, not at the next parking.
        parkingWidgetSync.refresh()
    }

    private val parkingWidgetSyncStarted = AtomicBoolean(false)

    /**
     * Brings every placed widget up to date and, the first time, keeps it that way.
     *
     * Called from [ParkingpinApplication] on process start and from the widget receivers
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
        // The lock-screen notice (docs/06 §7b) rides the same projection, so the guard can
        // no longer be "is a widget on screen": a user with no home-screen widget and the
        // notice switched on still needs the write. The preference is read here, off the
        // write path, and the early return still protects the lazy database for a process
        // that has neither.
        applicationScope.launch {
            refreshLockScreenNoticePreference()
            if (!anyParkingWidgetPlaced(appContext) && !lockScreenNoticeEnabled.get()) return@launch
            parkingWidgetSync.refresh()
            if (parkingWidgetSyncStarted.compareAndSet(false, true)) {
                parkingWidgetSync.keepInSync()
            }
        }
    }



    /**
     * The candidate prompt and everything that answers it
     * (docs/05_PARKING_DETECTION_ENGINE.md §10a).
     *
     * Lazy, and holding the repository as a provider rather than a value, for the same
     * bargain [parkingDatabase] makes. `주차 아님` is answered from a broadcast-started
     * process that never reads parking history, and it must not pay to open Room; only
     * [ParkingCandidateCoordinator.confirm] calls the provider, and confirming is exactly
     * the moment a database is genuinely needed.
     */
    val parkingCandidateCoordinator: ParkingCandidateCoordinator by lazy {
        ParkingCandidateCoordinator(
            store = detectionStateStore,
            repository = { parkingRepository },
            notifier = NotificationCandidateDelivery(appContext),
            clock = clock,
            idGenerator = { UUID.randomUUID().toString() },
            analytics = analyticsRecorder,
        )
    }

    /**
     * Where a manual save gets its coordinates, when there are any.
     *
     * FR-001: this returning null is an ordinary outcome, not a failure — see
     * [CheckpointParkingLocationProvider].
     */
    /**
     * Reads a pillar photo on device (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a).
     *
     * Built lazily and only when `사진으로 입력` is used: ML Kit's bundled recogniser loads
     * a model, and a process a detection broadcast started has no business paying for one.
     */
    val pillarTextReader: PillarTextReader by lazy { MlKitPillarTextReader() }

    /**
     * The pillar photo the camera just wrote, with what the form does with it.
     *
     * A function rather than a value because the file it points at is the *last* capture:
     * the entry is built when the form opens, so it can never be holding a stale
     * [CameraCaptureFile] from a previous screen.
     */
    fun pillarPhotoEntry(): PillarPhotoEntry = PillarPhotoEntry(
        photo = CameraCaptureFile.sourceIn(appContext),
        readSuggestion = ReadPillarSuggestionUseCase(pillarTextReader),
        attachPhoto = AttachParkingPhotoUseCase(parkingRepository, parkingPhotoStore, clock),
    )

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

    /**
     * The §3a state machine and everything it acts on
     * (docs/05_PARKING_DETECTION_ENGINE.md §16).
     *
     * Not lazy: every process a detection broadcast starts exists precisely to feed this,
     * and it costs one object plus a Mutex. The coordinator behind it stays a provider, for
     * the bargain [parkingCandidateCoordinator] documents — most events move the state
     * machine without ever reaching the user, and those must not pay for AppMeasurement. It
     * is the *same* coordinator the notification's `주차 아님` action uses, which is what
     * makes "the engine created it" and "the user answered it" two views of one candidate
     * rather than two candidates.
     */
    val parkingDetectionRuntime: ParkingDetectionRuntime = ParkingDetectionRuntime(
        store = detectionStateStore,
        candidates = { parkingCandidateCoordinator },
        // §11 departure. Providers, not values: a broadcast-started process that only sees
        // a transition must not pay to open Room, and only a confirmed departure calls this.
        endParking = { endedAtMillis -> EndParkingUseCase(parkingRepository, clock)(endedAtMillis) },
        analytics = { analyticsRecorder },
    )

    val transitionEventIngestor: TransitionEventIngestor = TransitionEventIngestor(
        store = detectionStateStore,
        clock = clock,
        locationSessionController = locationSessionController,
        detectionRuntime = parkingDetectionRuntime,
        traceRecorder = traceRecorder,
    )

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
     * [com.parkingpin.app.trace.NotificationLabelPromptDelivery], which is the point —
     * the screen and the poster must not disagree about whether prompting works.
     */
    fun hasNotificationPermission(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    /**
     * §3a's car link needs this to read the connecting device's [android.bluetooth.BluetoothClass]
     * and tell car audio from headphones. Below Android 12 the legacy `BLUETOOTH` permission
     * is install-time, so there is nothing to check.
     */
    fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

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

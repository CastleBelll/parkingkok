package com.parkingkok.app.ui.navigation

import android.content.Context
import android.Manifest
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parkingkok.app.AppContainer
import com.parkingkok.app.R
import com.parkingkok.app.map.ExternalMapOpener
import com.parkingkok.app.ui.motion.LocalMotionEnabled
import com.parkingkok.app.ui.motion.MotionDurations
import com.parkingkok.app.ui.confirm.ConfirmCandidateScreen
import com.parkingkok.app.ui.confirm.ConfirmCandidateViewModel
import com.parkingkok.app.ui.detail.ParkingDetailScreen
import com.parkingkok.app.ui.detail.ParkingDetailViewModel
import com.parkingkok.app.ui.diagnostics.DiagnosticsScreen
import com.parkingkok.app.ui.diagnostics.DiagnosticsViewModel
import com.parkingkok.app.ui.history.HistoryScreen
import com.parkingkok.app.ui.history.HistoryViewModel
import com.parkingkok.app.ui.home.HomeScreen
import com.parkingkok.app.ui.home.HomeViewModel
import com.parkingkok.app.ui.manual.ManualParkingScreen
import com.parkingkok.app.ui.manual.ManualParkingViewModel
import com.parkingkok.app.ui.notifications.NotificationHistoryScreen
import com.parkingkok.app.ui.notifications.NotificationHistoryViewModel
import com.parkingkok.app.ui.photo.rememberCameraCapture
import com.parkingkok.app.ui.settings.SettingsScreen
import com.parkingkok.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * The app shell: one back stack, one screen at a time.
 *
 * The stack is held in `rememberSaveable`, so a configuration change or a process death
 * brings the user back where they were rather than at the root
 * (docs/01_PRODUCT_REQUIREMENTS.md §8).
 */
@Composable
fun ParkingkokApp(
    container: AppContainer,
    /**
     * The candidate a tapped notification is asking about, or null for an ordinary
     * launch. It changes while the app is running — a second notification is tapped —
     * which is why it is a parameter and not a start destination.
     */
    candidateId: String? = null,
    onCandidateOpened: () -> Unit = {},
) {
    var backStack by rememberSaveable(saver = NavBackStackSaver) {
        mutableStateOf(NavBackStack.rootedAtHome())
    }

    // The orphan photo sweep (FR-007). It runs here, once a screen exists, rather than in
    // `ParkingkokApplication`: it is the first thing that would open the database, and a
    // process started by a detection broadcast must not pay for one (see `AppContainer`).
    LaunchedEffect(container) { container.cleanUpOrphanPhotos() }

    // docs/05 §10a expiry. The OS takes the notification down on its own timeout; this is
    // what takes the stored candidate down, so nothing can be created from one that is
    // past its 45 minutes. Every resume, because the process may have been dead for hours.
    LifecycleResumeEffect(container) {
        val job = container.applicationScope.launch { container.parkingCandidateCoordinator.expireIfDue() }
        onPauseOrDispose { job.cancel() }
    }

    // A tapped notification, whether it started the process or arrived while the app was
    // open. §10a: "opens the confirmation screen for that candidateId" — and when the
    // candidate has since gone, the screen itself sends the user home.
    LaunchedEffect(candidateId) {
        if (candidateId != null) {
            backStack = NavBackStack.openingCandidate(candidateId)
            onCandidateOpened()
        }
    }

    BackHandler(enabled = backStack.canGoBack) { backStack = backStack.pop() }

    RouteTransition(backStack) { route ->
        when (route) {
            ParkingkokRoute.Home -> HomeRoute(
                container = container,
                onNavigate = { backStack = backStack.push(it) },
            )

            is ParkingkokRoute.ManualEntry -> ManualEntryRoute(
                container = container,
                candidateId = route.candidateId,
                fromPillarPhoto = route.fromPillarPhoto,
                // A confirmation reached through `직접 입력` leaves two screens behind it —
                // the form and the confirmation screen it came from — and the user is done
                // with both.
                onSaved = {
                    backStack = if (route.candidateId != null) backStack.popToRoot() else backStack.pop()
                },
                onBack = { backStack = backStack.pop() },
            )

            is ParkingkokRoute.Confirm -> ConfirmRoute(
                container = container,
                candidateId = route.candidateId,
                onManualEntry = {
                    backStack = backStack.push(ParkingkokRoute.ManualEntry(route.candidateId))
                },
                // docs/10 §7a: the camera lands in the same form, already filled with
                // what the pillar said. The photo itself is the one file the camera
                // writes, so nothing about it travels in the route.
                onPhotoEntry = {
                    backStack = backStack.push(
                        ParkingkokRoute.ManualEntry(route.candidateId, fromPillarPhoto = true),
                    )
                },
                // Confirmed, rejected or gone: all three end with the user back where they
                // were. §7a: rejecting "returns to where the user was" and never asks why.
                // The one exception is §10a's handled candidate, which opens on the record
                // it became — replacing this screen rather than stacking on it, because
                // back from there should not return to a question already answered.
                onDone = { recordId ->
                    backStack = if (recordId != null) {
                        backStack.replaceTop(ParkingkokRoute.Detail(recordId))
                    } else {
                        backStack.pop()
                    }
                },
            )

            is ParkingkokRoute.Detail -> DetailRoute(
                container = container,
                recordId = route.recordId,
                onBack = { backStack = backStack.pop() },
            )

            ParkingkokRoute.History -> HistoryRoute(
                container = container,
                onOpenDetail = { backStack = backStack.push(ParkingkokRoute.Detail(it)) },
                onBack = { backStack = backStack.pop() },
            )

            ParkingkokRoute.Notifications -> NotificationsRoute(
                container = container,
                onOpenCandidate = { backStack = backStack.push(ParkingkokRoute.Confirm(it)) },
                onOpenRecord = { backStack = backStack.push(ParkingkokRoute.Detail(it)) },
                onBack = { backStack = backStack.pop() },
            )

            ParkingkokRoute.Settings -> SettingsRoute(
                container = container,
                onOpenDiagnostics = { backStack = backStack.push(ParkingkokRoute.Diagnostics) },
                onBack = { backStack = backStack.pop() },
            )

            ParkingkokRoute.Diagnostics -> DiagnosticsRoute(
                container = container,
                onBack = { backStack = backStack.pop() },
            )
        }
    }
}

/**
 * The push and the pop.
 *
 * Material 3's shared axis: the screen being left slides a short way against the travel and
 * fades out while the arriving one slides in — the same direction Android's own back stack
 * moves, not an iOS full-width slide (docs/10_DESIGN_UX_SPEC.md §3). Depth decides the
 * direction, so going back really does run the animation backwards.
 *
 * With motion off it is a cut. So is the first frame: `AnimatedContent` starts settled, so
 * a stack restored after process death opens on the screen the user left without replaying
 * a navigation they did not perform.
 */
@Composable
private fun RouteTransition(
    backStack: NavBackStack,
    content: @Composable (ParkingkokRoute) -> Unit,
) {
    val motionEnabled = LocalMotionEnabled.current
    AnimatedContent(
        targetState = backStack,
        contentKey = { it.current },
        transitionSpec = {
            if (!motionEnabled) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val forward = targetState.depth >= initialState.depth
                val spec = tween<Float>(MotionDurations.ROUTE_MS)
                val travel = tween<IntOffset>(MotionDurations.ROUTE_MS)
                val enter = fadeIn(spec) + slideInHorizontally(travel) { width ->
                    if (forward) width / ROUTE_TRAVEL_DIVISOR else -width / ROUTE_TRAVEL_DIVISOR
                }
                val exit = fadeOut(spec) + slideOutHorizontally(travel) { width ->
                    if (forward) -width / ROUTE_TRAVEL_DIVISOR else width / ROUTE_TRAVEL_DIVISOR
                }
                enter togetherWith exit
            }
        },
        label = "route",
    ) { stack -> content(stack.current) }
}

/** A screen travels an eighth of its width, the way Material's shared axis does. */
private const val ROUTE_TRAVEL_DIVISOR = 8

@Composable
private fun HomeRoute(container: AppContainer, onNavigate: (ParkingkokRoute) -> Unit) {
    PreparePillarReader(container)
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapOpener = rememberMapOpener()

    HomeScreen(
        state = state,
        onDirections = { viewModel.onMapOpened(mapOpener.openDirections(state.active?.location)) },
        onPhotoSelected = viewModel::onPhotoSelected,
        onCameraUnavailable = viewModel::onCameraUnavailable,
        onNoticeShown = viewModel::onNoticeShown,
        onApplyPillarSuggestion = viewModel::onApplyPillarSuggestion,
        onDismissPillarSuggestion = viewModel::onDismissPillarSuggestion,
        onStepFloor = viewModel::onStepFloor,
        onEndParking = viewModel::onEndParking,
        onSaveParking = { onNavigate(ParkingkokRoute.ManualEntry()) },
        onOpenCandidate = { onNavigate(ParkingkokRoute.Confirm(it)) },
        onOpenDetail = { onNavigate(ParkingkokRoute.Detail(it)) },
        onOpenHistory = { onNavigate(ParkingkokRoute.History) },
        onOpenSettings = { onNavigate(ParkingkokRoute.Settings) },
        // docs/10 §7b: the bell opens what the app raised, not the switches that turn it
        // on and off. Those stay in 설정 → 알림.
        onOpenNotifications = { onNavigate(ParkingkokRoute.Notifications) },
    )
}

@Composable
private fun ManualEntryRoute(
    container: AppContainer,
    candidateId: String?,
    fromPillarPhoto: Boolean,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: ManualParkingViewModel =
        viewModel(
            // The pillar arrival is keyed apart from the empty one: reaching the same
            // form twice, once by 직접 입력 and once by 사진으로 입력, must not reuse the
            // ViewModel that already decided there was no photo to read.
            key = "manual-${candidateId ?: "new"}-$fromPillarPhoto",
            factory = ManualParkingViewModel.factory(container, candidateId, fromPillarPhoto),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The save is asynchronous, so the screen leaves when the record id arrives rather
    // than when the button is pressed — otherwise a failure would navigate away silently.
    // `candidateGone` is the other way out: the candidate this form was confirming expired
    // while it was open, so there is nothing left to write (docs/05 §10a).
    LaunchedEffect(state.savedRecordId, state.candidateGone) {
        if (state.savedRecordId != null || state.candidateGone) onSaved()
    }

    ManualParkingScreen(
        state = state,
        onFloorChange = viewModel::onFloorChange,
        onZoneChange = viewModel::onZoneChange,
        onSpotChange = viewModel::onSpotChange,
        onMemoChange = viewModel::onMemoChange,
        onSave = viewModel::onSave,
        onBack = onBack,
    )
}

@Composable
private fun DetailRoute(container: AppContainer, recordId: String, onBack: () -> Unit) {
    PreparePillarReader(container)
    val viewModel: ParkingDetailViewModel =
        viewModel(
            key = "detail-$recordId",
            factory = ParkingDetailViewModel.factory(container, recordId),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapOpener = rememberMapOpener()

    // Deleting from here removes the thing the screen is about, so it closes itself.
    LaunchedEffect(state.missing) {
        if (state.missing) onBack()
    }

    ParkingDetailScreen(
        state = state,
        onEndParking = viewModel::onEndParking,
        onDelete = viewModel::onDelete,
        onDirections = {
            viewModel.onMapOpened(mapOpener.openDirections(state.record?.location))
        },
        onPhotoSelected = viewModel::onPhotoSelected,
        onRemovePhoto = viewModel::onRemovePhoto,
        onCameraUnavailable = viewModel::onCameraUnavailable,
        onNoticeShown = viewModel::onNoticeShown,
        onApplyPillarSuggestion = viewModel::onApplyPillarSuggestion,
        onDismissPillarSuggestion = viewModel::onDismissPillarSuggestion,
        onBack = onBack,
    )
}


/**
 * Load the recogniser's model while the user is reading the screen, not while they wait
 * for a form (docs/02 §6a).
 *
 * iOS measured its recogniser at 5224ms on the first call in a process against ~1010ms
 * warm, and a deadline sized for the warm number lost every first read — silently, since
 * §6a makes failure quiet. ML Kit's bundled model has the same cost in kind. Warming here
 * means the read the user actually triggers is the warm one.
 */
@Composable
private fun PreparePillarReader(container: AppContainer) {
    LaunchedEffect(Unit) { container.pillarTextReader.prepare() }
}

/** docs/10_DESIGN_UX_SPEC.md §7a. */
@Composable
private fun ConfirmRoute(
    container: AppContainer,
    candidateId: String,
    onManualEntry: () -> Unit,
    onPhotoEntry: () -> Unit,
    onDone: (String?) -> Unit,
) {
    val viewModel: ConfirmCandidateViewModel =
        viewModel(
            key = "confirm-$candidateId",
            factory = ConfirmCandidateViewModel.factory(container, candidateId),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PreparePillarReader(container)

    // The screen closes itself on every terminal outcome, so the shell holds no rule about
    // what a candidate is. `gone` covers §10a's expired notification: the user lands on
    // home, and nothing apologises.
    LaunchedEffect(state.gone, state.rejected) {
        if (state.gone || state.rejected) onDone(state.openRecordId)
    }

    val takePillarPhoto = rememberCameraCapture(
        onCaptured = onPhotoEntry,
        // No camera app, or no file to write to. §6a makes a failed read silent, and a
        // camera that never opened is the same thing one step earlier: the user is left
        // on the screen with 직접 입력 beside the button they pressed.
        onCameraUnavailable = {},
    )

    ConfirmCandidateScreen(
        state = state,
        onManualEntry = onManualEntry,
        onPhotoEntry = takePillarPhoto,
        onReject = viewModel::onReject,
        onBack = { onDone(null) },
    )
}

/**
 * The `길찾기` action's launcher.
 *
 * It is built here, in the shell, because `startActivity` belongs to the Activity and a
 * ViewModel holding one would outlive it. What it does with the coordinate — and what it
 * does when no app answers — is [ExternalMapOpener]'s, and is unit tested there.
 */
@Composable
private fun rememberMapOpener(): ExternalMapOpener {
    val context = LocalContext.current
    val pinLabel = stringResource(R.string.map_pin_label)
    return remember(context, pinLabel) {
        ExternalMapOpener(
            starter = { uri -> context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri())) },
            pinLabel = pinLabel,
        )
    }
}

/** docs/10_DESIGN_UX_SPEC.md §7b. */
@Composable
private fun NotificationsRoute(
    container: AppContainer,
    onOpenCandidate: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: NotificationHistoryViewModel =
        viewModel(factory = NotificationHistoryViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NotificationHistoryScreen(
        state = state,
        onOpenCandidate = onOpenCandidate,
        onOpenRecord = onOpenRecord,
        onBack = onBack,
    )
}

@Composable
private fun HistoryRoute(
    container: AppContainer,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HistoryScreen(
        state = state,
        onOpenDetail = onOpenDetail,
        onDeleteAll = viewModel::onDeleteAll,
        onBack = onBack,
    )
}

/**
 * What `자동 주차 감지` actually needs, asked for together (docs/04_ANDROID §3).
 *
 * `BLUETOOTH_CONNECT` is in the list because §3a's car link cannot work without it and
 * nothing was requesting it — the manifest comment claimed it was "requested only alongside
 * Smart Detection" and that request did not exist. Denial is not a failure: the link is an
 * optional vehicle signal and detection stands on motion and location alone.
 *
 * `ACCESS_BACKGROUND_LOCATION` is deliberately not here. Android 11+ will not show a dialog
 * for it in the same request as the foreground one, so it stays the row that opens system
 * Settings.
 */
private fun detectionPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
}.toTypedArray()

@Composable
private fun SettingsRoute(
    container: AppContainer,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A permission can be revoked in system Settings while this screen is backgrounded.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // docs/04_ANDROID §3. Until 2026-09-21 nothing in the shipped UI requested a runtime
    // permission — the only launchers in the app were on the developer diagnostics screen,
    // and turning on 자동 주차 감지 wrote a preference and asked for nothing. A user who
    // installed the app could not grant what detection needs without going to system
    // Settings by hand, and every device test so far had been granted over adb, which is
    // what hid it.
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    // Turning detection on asks for what detection needs, in one dialog. Background
    // location is deliberately absent: Android 11+ refuses to prompt for it alongside the
    // foreground one, so it stays a row that opens system Settings.
    val enableDetection = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    SettingsScreen(
        state = state,
        onRequestPermission = { permission -> requestPermission.launch(permission) },
        onDetectionEnabledChange = { enabled ->
            if (enabled) enableDetection.launch(detectionPermissions())
            viewModel.onDetectionEnabledChange(enabled)
        },
        onLockScreenNoticeChange = viewModel::onLockScreenNoticeChange,
        onAnalyticsConsentChange = viewModel::onAnalyticsConsentChange,
        onOpenBatterySettings = {
            // The system list rather than ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:
            // the direct prompt is one tap fewer and is Play-policy sensitive
            // (docs/13_PLAY_STORE_REVIEW_CHECKLIST.md), and this build has not been
            // through review. Falls back to the app's own settings page on a device with
            // no such screen.
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(list) }.onFailure { context.startActivity(fallback) }
        },
        onOpenSystemSettings = {
            // Background location in particular cannot be granted from an in-app prompt on
            // modern Android (docs/10_DESIGN_UX_SPEC.md §8), so every permission row leads
            // to the one place all of them can actually be changed.
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onDeleteHistory = viewModel::onDeleteHistory,
        onOpenDiagnostics = onOpenDiagnostics,
        onBack = onBack,
    )
}

/** The P0 diagnostics screen, unchanged — only its entry point moved into Settings. */
@Composable
private fun DiagnosticsRoute(container: AppContainer, onBack: () -> Unit) {
    val viewModel: DiagnosticsViewModel =
        viewModel(factory = DiagnosticsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    DiagnosticsScreen(
        state = state,
        onDetectionEnabledChange = viewModel::setDetectionEnabled,
        onPermissionResult = viewModel::refresh,
        onCaptureModeChange = viewModel::setCaptureMode,
        onExportDiagnostics = viewModel::exportDiagnostics,
        onClearEvents = viewModel::clearEventLog,
        onTraceLabelChange = viewModel::setTraceLabel,
        onTraceSplit = viewModel::splitTraceSession,
    )
}

/** Persists the stack as the list of strings [ParkingkokRouteCodec] produces. */
private val NavBackStackSaver = listSaver<MutableState<NavBackStack>, String>(
    save = { it.value.encode() },
    restore = { mutableStateOf(NavBackStack.decode(it)) },
)

package com.parkingkok.app.ui.navigation

import android.content.Context
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
import com.parkingkok.app.ui.settings.SettingsScreen
import com.parkingkok.app.ui.settings.SettingsViewModel

/**
 * The app shell: one back stack, one screen at a time.
 *
 * The stack is held in `rememberSaveable`, so a configuration change or a process death
 * brings the user back where they were rather than at the root
 * (docs/01_PRODUCT_REQUIREMENTS.md §8).
 */
@Composable
fun ParkingkokApp(container: AppContainer) {
    var backStack by rememberSaveable(saver = NavBackStackSaver) {
        mutableStateOf(NavBackStack.rootedAtHome())
    }

    // The orphan photo sweep (FR-007). It runs here, once a screen exists, rather than in
    // `ParkingkokApplication`: it is the first thing that would open the database, and a
    // process started by a detection broadcast must not pay for one (see `AppContainer`).
    LaunchedEffect(container) { container.cleanUpOrphanPhotos() }

    BackHandler(enabled = backStack.canGoBack) { backStack = backStack.pop() }

    RouteTransition(backStack) { route ->
        when (route) {
            ParkingkokRoute.Home -> HomeRoute(
                container = container,
                onNavigate = { backStack = backStack.push(it) },
            )

            ParkingkokRoute.ManualEntry -> ManualEntryRoute(
                container = container,
                onSaved = { backStack = backStack.pop() },
                onBack = { backStack = backStack.pop() },
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
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapOpener = rememberMapOpener()
    val context = LocalContext.current

    HomeScreen(
        state = state,
        onDirections = { viewModel.onMapOpened(mapOpener.openDirections(state.active?.location)) },
        onPhotoSelected = viewModel::onPhotoSelected,
        onCameraUnavailable = viewModel::onCameraUnavailable,
        onNoticeShown = viewModel::onNoticeShown,
        onStepFloor = viewModel::onStepFloor,
        onEndParking = viewModel::onEndParking,
        onSaveParking = { onNavigate(ParkingkokRoute.ManualEntry) },
        onOpenDetail = { onNavigate(ParkingkokRoute.Detail(it)) },
        onOpenHistory = { onNavigate(ParkingkokRoute.History) },
        onOpenSettings = { onNavigate(ParkingkokRoute.Settings) },
        // The bell in the mockup's header. 주차콕 has no notification centre of its own, so
        // it leads to the place its detection notifications are actually switched on and
        // off — a real destination rather than a decorative icon.
        onOpenNotificationSettings = { context.startActivity(notificationSettingsIntent(context)) },
    )
}

/**
 * Where this app's notification channels are configured.
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` is guaranteed from API 26 and this app is minSdk 29,
 * so there is no fallback to write: the screen is always there.
 */
private fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun ManualEntryRoute(
    container: AppContainer,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: ManualParkingViewModel =
        viewModel(factory = ManualParkingViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The save is asynchronous, so the screen leaves when the record id arrives rather
    // than when the button is pressed — otherwise a failure would navigate away silently.
    val savedRecordId = state.savedRecordId
    LaunchedEffect(savedRecordId) {
        if (savedRecordId != null) onSaved()
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
        onBack = onBack,
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

    SettingsScreen(
        state = state,
        onDetectionEnabledChange = viewModel::onDetectionEnabledChange,
        onAnalyticsConsentChange = viewModel::onAnalyticsConsentChange,
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

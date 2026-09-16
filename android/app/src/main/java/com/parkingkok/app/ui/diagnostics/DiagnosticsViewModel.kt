package com.parkingkok.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.detection.RegistrationStatus
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.location.LocationSessionMode
import com.parkingkok.app.domain.location.LocationSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Permission state the P0 screen renders and requests against. */
data class DiagnosticsPermissions(
    val activityRecognitionGranted: Boolean = false,
    val foregroundLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
)

/** Everything the P0 diagnostics screen renders. Contains no coordinates. */
data class DiagnosticsUiState(
    val permissions: DiagnosticsPermissions = DiagnosticsPermissions(),
    val detectionEnabled: Boolean = false,
    val registrationStatus: RegistrationStatus = RegistrationStatus.Unknown,
    val checkpoint: DetectionCheckpoint? = null,
    val events: List<MotionDomainEvent> = emptyList(),
    val sessionState: LocationSessionState = LocationSessionState(),
    /** Result of the last manual export: null before one has run, a reason string on failure. */
    val lastExportFailure: String? = null,
    val lastExportSucceeded: Boolean = false,
)

/**
 * Drives the P0 instrumentation screen.
 *
 * Owns the UI scope (docs/16_CODING_STANDARDS.md §2) and holds no detection logic of its
 * own — it forwards intents to the registration coordinator and the location session
 * controller, and observes the store.
 */
class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private val permissions = MutableStateFlow(readPermissions())
    private val exportResult = MutableStateFlow<ExportResult?>(null)

    /** null before an export has been asked for; [failure] null means the last one wrote. */
    private data class ExportResult(val failure: String?)

    /** The four store flows, folded first so the outer combine stays a typed 4-arity one. */
    private data class StoredState(
        val detectionEnabled: Boolean,
        val checkpoint: DetectionCheckpoint?,
        val events: List<MotionDomainEvent>,
        val sessionState: LocationSessionState,
    )

    private val storedState = combine(
        container.detectionStateStore.desiredEnabled,
        container.detectionStateStore.checkpoint,
        container.detectionStateStore.recentEvents,
        container.detectionStateStore.locationSessionState,
        ::StoredState,
    )

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        permissions,
        container.registrationCoordinator.status,
        storedState,
        exportResult,
    ) { grantedPermissions, registration, stored, export ->
        DiagnosticsUiState(
            permissions = grantedPermissions,
            detectionEnabled = stored.detectionEnabled,
            registrationStatus = registration,
            checkpoint = stored.checkpoint,
            events = stored.events.asReversed(),
            sessionState = stored.sessionState,
            lastExportFailure = export?.failure,
            lastExportSucceeded = export != null && export.failure == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DiagnosticsUiState())

    /**
     * Re-reads permission state and reconciles. The screen calls this on every resume and
     * after a permission result, so no reconcile is needed at construction time.
     */
    fun refresh() {
        permissions.value = readPermissions()
        viewModelScope.launch {
            container.registrationCoordinator.reconcile()
            container.locationSessionController.reconcile()
            container.diagnosticsExporter.export()
        }
    }

    fun setDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.registrationCoordinator.setDetectionEnabled(enabled)
            // Turning detection off must also tear down any bounded capture; leaving one
            // running past the opt-out is exactly the leak the battery gate rejects.
            if (!enabled) container.locationSessionController.setDesiredMode(LocationSessionMode.IDLE)
            container.diagnosticsExporter.export()
        }
    }

    /** Manual bounded capture, so the no-foreground-service path can be exercised without driving. */
    fun setCaptureMode(mode: LocationSessionMode) {
        viewModelScope.launch {
            container.locationSessionController.setDesiredMode(mode)
            container.diagnosticsExporter.export()
        }
    }

    fun exportDiagnostics() {
        viewModelScope.launch {
            exportResult.value = ExportResult(container.diagnosticsExporter.export())
        }
    }

    fun clearEventLog() {
        viewModelScope.launch { container.detectionStateStore.clearEventLog() }
    }

    private fun readPermissions(): DiagnosticsPermissions = DiagnosticsPermissions(
        activityRecognitionGranted = container.hasActivityRecognitionPermission(),
        foregroundLocationGranted = container.locationSessionController.hasForegroundLocationPermission(),
        backgroundLocationGranted = container.locationSessionController.hasBackgroundLocationPermission(),
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiagnosticsViewModel(container) as T
            }
    }
}

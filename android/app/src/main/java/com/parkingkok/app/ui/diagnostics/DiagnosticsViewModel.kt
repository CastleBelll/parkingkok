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
import com.parkingkok.app.domain.trace.TraceLabel
import com.parkingkok.app.domain.trace.TraceSession
import com.parkingkok.app.domain.trace.TraceSplitResult
import com.parkingkok.app.domain.trace.TraceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Permission state the P0 screen renders and requests against. */
data class DiagnosticsPermissions(
    val activityRecognitionGranted: Boolean = false,
    val foregroundLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    /** Whether a label prompt would be shown (docs/05 §9 labelling). */
    val notificationsGranted: Boolean = false,
)

/** Everything the P0 diagnostics screen renders. Contains no coordinates. */
data class DiagnosticsUiState(
    val permissions: DiagnosticsPermissions = DiagnosticsPermissions(),
    val detectionEnabled: Boolean = false,
    val registrationStatus: RegistrationStatus = RegistrationStatus.Unknown,
    val checkpoint: DetectionCheckpoint? = null,
    val events: List<MotionDomainEvent> = emptyList(),
    val sessionState: LocationSessionState = LocationSessionState(),
    /** Recorded trace sessions, newest first (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9). */
    val traceSessions: List<TraceSession> = emptyList(),
    /** The §9 counters and gap aggregate, the same ones the exported report carries. */
    val traceSummary: TraceSummary = TraceSummary(),
    /** §9 allows cutting a closed session only, so the screen has to know which is open. */
    val openTraceSessionId: String? = null,
    /** Why the last split was refused, or null. A refusal is a normal outcome, not an error. */
    val traceSplitRefusal: TraceSplitResult.Refusal? = null,
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

    /**
     * Re-read rather than observed: traces are files, not a Flow, and they only change when
     * this screen is open or an event arrives. Polling them would be exactly the standing
     * cost §9 rules out.
     *
     * Folded into one value so the outer `combine` stays a typed 5-arity one, and so the
     * list, the aggregate and the open-session pointer can never be rendered from three
     * different moments in time.
     */
    private data class TraceState(
        val sessions: List<TraceSession> = emptyList(),
        val summary: TraceSummary = TraceSummary(),
        val openSessionId: String? = null,
        val splitRefusal: TraceSplitResult.Refusal? = null,
    )

    private val traceState = MutableStateFlow(TraceState())

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
        traceState,
    ) { grantedPermissions, registration, stored, export, traces ->
        DiagnosticsUiState(
            permissions = grantedPermissions,
            detectionEnabled = stored.detectionEnabled,
            registrationStatus = registration,
            checkpoint = stored.checkpoint,
            events = stored.events.asReversed(),
            sessionState = stored.sessionState,
            traceSessions = traces.sessions,
            traceSummary = traces.summary,
            openTraceSessionId = traces.openSessionId,
            traceSplitRefusal = traces.splitRefusal,
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
            reloadTraces()
        }
    }

    fun setDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.registrationCoordinator.setDetectionEnabled(enabled)
            // Turning detection off must also tear down any bounded capture; leaving one
            // running past the opt-out is exactly the leak the battery gate rejects.
            if (!enabled) {
                container.locationSessionController.setDesiredMode(LocationSessionMode.IDLE)
                // The user-facing boundary: whatever was being recorded is finished, so
                // the next trip starts its own file instead of appending to this one.
                container.traceRecorder.closeOpenSession()
            }
            container.diagnosticsExporter.export()
            reloadTraces()
        }
    }

    /** Applies the human label §9 leaves to a person, then re-reads what landed. */
    fun setTraceLabel(sessionId: String, label: TraceLabel) {
        viewModelScope.launch {
            container.traceRecorder.setLabel(sessionId, label)
            reloadTraces()
        }
    }

    /**
     * Cuts a closed session in two at the event the user picked (§9 "사람이 세션을 나눈다").
     *
     * The refusal is carried back into the state rather than swallowed: §9 forbids a cut
     * leaving a one-event fragment, and the person choosing the point has no way to know
     * that until they choose it.
     */
    fun splitTraceSession(sessionId: String, atEventIndex: Int) {
        viewModelScope.launch {
            val refusal = container.traceRecorder.splitSession(sessionId, atEventIndex)
            reloadTraces(splitRefusal = refusal)
        }
    }

    /**
     * One read of the traces directory per refresh. [TraceRecorder.summary] walks the same
     * files, which is why both are done here, off the main thread, and never on an append.
     */
    private suspend fun reloadTraces(splitRefusal: TraceSplitResult.Refusal? = null) {
        val recorder = container.traceRecorder
        traceState.value = withContext(Dispatchers.IO) {
            TraceState(
                sessions = recorder.sessions(),
                summary = recorder.summary(),
                openSessionId = recorder.openSessionId(),
                splitRefusal = splitRefusal,
            )
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
            // Also the manual refresh for the trace list. Traces are files, so a session
            // recorded while this screen stayed in the foreground is otherwise invisible
            // until the next resume — and the export button is already the "show me the
            // current state" control.
            reloadTraces()
        }
    }

    fun clearEventLog() {
        viewModelScope.launch { container.detectionStateStore.clearEventLog() }
    }

    private fun readPermissions(): DiagnosticsPermissions = DiagnosticsPermissions(
        activityRecognitionGranted = container.hasActivityRecognitionPermission(),
        foregroundLocationGranted = container.locationSessionController.hasForegroundLocationPermission(),
        backgroundLocationGranted = container.locationSessionController.hasBackgroundLocationPermission(),
        notificationsGranted = container.hasNotificationPermission(),
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

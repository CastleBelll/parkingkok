package com.parkingkok.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.detection.RegistrationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the P0 diagnostics screen renders. Contains no coordinates. */
data class DiagnosticsUiState(
    val permissionGranted: Boolean = false,
    val detectionEnabled: Boolean = false,
    val registrationStatus: RegistrationStatus = RegistrationStatus.Unknown,
    val checkpoint: DetectionCheckpoint? = null,
    val events: List<MotionDomainEvent> = emptyList(),
)

/**
 * Drives the P0 instrumentation screen.
 *
 * Owns the UI scope (docs/16_CODING_STANDARDS.md §2) and holds no detection logic of its
 * own — it forwards intents to the registration coordinator and observes the store.
 */
class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private val permissionGranted = MutableStateFlow(container.hasActivityRecognitionPermission())
    private val registrationStatus = MutableStateFlow<RegistrationStatus>(RegistrationStatus.Unknown)

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        permissionGranted,
        registrationStatus,
        container.detectionStateStore.desiredEnabled,
        container.detectionStateStore.checkpoint,
        container.detectionStateStore.recentEvents,
    ) { granted, status, enabled, checkpoint, events ->
        DiagnosticsUiState(
            permissionGranted = granted,
            detectionEnabled = enabled,
            registrationStatus = status,
            checkpoint = checkpoint,
            events = events.asReversed(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DiagnosticsUiState())

    /**
     * Re-reads permission state and reconciles. The screen calls this on every resume and
     * after a permission result, so no reconcile is needed at construction time.
     */
    fun refresh() {
        permissionGranted.value = container.hasActivityRecognitionPermission()
        viewModelScope.launch {
            registrationStatus.value = container.registrationCoordinator.reconcile()
        }
    }

    fun setDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            registrationStatus.value = container.registrationCoordinator.setDetectionEnabled(enabled)
        }
    }

    fun clearEventLog() {
        viewModelScope.launch { container.detectionStateStore.clearEventLog() }
    }

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

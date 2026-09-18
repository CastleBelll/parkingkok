package com.parkingkok.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.domain.parking.usecase.DeleteParkingHistoryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What `05-settings.png` renders.
 *
 * Every flag here is read from the system or the store, never assumed. The screen's job is
 * to tell the truth about permissions the user may have changed in Settings since the app
 * last looked.
 */
data class SettingsUiState(
    val detectionEnabled: Boolean = false,
    val activityRecognitionGranted: Boolean = false,
    val foregroundLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
)

/** Drives the settings screen. */
class SettingsViewModel(
    private val container: AppContainer,
    private val deleteHistory: DeleteParkingHistoryUseCase,
) : ViewModel() {

    private val permissions = MutableStateFlow(readPermissions())

    val uiState: StateFlow<SettingsUiState> =
        combine(
            container.detectionStateStore.desiredEnabled,
            permissions,
        ) { detectionEnabled, granted ->
            granted.copy(detectionEnabled = detectionEnabled)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(),
        )

    /**
     * Re-reads the permission grants.
     *
     * Called on every resume: a permission can be revoked in system Settings while the app
     * is backgrounded, and a screen that still says `허용됨` afterwards is lying to the user.
     */
    fun refresh() {
        permissions.value = readPermissions()
    }

    fun onDetectionEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            container.registrationCoordinator.setDetectionEnabled(enabled)
            refresh()
        }
    }

    fun onDeleteHistory() {
        viewModelScope.launch { deleteHistory() }
    }

    private fun readPermissions() = SettingsUiState(
        activityRecognitionGranted = container.hasActivityRecognitionPermission(),
        foregroundLocationGranted =
            container.locationSessionController.hasForegroundLocationPermission(),
        backgroundLocationGranted =
            container.locationSessionController.hasBackgroundLocationPermission(),
        notificationsEnabled = container.hasNotificationPermission(),
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    container = container,
                    deleteHistory = DeleteParkingHistoryUseCase(container.parkingRepository),
                ) as T
            }
    }
}

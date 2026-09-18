package com.parkingkok.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.analytics.AnalyticsConsentStore
import com.parkingkok.app.analytics.AnalyticsEvent
import com.parkingkok.app.analytics.AnalyticsRecording
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
    /**
     * docs/07 "동의". Read from the store rather than assumed, so the row cannot claim a
     * consent that was never persisted — and `false` until it is.
     */
    val analyticsConsentGranted: Boolean = false,
)

/** Drives the settings screen. */
class SettingsViewModel(
    private val container: AppContainer,
    private val deleteHistory: DeleteParkingHistoryUseCase,
    private val analyticsConsentStore: AnalyticsConsentStore,
    private val analytics: AnalyticsRecording,
) : ViewModel() {

    private val permissions = MutableStateFlow(readPermissions())

    val uiState: StateFlow<SettingsUiState> =
        combine(
            container.detectionStateStore.desiredEnabled,
            analyticsConsentStore.granted,
            permissions,
        ) { detectionEnabled, analyticsConsent, granted ->
            granted.copy(
                detectionEnabled = detectionEnabled,
                analyticsConsentGranted = analyticsConsent,
            )
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
            // docs/17 §2 `smart_detection_enabled` — the detection opt-in, which is a
            // product signal. The analytics opt-in below is not reported at all.
            analytics.record(AnalyticsEvent.SmartDetectionEnabled(enabled))
            refresh()
        }
    }

    /**
     * docs/07 "동의": persisted immediately, and a revocation takes effect on the next event
     * because `AnalyticsRecorder` re-reads the flag every time.
     *
     * Nothing is reported here, in either direction. An event on the grant would be decided
     * by the state before consent existed, and one on the revocation would be a
     * transmission after it was withdrawn.
     */
    fun onAnalyticsConsentChange(granted: Boolean) {
        viewModelScope.launch { analyticsConsentStore.setGranted(granted) }
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
                    analyticsConsentStore = container.analyticsConsentStore,
                    analytics = container.analyticsRecorder,
                ) as T
            }
    }
}

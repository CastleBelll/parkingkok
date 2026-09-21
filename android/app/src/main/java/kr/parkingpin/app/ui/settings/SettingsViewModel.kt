package kr.parkingpin.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kr.parkingpin.app.AppContainer
import kr.parkingpin.app.analytics.AnalyticsConsentStore
import kr.parkingpin.app.analytics.AnalyticsEvent
import kr.parkingpin.app.analytics.AnalyticsRecording
import kr.parkingpin.app.domain.parking.usecase.DeleteParkingHistoryUseCase
import kr.parkingpin.app.R
import kr.parkingpin.app.identity.AccountLinkResult
import kr.parkingpin.app.identity.AccountProvider
import kr.parkingpin.app.identity.AccountState
import kr.parkingpin.app.identity.CredentialResult
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
    /** docs/07 §13a: a provider is attached to the uid this device already had. */
    val signedIn: Boolean = false,
    val accountBusy: Boolean = false,
    /** A string resource, or null. Cleared on the next attempt. */
    val accountMessage: Int? = null,
    /** docs/06 §7b: the ongoing shade readout. Off by default — the widget is the answer. */
    val lockScreenNoticeEnabled: Boolean = false,
    val activityRecognitionGranted: Boolean = false,
    val foregroundLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    /** §3a's car link. Optional — denial costs accuracy, not detection. */
    val bluetoothConnectGranted: Boolean = false,
    /** docs/04_ANDROID §4b: without it the drive capture cannot be raised in the background. */
    val batteryUnrestricted: Boolean = false,
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

    /** Held apart from [permissions] because a sign-in is not a grant and does not re-read one. */
    private data class AccountUi(val signedIn: Boolean, val busy: Boolean, val message: Int?)

    private val account = MutableStateFlow(
        AccountUi(
            signedIn = container.accountIdentity.state() is AccountState.Linked,
            busy = false,
            message = null,
        ),
    )

    val uiState: StateFlow<SettingsUiState> =
        combine(
            container.detectionStateStore.desiredEnabled,
            container.detectionStateStore.lockScreenNoticeEnabled,
            analyticsConsentStore.granted,
            permissions,
            account,
        ) { detectionEnabled, lockScreenNotice, analyticsConsent, granted, accountUi ->
            granted.copy(
                detectionEnabled = detectionEnabled,
                lockScreenNoticeEnabled = lockScreenNotice,
                analyticsConsentGranted = analyticsConsent,
                signedIn = accountUi.signedIn,
                accountBusy = accountUi.busy,
                accountMessage = accountUi.message,
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

    /**
     * docs/07 §13a. The token comes from the UI layer because Credential Manager needs an
     * `Activity` context; everything the token *means* is decided below, in
     * [LinkingAccountIdentity], which links it to the uid this device already has.
     */
    fun onSignIn(requestToken: suspend () -> CredentialResult) {
        if (uiState.value.accountBusy) return
        account.value = account.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val message = when (val credential = requestToken()) {
                is CredentialResult.Token -> messageFor(
                    container.accountIdentity.signIn(AccountProvider.GOOGLE, credential.value),
                )
                // The user closed the sheet. Saying anything would be scolding them for it.
                CredentialResult.Cancelled -> null
                is CredentialResult.Failed -> R.string.settings_account_failed
            }
            account.value = AccountUi(
                signedIn = container.accountIdentity.state() is AccountState.Linked,
                busy = false,
                message = message,
            )
        }
    }

    fun onSignOut() {
        if (uiState.value.accountBusy) return
        account.value = account.value.copy(busy = true, message = null)
        viewModelScope.launch {
            container.accountIdentity.signOut()
            account.value = AccountUi(signedIn = false, busy = false, message = null)
        }
    }

    /**
     * docs/07 §13b: a credential already attached to another Firebase user is refused, and
     * the copy says the records on this phone are untouched — which is true, because they
     * were never in the account.
     */
    private fun messageFor(result: AccountLinkResult): Int? = when (result) {
        is AccountLinkResult.Linked -> null
        AccountLinkResult.AlreadyLinkedElsewhere -> R.string.settings_account_conflict
        is AccountLinkResult.Failed -> R.string.settings_account_failed
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
     * docs/06 §7b. Takes effect immediately in both directions — the container re-writes
     * the projection, which posts or cancels the notification on the spot.
     */
    fun onLockScreenNoticeChange(enabled: Boolean) {
        viewModelScope.launch { container.setLockScreenNoticeEnabled(enabled) }
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
        bluetoothConnectGranted = container.hasBluetoothConnectPermission(),
        batteryUnrestricted = container.isIgnoringBatteryOptimizations(),
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    container = container,
                    deleteHistory = DeleteParkingHistoryUseCase(
                        container.parkingRepository,
                        container.cleanUpOrphanPhotos,
                    ),
                    analyticsConsentStore = container.analyticsConsentStore,
                    analytics = container.analyticsRecorder,
                ) as T
            }
    }
}

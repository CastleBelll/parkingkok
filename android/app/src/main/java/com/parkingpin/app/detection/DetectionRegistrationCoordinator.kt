package com.parkingpin.app.detection

import android.util.Log
import com.parkingpin.app.core.Clock
import com.parkingpin.app.data.DetectionStateStore
import com.parkingpin.app.domain.registration.ReconcileAction
import com.parkingpin.app.domain.registration.RegistrationReconciler
import com.parkingpin.app.domain.registration.TransitionRegistrationSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the diagnostics screen shows about the transition registration. */
sealed interface RegistrationStatus {
    data object Unknown : RegistrationStatus
    data object Disabled : RegistrationStatus
    data class Active(val specVersion: Int, val registeredAtMillis: Long) : RegistrationStatus
    data object MissingPermission : RegistrationStatus
    data class Failed(val reason: String) : RegistrationStatus
}

/**
 * Brings the actual Play services registration in line with the desired one.
 *
 * Called on app start, on BOOT_COMPLETED, on MY_PACKAGE_REPLACED, and whenever the user
 * toggles detection or grants the permission (docs/04_ANDROID_IMPLEMENTATION.md §6).
 *
 * The [Mutex] serializes concurrent reconciles — app start and a boot broadcast can land
 * in the same process at the same time, and without it both could see "not registered"
 * and each issue a register call.
 */
class DetectionRegistrationCoordinator(
    private val store: DetectionStateStore,
    private val registrar: TransitionRegistrar,
    private val clock: Clock,
) {

    private val mutex = Mutex()

    private val mutableStatus = MutableStateFlow<RegistrationStatus>(RegistrationStatus.Unknown)

    /** Last reconciliation result. Read by the diagnostics export and the P0 screen. */
    val status: StateFlow<RegistrationStatus> = mutableStatus.asStateFlow()

    /**
     * Forgets the recorded registration, then reconciles.
     *
     * Use after reboot or app update: the system-side subscription is gone even though
     * our record survives, so the record must not be trusted as proof of registration.
     */
    suspend fun reconcileAfterSystemReset(): RegistrationStatus = mutex.withLock {
        store.clearRegistrationRecord()
        reconcileLocked()
    }

    suspend fun reconcile(): RegistrationStatus = mutex.withLock { reconcileLocked() }

    private suspend fun reconcileLocked(): RegistrationStatus {
        val desiredEnabled = store.readDesiredEnabledOnce()
        val record = store.readRegistrationRecordOnce()
        val action = RegistrationReconciler.decide(
            desiredEnabled = desiredEnabled,
            recordedSpecVersion = record?.specVersion,
            currentSpecVersion = TransitionRegistrationSpec.VERSION,
            permissionGranted = registrar.hasPermission(),
        )
        Log.i(TAG, "reconcile desired=$desiredEnabled recorded=${record?.specVersion} -> $action")

        return when (action) {
            ReconcileAction.NONE ->
                if (record == null) {
                    RegistrationStatus.Disabled
                } else {
                    RegistrationStatus.Active(record.specVersion, record.registeredAtMillis)
                }

            ReconcileAction.BLOCKED_MISSING_PERMISSION -> {
                // Without the permission the subscription cannot be trusted to exist.
                store.clearRegistrationRecord()
                RegistrationStatus.MissingPermission
            }

            ReconcileAction.UNREGISTER -> {
                val failure = registrar.unregister()
                store.clearRegistrationRecord()
                if (failure == null) RegistrationStatus.Disabled else RegistrationStatus.Failed(failure)
            }

            ReconcileAction.REGISTER -> {
                val failure = registrar.register()
                if (failure != null) {
                    RegistrationStatus.Failed(failure)
                } else {
                    val registeredAt = clock.nowEpochMillis()
                    store.recordRegistered(TransitionRegistrationSpec.VERSION, registeredAt)
                    RegistrationStatus.Active(TransitionRegistrationSpec.VERSION, registeredAt)
                }
            }
        }.also { mutableStatus.value = it }
    }

    suspend fun setDetectionEnabled(enabled: Boolean): RegistrationStatus = mutex.withLock {
        store.setDesiredEnabled(enabled)
        reconcileLocked()
    }

    private companion object {
        const val TAG = "ParkingkokRegistration"
    }
}

package com.sjstudioz.parkingpin.detection

import android.util.Log
import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.domain.registration.ReconcileAction
import com.sjstudioz.parkingpin.domain.registration.RegistrationReconciler
import com.sjstudioz.parkingpin.domain.registration.TransitionRegistrationSpec
import kotlinx.coroutines.CancellationException
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
    /**
     * Ends everything detection had running when the user switches it off — the location
     * capture, a drive, a departure, a stop-only window (docs/05 §11 / §19), which the
     * transition registration alone does not reach. [ParkingDetectionRuntime.handleSmartDetectionDisabled]
     * in the app; null in the tests that pin the registration alone.
     */
    private val optOut: (suspend (atMillis: Long) -> Unit)? = null,
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
    suspend fun reconcileAfterSystemReset(): RegistrationStatus {
        val status = mutex.withLock {
            store.clearRegistrationRecord()
            reconcileLocked()
        }
        endWhatAnInterruptedOptOutLeft()
        return status
    }

    suspend fun reconcile(): RegistrationStatus {
        val status = mutex.withLock { reconcileLocked() }
        endWhatAnInterruptedOptOutLeft()
        return status
    }

    /**
     * docs/05 §3a "Turning Smart Detection off": a relaunch while opted out ends whatever a
     * process that died mid-opt-out left behind — the flag written, the opt-out never run — or
     * every later sensor batch is gated and the stale session waits for the day detection is
     * switched back on. Every process start and every system reset reconciles, so this is
     * where the relaunch is seen. The opt-out is idempotent (the runtime writes nothing when
     * the stored state is already what it leaves), so running it on every start costs nothing.
     */
    private suspend fun endWhatAnInterruptedOptOutLeft() {
        if (!store.readDesiredEnabledOnce()) runOptOut()
    }

    /**
     * The opt-out, never allowed to fail its caller. The flag is already off, so the toggle
     * has done what the user asked; a DataStore or capture failure here is logged, and the
     * next relaunch sweeps again ([endWhatAnInterruptedOptOutLeft]).
     */
    private suspend fun runOptOut() {
        val handler = optOut ?: return
        try {
            handler(clock.nowEpochMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "opt-out did not complete: ${failure.javaClass.simpleName}")
        }
    }

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

    /**
     * Records the user's choice and reconciles the registration. Switching off then ends the
     * engine session and the capture, after the flag is written so no sensor batch in flight
     * can reopen them, and outside this lock because the runtime and the capture hold their
     * own. A failure there does not undo the opt-out or fail the toggle: the flag is already
     * off, every later batch releases whatever capture it finds, and the next relaunch ends the
     * session ([endWhatAnInterruptedOptOutLeft]).
     */
    suspend fun setDetectionEnabled(enabled: Boolean): RegistrationStatus {
        val status = mutex.withLock {
            store.setDesiredEnabled(enabled)
            reconcileLocked()
        }
        if (!enabled) runOptOut()
        return status
    }

    private companion object {
        const val TAG = "ParkingpinRegistration"
    }
}

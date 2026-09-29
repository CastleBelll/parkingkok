package com.sjstudioz.parkingpin.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sjstudioz.parkingpin.ParkingpinApplication
import kotlinx.coroutines.launch

/**
 * Restores the detection registrations after a reboot or an app update.
 *
 * Both events drop the system-side subscriptions while our DataStore records survive, so
 * recovery clears the transition record first and lets reconciliation re-register exactly
 * once (docs/04_ANDROID_IMPLEMENTATION.md §6).
 *
 * The location session is reset too, for the same reason: both events drop the Fused
 * Location request as well, so a session record that outlived it describes a registration
 * that no longer exists and has to be cleared rather than trusted. The engine is then asked
 * once, at the current time, what it still wants: a restored drive, departure or stop-only
 * resume window gets its bounded capture back (docs/05 §14 "The capture did not survive"
 * step 3). A window whose capture cannot be reopened (a revoked or "only while using"
 * permission) closes there, the candidate kept (docs/05 §3a).
 */
class RegistrationRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val container = ParkingpinApplication.containerOf(context) ?: return
        Log.i(TAG, "recovery triggered by $action")

        val pendingResult = goAsync()
        container.applicationScope.launch {
            try {
                container.registrationCoordinator.reconcileAfterSystemReset()
                container.locationSessionController.reconcileAfterSystemReset()
                // docs/05 §14: settle the restored state and reopen the bounded capture it
                // still wants — the reboot dropped that request with the others. Not with
                // Smart Detection off: nothing may open a capture the user switched off.
                if (container.detectionStateStore.readDesiredEnabledOnce()) {
                    container.parkingDetectionRuntime.resumeAfterSystemReset(container.clock.nowEpochMillis())
                }
                container.diagnosticsExporter.export()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ParkingpinRegistration"
    }
}

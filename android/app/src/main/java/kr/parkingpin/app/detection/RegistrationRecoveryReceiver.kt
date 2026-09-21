package kr.parkingpin.app.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kr.parkingpin.app.ParkingpinApplication
import kotlinx.coroutines.launch

/**
 * Restores the detection registrations after a reboot or an app update.
 *
 * Both events drop the system-side subscriptions while our DataStore records survive, so
 * recovery clears the transition record first and lets reconciliation re-register exactly
 * once (docs/04_ANDROID_IMPLEMENTATION.md §6).
 *
 * The location session is reconciled too, for the opposite reason: a reboot drops the
 * Fused Location request as well, so a session record that outlived it describes a
 * registration that no longer exists and has to be cleared rather than trusted.
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
                container.locationSessionController.reconcile()
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

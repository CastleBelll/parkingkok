package com.parkingkok.app.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.parkingkok.app.ParkingkokApplication
import kotlinx.coroutines.launch

/**
 * Restores the transition registration after a reboot or an app update.
 *
 * Both events drop the system-side subscription while our DataStore record survives, so
 * recovery clears the record first and lets reconciliation re-register exactly once
 * (docs/04_ANDROID_IMPLEMENTATION.md §6).
 */
class RegistrationRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val container = ParkingkokApplication.containerOf(context) ?: return
        Log.i(TAG, "recovery triggered by $action")

        val pendingResult = goAsync()
        container.applicationScope.launch {
            try {
                container.registrationCoordinator.reconcileAfterSystemReset()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ParkingkokRegistration"
    }
}

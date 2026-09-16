package com.parkingkok.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch

/**
 * Owns the composition root and runs registration reconciliation on every process start.
 *
 * A broadcast can be what starts the process, and `onCreate` always runs before
 * `onReceive`, so receivers can rely on [containerOf] returning a ready container.
 */
class ParkingkokApplication : Application() {

    /** Nullable rather than lateinit so a null context mismatch degrades instead of throwing. */
    private var container: AppContainer? = null

    override fun onCreate() {
        super.onCreate()
        val created = AppContainer(this)
        container = created
        // Process death leaves the Play services subscription intact, so this normally
        // resolves to "already registered" and issues no call at all.
        created.applicationScope.launch {
            created.registrationCoordinator.reconcile()
            // The third leak guard: a session record left behind by a process that died
            // mid-drive is stopped here once its deadline has passed.
            created.locationSessionController.reconcile()
            created.diagnosticsExporter.export()
        }
    }

    companion object {
        /**
         * Returns the container, or null if this component runs against a context whose
         * application is not ours (for example under a test harness).
         */
        fun containerOf(context: Context): AppContainer? =
            (context.applicationContext as? ParkingkokApplication)?.container
    }
}

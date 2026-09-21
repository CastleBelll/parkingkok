package kr.parkingpin.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch

/**
 * Owns the composition root and runs registration reconciliation on every process start.
 *
 * A broadcast can be what starts the process, and `onCreate` always runs before
 * `onReceive`, so receivers can rely on [containerOf] returning a ready container.
 */
class ParkingpinApplication : Application() {

    /** Nullable rather than lateinit so a null context mismatch degrades instead of throwing. */
    private var container: AppContainer? = null

    override fun onCreate() {
        super.onCreate()
        val created = AppContainer(this)
        container = created
        // docs/07 "동의". Its own coroutine because it collects for the life of the
        // process — putting it in the block below would keep reconciliation from ever
        // running. Nothing here awaits it: the Firebase SDK is already at the manifest
        // default of "collection off", so a slow first read from disk cannot leak an event.
        // Runs in every process, broadcast-started ones included: consent is one decision
        // and the SDK must honour it wherever it happens to be loaded.
        created.applicationScope.launch { created.analyticsCollectionGate.run() }
        // Process death leaves the Play services subscription intact, so this normally
        // resolves to "already registered" and issues no call at all.
        created.applicationScope.launch {
            created.registrationCoordinator.reconcile()
            // The third leak guard: a session record left behind by a process that died
            // mid-drive is stopped here once its deadline has passed.
            created.locationSessionController.reconcile()
            created.diagnosticsExporter.export()
        }
        // docs/06 §8 startup repair, and the reason a placed widget is right again after
        // process death. A no-op — not even a database open — when no widget is on screen.
        created.syncParkingWidgets()
    }

    companion object {
        /**
         * Returns the container, or null if this component runs against a context whose
         * application is not ours (for example under a test harness).
         */
        fun containerOf(context: Context): AppContainer? =
            (context.applicationContext as? ParkingpinApplication)?.container
    }
}

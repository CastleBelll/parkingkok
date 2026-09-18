package com.parkingkok.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.parkingkok.app.analytics.AnalyticsEvent
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.ui.navigation.ParkingkokApp
import kotlinx.coroutines.launch

/**
 * The single entry point. It owns the window and nothing else — the shell, its back stack
 * and every screen live in [ParkingkokApp] (docs/03_SYSTEM_ARCHITECTURE.md §5 keeps
 * business logic out of the Activity).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The fallback only fires under a harness whose Application is not ours. It is
        // safe because `preferencesDataStore` memoizes one DataStore per process, so a
        // second container still reads and writes the same store.
        val container = ParkingkokApplication.containerOf(this) ?: AppContainer(this)
        setContent {
            ParkingkokTheme {
                ParkingkokApp(container = container)
            }
        }
        runFirebaseSelfCheckIfRequested(container)
    }

    /**
     * Debuggable-build hook for the M5 verification run:
     *
     * ```sh
     * adb shell am start -n com.parkingkok.app/.MainActivity --ez pk_firebase_selfcheck true
     * ```
     *
     * There is no backend feature yet, so nothing in the product calls
     * [com.parkingkok.app.identity.AnonymousIdentity]. This is how both the sign-in and the
     * analytics transport get exercised against the real project without inventing a screen
     * for either — the same hook as iOS's `PK_FIREBASE_SELFCHECK`, and it is unreachable in
     * a release build.
     *
     * Deliberately after `setContent` and on the application scope: the point it
     * demonstrates is that a sign-in cannot delay the first frame (docs/04_IOS §14, and the
     * same rule here).
     */
    private fun runFirebaseSelfCheckIfRequested(container: AppContainer) {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || !intent.getBooleanExtra(EXTRA_SELF_CHECK, false)) return
        container.applicationScope.launch {
            Log.i(TAG, "selfcheck anonymous uid: ${container.anonymousIdentity.uid() ?: "unavailable"}")
            // Goes through the ordinary recorder, so the consent gate decides this exactly
            // as it decides a real event — which is the half being verified.
            container.analyticsRecorder.record(AnalyticsEvent.OnboardingCompleted)
        }
    }

    private companion object {
        const val EXTRA_SELF_CHECK = "pk_firebase_selfcheck"
        const val TAG = "PkIdentity"
    }
}

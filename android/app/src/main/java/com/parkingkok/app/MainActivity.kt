package com.parkingkok.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.parkingkok.app.analytics.AnalyticsEvent
import com.parkingkok.app.analytics.DetectionProperties
import com.parkingkok.app.detection.ParkingCandidateChannel
import com.parkingkok.app.domain.parking.ConfidenceBucket
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.ui.navigation.ParkingkokApp
import kotlinx.coroutines.launch

/**
 * The single entry point. It owns the window and nothing else — the shell, its back stack
 * and every screen live in [ParkingkokApp] (docs/03_SYSTEM_ARCHITECTURE.md §5 keeps
 * business logic out of the Activity).
 */
class MainActivity : ComponentActivity() {

    /**
     * The candidate this launch is about, consumed once.
     *
     * Held in Compose state rather than read from `intent` in the composition, because the
     * intent outlives the navigation: a configuration change would otherwise re-open the
     * confirmation screen over whatever the user had moved on to.
     */
    private var pendingCandidateId by mutableStateOf<String?>(null)

    private fun Intent.candidateId(): String? =
        getStringExtra(ParkingCandidateChannel.EXTRA_CANDIDATE_ID)?.takeIf { it.isNotEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, which is what the library requires: it swaps the splash
        // theme out for Theme.Parkingkok, so the activity is never drawn wearing the
        // splash's white window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingCandidateId = intent.candidateId()
        enableEdgeToEdge()
        // The fallback only fires under a harness whose Application is not ours. It is
        // safe because `preferencesDataStore` memoizes one DataStore per process, so a
        // second container still reads and writes the same store.
        val container = ParkingkokApplication.containerOf(this) ?: AppContainer(this)
        setContent {
            ParkingkokTheme {
                ParkingkokApp(
                    container = container,
                    candidateId = pendingCandidateId,
                    onCandidateOpened = { pendingCandidateId = null },
                )
            }
        }
        runFirebaseSelfCheckIfRequested(container)
        injectCandidateIfRequested(container)
    }

    /**
     * A candidate notification tapped while the app was already running.
     *
     * The notification's PendingIntent carries `CLEAR_TOP or SINGLE_TOP`, so a second tap
     * arrives here rather than as a new activity. Without this the app would come forward
     * showing whatever screen it was on, and the tap would do nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCandidateId = intent.candidateId()
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
        if (!isDebuggable || !intent.getBooleanExtra(EXTRA_SELF_CHECK, false)) return
        container.applicationScope.launch {
            Log.i(TAG, "selfcheck anonymous uid: ${container.anonymousIdentity.uid() ?: "unavailable"}")
            // Goes through the ordinary recorder, so the consent gate decides this exactly
            // as it decides a real event — which is the half being verified.
            container.analyticsRecorder.record(AnalyticsEvent.OnboardingCompleted)
        }
    }

    /**
     * Debuggable-build hook for verifying the candidate notification on a real device:
     *
     * ```sh
     * adb shell am start -n com.parkingkok.app/.MainActivity \
     *     --ez pk_inject_candidate true --es pk_candidate_bucket HIGH
     * ```
     *
     * There is no engine transition into `CANDIDATE_PENDING` yet, so this is how the
     * notification, the tap and the confirmation screen get exercised on hardware without
     * driving a car for forty minutes per attempt.
     *
     * **It bypasses nothing.** It calls the same
     * [com.parkingkok.app.detection.ParkingCandidateCoordinator.create] the engine will,
     * so the `low`-confidence suppression, the supersede-and-withdraw rule, the permission
     * check and the analytics event all still apply — a hook that skipped them would
     * verify something the product does not do. Unreachable in a release build, the same
     * guard [runFirebaseSelfCheckIfRequested] uses.
     *
     * The injected candidate carries no location: this hook exists to exercise the prompt,
     * and inventing a coordinate would put a fake position into a real parking record.
     */
    private fun injectCandidateIfRequested(container: AppContainer) {
        if (!isDebuggable || !intent.getBooleanExtra(EXTRA_INJECT_CANDIDATE, false)) return
        val bucket = intent.getStringExtra(EXTRA_CANDIDATE_BUCKET)
            ?.let { name -> ConfidenceBucket.entries.firstOrNull { it.name == name } }
            ?: ConfidenceBucket.HIGH
        container.applicationScope.launch {
            val candidate = container.parkingCandidateCoordinator.create(
                evidence = DetectionProperties(
                    confidenceBucket = bucket,
                    walkingEvidence = true,
                    gpsDegradation = false,
                    optionalVehicleSignal = false,
                ),
                lastReliableLocation = null,
            )
            Log.i(TAG, "injected candidate ${candidate.id} bucket=$bucket")
        }
    }

    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        const val EXTRA_SELF_CHECK = "pk_firebase_selfcheck"
        const val EXTRA_INJECT_CANDIDATE = "pk_inject_candidate"
        const val EXTRA_CANDIDATE_BUCKET = "pk_candidate_bucket"
        const val TAG = "PkIdentity"
    }
}

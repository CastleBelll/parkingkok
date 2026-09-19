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
import com.parkingkok.app.detection.ParkingCandidateChannel
import com.parkingkok.app.domain.detection.DetectionEvent
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.MotionEventKind
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
        replayDriveIfRequested(container)
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
     * Debuggable-build hook for verifying detection on a real device:
     *
     * ```sh
     * adb shell am start -n com.parkingkok.app/.MainActivity \
     *     --ez pk_replay_drive true          # motion only: enter, exit, walk
     * adb shell am start -n com.parkingkok.app/.MainActivity \
     *     --ez pk_replay_drive true --es pk_replay_scenario car_link
     * ```
     *
     * A real drive takes forty minutes per attempt and an underground car park takes a car,
     * so this feeds the §2 event vocabulary straight into
     * [com.parkingkok.app.detection.ParkingDetectionRuntime] at compressed timestamps.
     *
     * **It bypasses nothing that decides.** The events go through the same reducer,
     * the same §3a table, the same §8 scoring and the same
     * [com.parkingkok.app.detection.ParkingCandidateCoordinator] a Play services transition
     * would reach — so the 90-second promotion bar, the `low`-confidence suppression, the
     * supersede-and-withdraw rule, the notification permission check and the analytics event
     * all still apply. What it replaces is the OS delivering the transitions, which is the
     * one part a phone on a desk cannot produce.
     *
     * It replaces the earlier `pk_inject_candidate` hook, which called `create` directly and
     * therefore proved nothing about the state machine that now owns that decision.
     *
     * The replay carries no location: there is no coordinate this hook could honestly
     * supply, and inventing one would put a fake position into a real parking record. The
     * candidate is therefore the underground shape — reliable fix absent, §9 bucket earned
     * from motion evidence alone.
     */
    private fun replayDriveIfRequested(container: AppContainer) {
        if (!isDebuggable || !intent.getBooleanExtra(EXTRA_REPLAY_DRIVE, false)) return
        val withCarLink = intent.getStringExtra(EXTRA_REPLAY_SCENARIO) == SCENARIO_CAR_LINK
        val start = container.clock.nowEpochMillis() - REPLAY_DRIVE_SPAN_MILLIS
        val runtime = container.parkingDetectionRuntime

        container.applicationScope.launch {
            // Vehicle evidence, then a gap past `minimumVehicleDuration`, then the end of
            // the drive. Exactly the §3a path, at a scale a person can watch.
            runtime.handleCarLink(
                if (withCarLink) {
                    DetectionEvent.CarLinkConnected(start)
                } else {
                    DetectionEvent.VehicleEnter(start)
                },
            )
            val ending = start + REPLAY_DRIVE_SPAN_MILLIS
            if (withCarLink) {
                // The link's own row: straight to CANDIDATE_PENDING, no walk required —
                // which is the underground case motion alone cannot reach.
                runtime.handleCarLink(DetectionEvent.CarLinkDisconnected(ending))
            } else {
                runtime.handleMotion(
                    MotionDomainEvent(MotionEventKind.EXITED_VEHICLE, ending, ending),
                )
                runtime.handleMotion(
                    MotionDomainEvent(MotionEventKind.STARTED_WALKING, ending, ending),
                )
            }
            Log.i(TAG, "replay drive finished in ${runtime.restore().state}")
        }
    }

    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        const val EXTRA_SELF_CHECK = "pk_firebase_selfcheck"
        const val EXTRA_REPLAY_DRIVE = "pk_replay_drive"
        const val EXTRA_REPLAY_SCENARIO = "pk_replay_scenario"
        const val SCENARIO_CAR_LINK = "car_link"

        /**
         * How long the replayed drive claims to have lasted.
         *
         * Comfortably past both §3a's 90 s promotion bar and §7's 120 s duration clause, so
         * the run exercises a promotion that was earned rather than one waived for the
         * hook.
         */
        const val REPLAY_DRIVE_SPAN_MILLIS = 300_000L
        const val TAG = "PkIdentity"
    }
}

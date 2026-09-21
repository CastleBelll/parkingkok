package com.parkingpin.app.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The payload's parameters in the three types a Firebase event parameter may hold.
 *
 * Split out of [FirebaseAnalyticsSink] because it is the last place a value changes shape
 * before leaving the device, and `Bundle` is unavailable to a JVM unit test — the same
 * layering as `FirebaseAnonymousSignIn`, which keeps every decision on the testable side.
 *
 * `Flag` becomes `"true"`/`"false"` rather than a boolean: Firebase silently drops a
 * boolean put into an event bundle, and the string is the shape iOS sends too, so the same
 * event from the two platforms lands in one comparable column (docs/05 parity).
 */
internal fun firebaseParameters(payload: AnalyticsPayload): Map<String, Any> =
    payload.parameters.mapValues { (_, value) ->
        when (value) {
            is AnalyticsValue.Text -> value.value
            is AnalyticsValue.Count -> value.value.toLong()
            is AnalyticsValue.Flag -> value.value.toString()
        }
    }

/**
 * The Firebase transport (docs/07 §2 "Analytics 전송 수단").
 *
 * It sees an [AnalyticsPayload] and nothing else, which is the whole point of the seam: the
 * payload's constructor is private and only an [AnalyticsEvent] can build one, so this class
 * has no way to attach a key docs/17 §3 does not allow. It is a translator, not a decision
 * point — every decision was made before the payload reached it.
 *
 * Reaching here already means consent was granted: [AnalyticsRecorder] re-reads the flag
 * before every send. The SDK's own collection switch is the second gate and belongs to
 * [AnalyticsCollectionGate].
 */
class FirebaseAnalyticsSink(private val firebaseAnalytics: FirebaseAnalytics) : AnalyticsSink {

    override fun send(payload: AnalyticsPayload) {
        val bundle = Bundle()
        firebaseParameters(payload).forEach { (key, value) ->
            when (value) {
                is Long -> bundle.putLong(key, value)
                else -> bundle.putString(key, value.toString())
            }
        }
        firebaseAnalytics.logEvent(payload.name, bundle)
    }
}

/**
 * The SDK's own collection switch.
 *
 * Separate from the sink because it governs what Firebase sends *on its own initiative* —
 * `session_start`, `first_open`, `app_update` and the rest never pass through
 * [AnalyticsRecorder].
 */
fun interface AnalyticsCollectionControl {

    fun setEnabled(granted: Boolean)
}

/** Drives [FirebaseAnalytics.setAnalyticsCollectionEnabled]. */
class FirebaseAnalyticsCollectionControl(
    private val firebaseAnalytics: FirebaseAnalytics,
) : AnalyticsCollectionControl {

    override fun setEnabled(granted: Boolean) {
        firebaseAnalytics.setAnalyticsCollectionEnabled(granted)
    }
}

/**
 * The control for a build with no Firebase project.
 *
 * Doing nothing is correct here rather than merely convenient: with no `google-services.json`
 * the SDK has no project to report to, so there is nothing that could collect.
 */
object NoAnalyticsCollectionControl : AnalyticsCollectionControl {

    override fun setEnabled(granted: Boolean) = Unit
}

/**
 * **What makes the consent gate cover Firebase's own events, not just ours.**
 *
 * The manifest ships `firebase_analytics_collection_enabled=false`, so the SDK starts every
 * process collecting nothing. This is the only thing that ever turns it on, and it turns it
 * on exactly while the stored consent flag is true — so a revocation reaches the SDK
 * immediately rather than merely stopping the next [AnalyticsRecorder] event.
 *
 * Reading the flag as a flow rather than once is the point: consent changes while the
 * process is alive, and a gate that only looked at startup would leave the SDK collecting
 * for the rest of the session after a revocation.
 */
class AnalyticsCollectionGate(
    private val consentStore: AnalyticsConsentStore,
    private val control: AnalyticsCollectionControl,
) {

    /** Collects for the life of the process. Never returns. */
    suspend fun run() {
        consentStore.granted
            .distinctUntilChanged()
            .collect { granted -> control.setEnabled(granted) }
    }
}

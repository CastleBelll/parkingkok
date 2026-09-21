package com.sjstudioz.parkingpin.analytics

import android.util.Log

/**
 * Where a payload goes once the consent gate has let it through.
 *
 * **The replaceable seam.** docs/07 §2 fixed Firebase Analytics as the transport, but there
 * is no Firebase project yet and therefore no `google-services.json`, so this build ships a
 * sink that sends nothing. Adding Firebase is one new implementation plus one line in
 * `AppContainer`; nothing above this interface knows what the transport is.
 */
interface AnalyticsSink {

    fun send(payload: AnalyticsPayload)
}

/**
 * The transport this milestone ships: none.
 *
 * Not a stub to be tolerated — with no Firebase project, "drop it" is the only honest
 * behaviour. Buffering until one exists would be the buffering docs/07 "동의" forbids, only
 * worse, because nobody would have consented to the backlog.
 */
object NoOpAnalyticsSink : AnalyticsSink {

    override fun send(payload: AnalyticsPayload) = Unit
}

/**
 * Debuggable-build readout, so the wiring can be verified on a device before any transport
 * exists. `AppContainer` installs it only in a debuggable build, and `Log.isLoggable` gates
 * it again — `adb shell setprop log.tag.PkAnalytics DEBUG` is what turns it on.
 *
 * Safe to log: a payload's keys are [AnalyticsPayload.Key.ALL] and its values are buckets
 * and booleans, so there is no coordinate in here to leak (docs/09 §11).
 */
object LogAnalyticsSink : AnalyticsSink {

    private const val TAG = "PkAnalytics"

    override fun send(payload: AnalyticsPayload) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "send ${payload.debugSummary}")
        }
    }
}

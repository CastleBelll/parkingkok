package com.parkingkok.app.analytics

import com.parkingkok.app.core.Clock

/**
 * What a feature depends on to report an event. An [AnalyticsEvent] is the only argument it
 * takes, which is the whole of the type-level guarantee.
 */
interface AnalyticsRecording {

    suspend fun record(event: AnalyticsEvent)
}

/**
 * The consent gate (docs/07 "동의").
 *
 * Consent is re-read from the store on every event rather than cached, so switching the
 * toggle off stops transmission on the next event with no invalidation step to get wrong.
 * That read is why [record] suspends — the flag lives in DataStore.
 *
 * **Nothing is buffered.** A dropped event is gone. docs/07 is explicit: if the user later
 * consents there must be no backlog to flush, and if they refuse there must never have been
 * one — consenting to future reporting is not consenting to the past.
 */
class AnalyticsRecorder(
    private val consentStore: AnalyticsConsentStore,
    private val sink: AnalyticsSink,
    private val clock: Clock,
) : AnalyticsRecording {

    override suspend fun record(event: AnalyticsEvent) {
        if (!consentStore.isGrantedOnce()) return
        sink.send(AnalyticsPayload.of(event, clock.nowEpochMillis()))
    }
}

/**
 * A recorder that reports nothing, for the call sites that have no analytics stack —
 * tests, previews, and any composition that has not been given one.
 */
object DisabledAnalyticsRecorder : AnalyticsRecording {

    override suspend fun record(event: AnalyticsEvent) = Unit
}

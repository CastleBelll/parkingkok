package com.parkingpin.app.domain.trace

/**
 * Decides where one recorded trip ends and the next begins.
 *
 * ## Why not the two obvious answers
 *
 * **Smart detection ON/OFF** is the boundary the user controls, and it is the only one
 * that is unambiguous — but nobody toggles it per trip. Left on for a week it produces one
 * session holding a commute, three bus rides and a walk, and a fixture converted from that
 * describes nothing. It is kept as *a* boundary (turning detection off closes the open
 * session, see [com.parkingpin.app.trace.TraceRecorder.closeOpenSession]) but it cannot be
 * the only one.
 *
 * **The bounded driving session** is the right boundary for a drive and the wrong one for
 * everything else. `LocationCaptureModePolicy` only opens a capture on vehicle evidence,
 * so a walk or a subway ride — exactly the negative cases
 * docs/05_PARKING_DETECTION_ENGINE.md §17 requires, and the ones collectable without a car
 * — would never start a session and would never be recorded at all.
 *
 * ## What this does instead
 *
 * A session is a run of events with no long silence in it. The gap is evaluated lazily,
 * when the next event arrives, so there is no timer, no polling and no wakeup: the battery
 * cost of the boundary is zero, which is what §9 requires of the recorder as a whole.
 *
 * The caps on top of the gap are what keep the rolling limit meaningful. A device that
 * moves all day with no half-hour of stillness would otherwise grow one unbounded session,
 * and a cap that only counts whole sessions cannot evict part of one.
 */
object TraceSessionBoundaryPolicy {

    /**
     * Silence that ends a trip.
     *
     * Has to clear the platform's own delivery cadence to mean anything. Background
     * location delivery on the reference Galaxy S21+ was measured throttled to roughly one
     * batch per 10 minutes (foreground: 15.5 s), and Activity Transitions arrive only when
     * the activity actually changes — a subway ride can be genuinely silent for a long
     * stretch without having ended. 30 minutes is comfortably past the throttle while
     * still splitting an outbound trip from the return one.
     */
    const val IDLE_GAP_MILLIS: Long = 30L * 60L * 1_000L

    /** Past this a session is no longer one trip, whatever the gaps say. */
    const val MAX_SESSION_MILLIS: Long = 4L * 60L * 60L * 1_000L

    /**
     * Roughly a 4-hour drive at the 15 s foreground cadence, so the duration cap is what
     * normally fires and this one only catches a pathological event rate.
     */
    const val MAX_EVENTS_PER_SESSION: Int = 1_000

    /** Ordinary clock skew, not a clock change. Matches the location freshness tolerance. */
    private const val CLOCK_TOLERANCE_MILLIS: Long = 5_000L

    /**
     * Fewer events than this and a finished session is not worth storing
     * (§9 "비생존 세션은 버린다").
     *
     * Two, because one event is a lone motion edge with half an hour of silence on either
     * side. It cannot become a §8 fixture — a fixture needs a sequence to replay — and it
     * tells the engine nothing it could have acted on. Five of the nine sessions in the
     * September 2026 iOS field set were exactly that, and on Android one of the four
     * retrieved traces was.
     */
    const val MINIMUM_VIABLE_EVENT_COUNT: Int = 2

    /**
     * Whether a session that has stopped growing is worth keeping.
     *
     * **Only ever asked at rotation, or when the user closes recording.** An open session
     * holding one event is not a failure, it is a session that has had one event so far,
     * and judging it early would throw away a trip on the strength of its first edge —
     * which on Android is guaranteed to happen, because the open session lives on disk
     * between two PendingIntent deliveries and is written the moment it has one event.
     */
    fun isViable(eventCount: Int): Boolean = eventCount >= MINIMUM_VIABLE_EVENT_COUNT

    /** Why the open session was closed. Diagnostic only — §9's schema has no field for it. */
    enum class RotationReason {
        /** No event for [IDLE_GAP_MILLIS]. The ordinary end of a trip. */
        IDLE_GAP,

        MAX_DURATION,

        MAX_EVENTS,

        /**
         * The new event predates the open session.
         *
         * A wall-clock jump — an NTP correction, a manual clock change, a timezone-naive
         * OEM fix — would otherwise write a session whose events run backwards. The
         * converter turns `atMillis` into relative seconds from the first event, so a
         * negative offset produces a fixture that cannot be replayed. Splitting keeps the
         * damage to one boundary instead of corrupting the whole recording.
         */
        CLOCK_WENT_BACKWARDS,
    }

    /**
     * @return the reason [open] must be closed before [eventAtMillis] is recorded, or null
     *   to append to it. A null [open] is not a rotation — there is simply nothing open yet.
     */
    fun rotationReason(open: TraceSession?, eventAtMillis: Long): RotationReason? {
        if (open == null) return null
        return when {
            eventAtMillis < open.startedAt - CLOCK_TOLERANCE_MILLIS -> RotationReason.CLOCK_WENT_BACKWARDS
            eventAtMillis - open.endedAt >= IDLE_GAP_MILLIS -> RotationReason.IDLE_GAP
            eventAtMillis - open.startedAt >= MAX_SESSION_MILLIS -> RotationReason.MAX_DURATION
            open.events.size >= MAX_EVENTS_PER_SESSION -> RotationReason.MAX_EVENTS
            else -> null
        }
    }
}

package com.parkingpin.app.domain.trace

/**
 * The outcome of asking for a session to be cut in two.
 *
 * A refusal is modelled as a value rather than thrown, because on this path refusing is an
 * ordinary result, not an error: §9 makes a cut that would leave a one-event fragment
 * invalid, and the person choosing the point has no way to know that until they choose it.
 * The screen has to be able to say *why*, so each refusal carries what it knows.
 */
sealed interface TraceSplitResult {

    data class Fragments(val leading: TraceSession, val trailing: TraceSession) : TraceSplitResult

    /** Everything that is not a split. Narrower than [TraceSplitResult] so the UI can exhaust it. */
    sealed interface Refusal : TraceSplitResult

    /** The session was evicted, or was never there. */
    data object SessionNotFound : Refusal

    /**
     * The session is still being recorded. §9 allows cutting a closed session only: more
     * events may still join an open one, and replacing it would pull the file out from
     * under the recorder mid-append.
     */
    data object SessionIsOpen : Refusal

    /** A cut before the first event or after the last one, which would leave a side empty. */
    data object IndexOutOfRange : Refusal

    /**
     * One of the two halves would be a session §9 discards. Carries both counts so the
     * screen can say which side was too small rather than only that one was.
     */
    data class FragmentNotViable(
        val leadingEventCount: Int,
        val trailingEventCount: Int,
    ) : Refusal

    /** The store could not write the fragments. Short and coordinate-free, as [TraceStore] returns it. */
    data class StoreFailure(val reason: String) : Refusal
}

/**
 * Cuts one recorded session into two, because the device could not
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "사람이 세션을 나눈다").
 *
 * ### Why a person has to do this
 * Sitting at a desk and riding a train produce the same kind of evidence — a stationary
 * edge, a walking edge, a long silence — and in the September 2026 field set the silences
 * were indistinguishable by size: the office waits measured 20.7, 28.9 and 28.4 minutes
 * while a genuine stretch of subway ran 16.9 minutes with nothing recorded. Any threshold
 * that separated the first group would have cut the second in half.
 *
 * So the cut is a judgement, and this object only carries it out. It decides nothing about
 * *where*; [TraceGapStats] and the event list give the screen the numbers a person needs
 * to choose.
 *
 * ### What a fragment is
 * A whole session, not an annotation on the parent: its own [TraceSession.sessionId], its
 * own `startedAt`/`endedAt`, its own recomputed [TraceGapStats], and its own label. That is
 * the point — labelling each half separately is the reason to split at all — and it is
 * also what lets the fixture converter read a fragment without knowing it was ever part of
 * something longer. [TraceSplitOrigin] is the only trace of the parent, and both halves
 * carry the same one so the pair survives the parent's eviction.
 *
 * ### What it does not inherit
 * The parent's `mode` and `parked`. A label attached to the whole was a claim about a
 * mixture, and copying it onto both halves would assert it twice as precisely as it was
 * ever meant — the subway session would hand `subway` to the hour and a half of sitting
 * still, and the converter treats a label as ground truth. Both halves come back
 * unlabelled. The free-text `note` does carry over, because the parent file is about to be
 * replaced and a person cannot retype what they observed.
 */
object TraceSessionSplit {

    /**
     * Splits [session] so that [atEventIndex] is the first event of the second fragment.
     *
     * @param sessionIdFactory injected so a test can assert on identity; the fragments are
     *   new sessions and must not reuse the parent's id.
     */
    fun split(
        session: TraceSession,
        atEventIndex: Int,
        sessionIdFactory: () -> String,
    ): TraceSplitResult {
        if (atEventIndex <= 0 || atEventIndex >= session.events.size) {
            return TraceSplitResult.IndexOutOfRange
        }

        val leadingEvents = session.events.subList(0, atEventIndex).toList()
        val trailingEvents = session.events.subList(atEventIndex, session.events.size).toList()
        if (!TraceSessionBoundaryPolicy.isViable(leadingEvents.size) ||
            !TraceSessionBoundaryPolicy.isViable(trailingEvents.size)
        ) {
            // §9: "조각도 비생존 규칙을 따른다." Refusing is the whole answer — producing
            // the viable half alone would silently delete the events on the other side.
            return TraceSplitResult.FragmentNotViable(
                leadingEventCount = leadingEvents.size,
                trailingEventCount = trailingEvents.size,
            )
        }

        // The cut is named by the event the user picked, and both halves record the same
        // instant, so either one alone identifies the boundary.
        val origin = TraceSplitOrigin(
            parentSessionId = session.sessionId,
            atMillis = trailingEvents.first().atMillis,
        )

        return TraceSplitResult.Fragments(
            leading = session.fragment(
                sessionId = sessionIdFactory(),
                events = leadingEvents,
                startedAt = session.startedAt,
                origin = origin,
            ),
            trailing = session.fragment(
                sessionId = sessionIdFactory(),
                events = trailingEvents,
                startedAt = trailingEvents.first().atMillis,
                origin = origin,
            ),
        )
    }

    /**
     * `endedAt` is the fragment's last event, exactly as the recorder leaves it on a
     * session it rotated away from: a fragment must be indistinguishable from a recorded
     * session apart from [TraceSession.splitFrom].
     */
    private fun TraceSession.fragment(
        sessionId: String,
        events: List<TraceEvent>,
        startedAt: Long,
        origin: TraceSplitOrigin,
    ): TraceSession = copy(
        sessionId = sessionId,
        startedAt = startedAt,
        endedAt = events.last().atMillis,
        label = TraceLabel(note = label.note),
        events = events,
        gapStats = TraceGapStats.of(events),
        splitFrom = origin,
    )
}

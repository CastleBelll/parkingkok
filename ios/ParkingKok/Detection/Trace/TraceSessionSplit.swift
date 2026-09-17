import Foundation

/// Why a requested split was refused.
enum TraceSplitError: Error, Equatable {
    /// No such event, or a cut that would leave one side empty.
    case indexOutOfRange
    /// One of the two halves would be a session §9 discards. Carries both counts so the
    /// screen can say which side was too small rather than only that something was.
    case fragmentNotViable(leadingEventCount: Int, trailingEventCount: Int)
}

/// Cuts one recorded session into two, because the device could not
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "사람이 세션을 나눈다").
///
/// ### Why a person has to do this
/// Sitting at a desk and riding a train produce the same kind of evidence — a stationary
/// edge, a walking edge, a long silence — and in the September 2026 field set the silences
/// were indistinguishable by size: the office waits measured 20.7, 28.9 and 28.4 minutes
/// while a genuine stretch of subway ran 16.9 minutes with nothing recorded. Any threshold
/// that separated the first group would have cut the second in half. The session that
/// resulted ran 2h39m and was labelled `subway`, of which the actual ride was 45 minutes.
///
/// So the cut is a judgement, and this type only carries it out. It decides nothing about
/// *where*; `TraceGapStats` gives the screen the numbers a person needs to choose.
///
/// ### What a fragment is
/// A whole session, not an annotation on the parent: its own `sessionId`, its own
/// `startedAt`/`endedAt`, its own recomputed `gapStats`, and its own label. That is the
/// point — labelling each half separately is the reason to split at all — and it is also
/// what lets the existing converter read a fragment without knowing it was ever part of
/// something longer. `splitFrom` is the only trace of the parent, and both halves carry
/// the same one so the pair survives the parent's eviction.
///
/// ### What it does not inherit
/// The parent's `mode` and `parked`. A label attached to the whole was a claim about a
/// mixture, and copying it onto both halves would assert it twice as precisely as it was
/// ever meant — the subway session would hand `subway` to the hour and a half of sitting
/// still, and the converter treats a label as ground truth. Both halves come back
/// unlabelled and the screen shows them as needing a human. The free-text `note` does
/// carry over, because the parent file is about to be replaced and a person cannot
/// retype what they observed.
enum TraceSessionSplit {
    /// Splits `session` so that `index` is the first event of the second fragment.
    ///
    /// - Parameter makeSessionId: injected so a test can assert on identity; the fragments
    ///   are new sessions and must not reuse the parent's id.
    static func split(
        _ session: TraceSession,
        atEventIndex index: Int,
        makeSessionId: () -> UUID = UUID.init
    ) throws -> (leading: TraceSession, trailing: TraceSession) {
        guard index > 0, index < session.events.count else {
            throw TraceSplitError.indexOutOfRange
        }

        let leadingEvents = Array(session.events[..<index])
        let trailingEvents = Array(session.events[index...])
        guard TraceSessionBoundaryPolicy.isViable(eventCount: leadingEvents.count),
              TraceSessionBoundaryPolicy.isViable(eventCount: trailingEvents.count)
        else {
            // §9: "조각도 비생존 규칙을 따른다." Refusing is the whole answer — producing
            // the viable half alone would silently delete the events on the other side.
            throw TraceSplitError.fragmentNotViable(
                leadingEventCount: leadingEvents.count,
                trailingEventCount: trailingEvents.count
            )
        }

        // The cut is named by the event the user picked, and both halves record the same
        // instant, so either one alone identifies the boundary.
        let origin = TraceSplitOrigin(
            parentSessionId: session.sessionId,
            atMillis: trailingEvents[0].atMillis
        )

        return (
            fragment(
                of: session,
                events: leadingEvents,
                startedAt: session.startDate,
                origin: origin,
                id: makeSessionId()
            ),
            fragment(
                of: session,
                events: trailingEvents,
                startedAt: trailingEvents[0].date,
                origin: origin,
                id: makeSessionId()
            )
        )
    }

    /// `endedAt` is the fragment's last event, exactly as the recorder writes it for a
    /// session it closed — a fragment must be indistinguishable from a recorded session
    /// apart from `splitFrom`.
    private static func fragment(
        of parent: TraceSession,
        events: [TraceEvent],
        startedAt: Date,
        origin: TraceSplitOrigin,
        id: UUID
    ) -> TraceSession {
        TraceSession(
            sessionId: id,
            metadata: parent.metadata,
            startedAt: startedAt,
            endedAt: events[events.count - 1].date,
            label: TraceLabel(mode: .unknown, parked: nil, note: parent.label.note),
            events: events,
            gapStats: TraceGapStats(events: events),
            splitFrom: origin
        )
    }
}

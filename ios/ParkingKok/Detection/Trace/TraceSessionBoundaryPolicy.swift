import Foundation

/// Decides where one recorded trip ends and the next begins
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "세션 경계 — 양 플랫폼 동일").
///
/// The semantics, the constants and the rotation reasons are the same ones
/// `TraceSessionBoundaryPolicy.kt` implements on Android. The contract fixes them for both
/// platforms so that the same event stream yields the same sessions, which is what makes a
/// trace convertible into a §8 fixture at all.
///
/// ### Why not the two obvious answers
///
/// **Smart Detection ON/OFF** is the boundary the user controls, and the only unambiguous
/// one — but nobody toggles it per trip. Left on for a week it produces one session holding
/// a commute, three bus rides and a walk, and a fixture cut from that describes nothing. It
/// stays as *a* boundary (opting out closes the open session, see
/// `TraceRecorder.closeOpenSession`) but it cannot be the only one.
///
/// **The bounded driving session** is the right boundary for a drive and the wrong one for
/// everything else. Capture only opens on vehicle evidence, so a walk or a subway ride —
/// exactly the negative cases docs/05_PARKING_DETECTION_ENGINE.md §17 requires, and the ones
/// collectable without a car — would never start a session and would never be recorded.
/// iOS shipped that first and lost walking entirely; §9 now names it as the mistake.
///
/// ### What this does instead
///
/// A session is a run of events with no long silence in it. The gap is judged lazily, when
/// the next event arrives, so there is no timer, no polling and no wakeup: the battery cost
/// of the boundary is zero, which is what §9 requires of the recorder as a whole.
///
/// The caps on top of the gap are what keep the rolling limit meaningful. A device that
/// moves all day with no half-hour of stillness would otherwise grow one unbounded session,
/// and a cap that only counts whole sessions cannot evict part of one.
enum TraceSessionBoundaryPolicy {
    /// Silence that ends a trip.
    ///
    /// Has to clear the platform's own delivery cadence to mean anything. Significant-change
    /// wakes arrive only every few hundred metres, Core Motion history is re-queried on a
    /// wake rather than pushed, and a subway ride can be genuinely silent for a long stretch
    /// without having ended. 30 minutes is comfortably past that while still splitting an
    /// outbound trip from the return one (§9; the number was measured on Android's reference
    /// device and the contract fixes it for both platforms).
    static let idleGap: TimeInterval = 30 * 60

    /// Past this a session is no longer one trip, whatever the gaps say.
    static let maximumDuration: TimeInterval = 4 * 60 * 60

    /// Roughly a 4-hour drive at the recorder's downsampled location cadence, so the
    /// duration cap is what normally fires and this one only catches a pathological rate.
    static let maximumEvents = 1000

    /// Ordinary clock skew, not a clock change. Matches the location freshness tolerance.
    static let clockTolerance: TimeInterval = 5

    /// Why the open session was closed. Diagnostic only — §9's schema has no field for it.
    enum RotationReason: String, Sendable, Equatable, CaseIterable {
        /// No event for `idleGap`. The ordinary end of a trip.
        case idleGap

        case maximumDuration

        case maximumEvents

        /// The new event predates the open session.
        ///
        /// A wall-clock jump — an NTP correction, a manual clock change — would otherwise
        /// write a session whose events run backwards. The converter turns `atMillis` into
        /// relative seconds from the first event, so a negative offset produces a fixture
        /// that cannot be replayed. Splitting keeps the damage to one boundary instead of
        /// corrupting the whole recording.
        case clockWentBackwards
    }

    /// The reason the open session must be closed before `date`'s event is recorded, or
    /// `nil` to append to it. A `nil` session is not a rotation — there is simply nothing
    /// open yet.
    static func rotationReason(for open: TraceSession?, nextEventAt date: Date) -> RotationReason? {
        guard let open else { return nil }
        return rotationReason(
            startedAt: open.startDate,
            endedAt: open.endDate,
            eventCount: open.events.count,
            nextEventAt: date
        )
    }

    /// The same decision taken on the fields alone, for the recorder's in-flight session.
    ///
    /// `endedAt` is the newest recorded event, never "now": the gap is a property of the
    /// event stream, so nothing here reads a clock (docs/16_CODING_STANDARDS.md §8).
    static func rotationReason(
        startedAt: Date,
        endedAt: Date,
        eventCount: Int,
        nextEventAt date: Date
    ) -> RotationReason? {
        if date < startedAt - clockTolerance {
            return .clockWentBackwards
        }
        if date.timeIntervalSince(endedAt) >= idleGap {
            return .idleGap
        }
        if date.timeIntervalSince(startedAt) >= maximumDuration {
            return .maximumDuration
        }
        if eventCount >= maximumEvents {
            return .maximumEvents
        }
        return nil
    }
}

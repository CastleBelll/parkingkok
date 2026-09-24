import Foundation

/// The question a just-closed session puts to the user, reduced to what a notification
/// can carry (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 labelling).
///
/// ### Why this exists
/// The in-app labelling screen shipped and was never used: eight sessions on the reference
/// device, every one of them `unknown`. The user carries the phone and does not open the
/// app, so the label has to be asked for where they already are — the lock screen — at the
/// one moment they still remember what the trip was. An unlabelled trace cannot become a
/// §8 fixture: the converter derives `expected` from `label.mode`, and `unknown` leaves a
/// human guessing from the raw events weeks later.
///
/// ### P0 instrumentation, not product UI
/// This is not the M3 candidate notification and must never be mistaken for it — it asks
/// what a *past* trip was, it is posted on its own diagnostics channel/category, and it is
/// deliberately confined to these few types so the whole thing can be deleted in one
/// commit once enough field data exists.
///
/// ### A pure value, mirrored on Android
/// `TraceLabelPrompt.kt` computes the same fields from the same events, so a test can
/// state that both platforms ask the same question about the same recording — the same
/// reason `TraceSessionBoundaryPolicy` is a domain type rather than platform code. The
/// Korean strings live here for the same reason: they are then readable by a unit test,
/// which is what lets the "no coordinate in the body" rule be enforced rather than
/// asserted.
///
/// **No coordinate is representable here.** It is built from `TraceSession`, which has no
/// field to put one in, and the body is assembled from times, minutes and counts only.
struct TraceLabelPrompt: Sendable, Equatable {
    let sessionId: UUID
    let startedAt: Date
    let endedAt: Date
    let eventCount: Int
    /// Milliseconds spent inside `vehicle_enter` → `vehicle_exit` spans. `0` with
    /// `hasVehicle` true means a vehicle edge was seen but never bracketed — Core Motion
    /// routinely reports one side of the pair only.
    let vehicleMillis: Int64
    let hasVehicle: Bool
    let hasWalking: Bool
    let hasStationary: Bool

    /// The modes offered as one-tap actions, most useful first.
    ///
    /// `car`, `bus` and `subway` are indistinguishable from the recorded events — all three
    /// are a `vehicle_enter` followed by movement — so they are exactly what only a person
    /// can supply, and the two negative ones are the fixtures
    /// docs/05_PARKING_DETECTION_ENGINE.md §17 asks for and that no amount of driving
    /// produces. `walk` comes last because a walk-only trace is still readable from its
    /// events afterwards, which is why it is the one Android's three-action limit drops.
    ///
    /// `taxi` and `still` are deliberately absent: four actions is iOS's ceiling, and both
    /// remain available in the in-app labelling screen, which this never replaces.
    static let offeredModes: [TraceMode] = [.car, .bus, .subway, .walk]

    /// `nil` when there is nothing worth asking about.
    ///
    /// Two refusals, both deliberate:
    ///
    /// 1. **No motion event.** A session of three `location` events is a question the user
    ///    cannot answer — nothing in it distinguishes a bus from a desk, and they were not
    ///    told anything was being recorded at the time. Ask only what can be answered.
    /// 2. **Not viable.** §9 discards a session of fewer than
    ///    `TraceSessionBoundaryPolicy.minimumViableEventCount` events, so prompting for one
    ///    would ask about a file that is about to be deleted.
    static func of(_ session: TraceSession) -> TraceLabelPrompt? {
        guard TraceSessionBoundaryPolicy.isViable(eventCount: session.events.count) else { return nil }
        guard session.events.contains(where: \.type.isMotion) else { return nil }

        var vehicleMillis: Int64 = 0
        var enteredAt: Int64?
        for event in session.events {
            switch event.type {
            case .vehicleEnter:
                // The first enter of an unclosed span wins; a repeated enter is Core
                // Motion restating a drive, not a second boarding.
                if enteredAt == nil {
                    enteredAt = event.atMillis
                }
            case .vehicleExit:
                if let start = enteredAt {
                    vehicleMillis += event.atMillis - start
                    enteredAt = nil
                }
            default:
                break
            }
        }
        // A drive whose exit never arrived ran to the end of the recording, which is what
        // the session's own `endedAt` says.
        if let start = enteredAt {
            vehicleMillis += max(0, session.endedAt - start)
        }

        let types = Set(session.events.map(\.type))
        return TraceLabelPrompt(
            sessionId: session.sessionId,
            startedAt: session.startDate,
            endedAt: session.endDate,
            eventCount: session.events.count,
            vehicleMillis: vehicleMillis,
            hasVehicle: types.contains(.vehicleEnter) || types.contains(.vehicleExit),
            hasWalking: types.contains(.walkingEnter),
            hasStationary: types.contains(.stationaryEnter) || types.contains(.stationaryExit)
        )
    }

    /// Deliberately a question, so it cannot read as the M3 "your car is parked here"
    /// notification.
    static let title = "이 이동, 무엇이었나요?"

    /// What the user has to recognise the trip by: when it ran, how long, and what the
    /// device saw. Never where.
    ///
    /// `timeZone` is a parameter rather than `.current` at the call site so the text is
    /// checkable by a test (docs/16_CODING_STANDARDS.md §8: inject, never read ambient
    /// state in logic worth asserting on).
    func body(timeZone: TimeZone = .current) -> String {
        let range = "\(Self.clockTime(startedAt, in: timeZone))–\(Self.clockTime(endedAt, in: timeZone))"
        let total = "\(Self.minutes(endedAt.timeIntervalSince(startedAt)))분"
        return "\(range) · \(total) · \(movementSummary) · 이벤트 \(eventCount)개"
    }

    /// The events in the words a person would use. Never empty — `of` refuses a session
    /// with no motion event at all, so at least one of the three parts is present.
    var movementSummary: String {
        var parts: [String] = []
        if hasVehicle {
            let minutes = Self.minutes(TimeInterval(vehicleMillis) / 1000)
            parts.append(minutes > 0 ? "차량 \(minutes)분" : "차량")
        }
        if hasWalking {
            parts.append("도보")
        }
        if hasStationary {
            parts.append("정지")
        }
        return parts.joined(separator: " + ")
    }

    /// What one tap means (§9 `label`).
    ///
    /// `parked` is inferred rather than asked, because a second tap is the thing that
    /// stopped the in-app screen from being used: a walk, a bus and a subway park nothing,
    /// and only a car leaves the question genuinely open — so a car stays `nil` and is
    /// answered in the app by whoever needs it. §9 makes `nil` a first-class value, so
    /// this claims nothing it does not know.
    static func label(for mode: TraceMode) -> TraceLabel {
        switch mode {
        case .car, .taxi:
            TraceLabel(mode: mode, parked: nil, note: nil)
        case .bus, .subway, .walk, .still:
            TraceLabel(mode: mode, parked: false, note: nil)
        case .unknown:
            .unlabeled
        }
    }

    /// Fixed 24-hour, not `Date.formatted`: the body sits beside a duration and an event
    /// count, and "오후 2:03" is longer and harder to compare against the next line.
    private static func clockTime(_ date: Date, in timeZone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = timeZone
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    private static func minutes(_ interval: TimeInterval) -> Int {
        Int((interval / 60).rounded())
    }
}

extension TraceMode {
    /// The word on the notification button. Only the offered modes have one that matters;
    /// the rest are spelled out anyway so the labelling screen and the notification never
    /// disagree about what a mode is called.
    var promptActionTitle: String {
        switch self {
        case .car: "자동차"
        case .bus: "버스"
        case .subway: "지하철"
        case .taxi: "택시"
        case .walk: "도보"
        case .still: "정지"
        case .unknown: "모름"
        }
    }
}

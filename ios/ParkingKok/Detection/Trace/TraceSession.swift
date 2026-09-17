import Foundation

/// How the user was travelling, as told to the app afterwards (docs/05 §9 `label.mode`).
///
/// The negative modes carry the weight here: bus, subway and taxi are rides anyone can
/// take without a car, and they are exactly the cases §17's fixture list needs and that
/// twenty repeated real drives would never produce.
enum TraceMode: String, Sendable, Equatable, Codable, CaseIterable, Identifiable {
    case car
    case bus
    case subway
    case taxi
    case walk
    case still
    case unknown

    var id: String {
        rawValue
    }
}

/// What a human said about a recorded session.
///
/// §9: "label은 기기가 알 수 없다. 사람이 앱에서 붙인다." The device deliberately never
/// guesses — a trace labelled by the engine's own opinion could only ever confirm it.
struct TraceLabel: Sendable, Equatable, Codable {
    var mode: TraceMode
    /// `nil` when the user has not said, which §9 encodes as absent/null.
    var parked: Bool?
    var note: String?

    static let unlabeled = TraceLabel(mode: .unknown, parked: nil, note: nil)

    /// A note alone is not a label: the converter needs `mode` and `parked` to fill in a
    /// fixture's `expected`, and counting a note as labelled would hide sessions that
    /// still need a human.
    var isLabeled: Bool {
        mode != .unknown || parked != nil
    }
}

/// Which build produced a trace. Local-only metadata, carried so a fixture derived months
/// later still says which hardware and OS it came from (docs/05 §18 field tuning spans
/// multiple iPhone generations).
struct TraceDeviceMetadata: Sendable, Equatable {
    let platform: String
    let deviceModel: String
    let osVersion: String
    let appVersion: String

    init(platform: String = "ios", deviceModel: String, osVersion: String, appVersion: String) {
        self.platform = platform
        self.deviceModel = deviceModel
        self.osVersion = osVersion
        self.appVersion = appVersion
    }

    /// The running device. `utsname.machine` gives the model identifier §9 asks for
    /// (`iPhone15,3`); `UIDevice.model` would only say "iPhone".
    static var current: TraceDeviceMetadata {
        let info = AppInfo.current
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return TraceDeviceMetadata(
            deviceModel: machineIdentifier(),
            osVersion: "\(version.majorVersion).\(version.minorVersion)",
            appVersion: "\(info.version) (\(info.buildNumber))"
        )
    }

    private static func machineIdentifier() -> String {
        var system = utsname()
        guard uname(&system) == 0 else { return AppInfo.unknownValue }
        let identifier = withUnsafeBytes(of: &system.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
        return identifier.isEmpty ? AppInfo.unknownValue : identifier
    }
}

/// What the gaps inside one recorded session looked like (docs/05 §9 "gap 계측").
///
/// **This exists to retire a guess, not to act on one.** The 30-minute idle gap was set
/// from a single day of observation and the same day showed it landing badly: three office
/// waits of 20.7 / 28.9 / 28.4 minutes all stayed inside one session — the largest missing
/// the threshold by 66 seconds — while a 16.9-minute silence sat in the middle of a real
/// subway ride, so lowering the number would have cut a genuine journey in two. Nothing
/// here changes the threshold. It records what a threshold would have had to decide, so
/// that the next change is made against a distribution instead of against one commute.
///
/// The two counts bracket the interesting region rather than describing it fully: a
/// histogram of every gap would say more and would also mean carrying an array per session
/// for a number nobody reads until the retune. The whole distribution is recoverable from
/// the events themselves once a trace is copied off the device; these three numbers are
/// what makes the aggregate cheap enough to show on the diagnostics screen.
struct TraceGapStats: Sendable, Equatable, Codable {
    /// Milliseconds. `0` for a session with fewer than two events — no gap was observed,
    /// which is not the same as a short one, and the viability rule discards those anyway.
    var maxGapMillis: Int64 = 0
    var gapsOver10MinCount = 0
    var gapsOver20MinCount = 0

    static let tenMinutesMillis: Int64 = 10 * 60 * 1000
    static let twentyMinutesMillis: Int64 = 20 * 60 * 1000

    static let empty = TraceGapStats()

    /// Folds in the silence between the previous event and the one being appended.
    ///
    /// Called once per appended event, which is what keeps this off the battery budget:
    /// three integer comparisons on a path that was already writing the event.
    /// A negative delta cannot arrive — the recorder's watermark refuses any event not
    /// strictly newer than the last one — but it is clamped rather than trusted, because a
    /// negative maximum would read as "no gap observed" and quietly poison the aggregate.
    mutating func record(gapMillis: Int64) {
        let gap = max(0, gapMillis)
        maxGapMillis = max(maxGapMillis, gap)
        if gap > Self.tenMinutesMillis {
            gapsOver10MinCount += 1
        }
        if gap > Self.twentyMinutesMillis {
            gapsOver20MinCount += 1
        }
    }

    /// Measures a finished event list in one pass.
    ///
    /// Used where there is no append to hang the increment off: restoring a session after
    /// process death, and splitting one into fragments whose gaps are a subset of the
    /// parent's. Both are rare and bounded by `maximumEvents`.
    init(events: [TraceEvent]) {
        for (previous, next) in zip(events, events.dropFirst()) {
            record(gapMillis: next.atMillis - previous.atMillis)
        }
    }

    init() {}
}

/// Where a session came from when a human cut a longer one in two (docs/05 §9
/// "사람이 세션을 나눈다").
///
/// The device cannot tell "sitting in the office" from "travelling" — both emit motion
/// edges, and the September 2026 traces put the silence between them within a minute of the
/// boundary. A person can tell, so the split is theirs to make; this records that they made
/// it. Both fragments carry the *same* value, so the pair is recoverable from either half
/// long after the parent file has been evicted.
struct TraceSplitOrigin: Sendable, Equatable, Codable {
    let parentSessionId: UUID
    /// The time of the first event of the second fragment — the cut itself, not a gap.
    let atMillis: Int64
}

/// One recorded session, in the exact shape docs/05 §9 fixed.
///
/// **Never an encoding of a live detection type.** Like `DiagnosticsReport`, every field
/// is listed by hand and the coordinate-bearing ones are structurally absent — `TraceEvent`
/// has no place to put one. This file is copied off the device by design; that is its
/// reason to exist, and it is also why a latitude reaching it would turn the whole
/// recording into the parking-location log CLAUDE.md's Hard Constraints forbid. A test on
/// the encoded bytes enforces it.
///
/// `endedAt` is non-optional and is rewritten to the newest event's time on every append,
/// so a file left behind by process death is already complete rather than needing a
/// repair pass on the next launch.
struct TraceSession: Sendable, Equatable, Codable, Identifiable {
    static let schemaVersion = 1

    /// `sessionId` is already the identity §9 gives a session; this only spells it the way
    /// SwiftUI asks for it.
    var id: UUID {
        sessionId
    }

    var schemaVersion: Int = TraceSession.schemaVersion
    let sessionId: UUID
    let platform: String
    let deviceModel: String
    let osVersion: String
    let appVersion: String
    let startedAt: Int64
    var endedAt: Int64
    var label: TraceLabel
    var events: [TraceEvent]
    /// Optional because *absent* and *zero* are different claims, and the aggregate this
    /// feeds cannot afford to confuse them. Traces recorded before gap measurement landed
    /// were never measured; reading them as "no gap over 10 minutes" would understate
    /// exactly the tail the retune is looking for. A session written from now on always
    /// carries one.
    var gapStats: TraceGapStats?
    /// Present only on a fragment a human cut out of a longer session.
    var splitFrom: TraceSplitOrigin?

    init(
        sessionId: UUID,
        metadata: TraceDeviceMetadata,
        startedAt: Date,
        endedAt: Date,
        label: TraceLabel = .unlabeled,
        events: [TraceEvent] = [],
        gapStats: TraceGapStats? = nil,
        splitFrom: TraceSplitOrigin? = nil
    ) {
        self.sessionId = sessionId
        platform = metadata.platform
        deviceModel = metadata.deviceModel
        osVersion = metadata.osVersion
        appVersion = metadata.appVersion
        self.startedAt = startedAt.traceMillis
        self.endedAt = endedAt.traceMillis
        self.label = label
        self.events = events
        self.gapStats = gapStats
        self.splitFrom = splitFrom
    }

    /// The flat §9 fields read back as the value they were written from, so a fragment can
    /// be built carrying the same device and build as the session it was cut out of.
    var metadata: TraceDeviceMetadata {
        TraceDeviceMetadata(
            platform: platform,
            deviceModel: deviceModel,
            osVersion: osVersion,
            appVersion: appVersion
        )
    }

    var startDate: Date {
        Date(traceMillis: startedAt)
    }

    var endDate: Date {
        Date(traceMillis: endedAt)
    }
}

/// A session reduced to what the labelling list shows, so the screen never has to hold
/// every event of every session in memory.
struct TraceSessionSummary: Sendable, Equatable, Identifiable {
    let id: UUID
    let startedAt: Date
    let endedAt: Date
    let eventCount: Int
    let label: TraceLabel
    let gapStats: TraceGapStats?
    let splitFrom: TraceSplitOrigin?

    var duration: TimeInterval {
        endedAt.timeIntervalSince(startedAt)
    }

    /// A fragment is already the result of one human judgement, so the labelling screen
    /// says so rather than presenting it as something the device recorded whole.
    var isSplitFragment: Bool {
        splitFrom != nil
    }

    init(_ session: TraceSession) {
        id = session.sessionId
        startedAt = session.startDate
        endedAt = session.endDate
        eventCount = session.events.count
        label = session.label
        gapStats = session.gapStats
        splitFrom = session.splitFrom
    }
}

/// The numbers that answer "is recording working?" — and now "what would a different idle
/// gap have done?" — without retrieving a single trace (docs/05 §9).
///
/// Both drop counters are here because they fail in opposite directions and a single total
/// would hide which one is happening: `droppedSessionCount` climbing means the rolling cap
/// is evicting trips before anyone labelled them, while `nonViableDropCount` climbing means
/// the boundary is manufacturing single-event sessions and the threshold is wrong.
///
/// The gap aggregate describes the sessions **still on disk**. Eviction takes a session's
/// gaps with it, which is the right reading for a screen that answers "what is currently
/// recorded" — the durable copy of the distribution is the trace files themselves, each
/// carrying its own `gapStats`, retrieved over the same `devicectl copy` path.
struct TraceSummary: Sendable, Equatable, Codable {
    var sessionCount = 0
    var eventCount = 0
    /// Sessions evicted by the rolling cap since install. Persisted, because the eviction
    /// that matters happens in a background process nobody is watching.
    var droppedSessionCount = 0
    /// Sessions discarded at rotation for holding one event or none (§9 "비생존 세션은
    /// 버린다"). Persisted for the same reason, and never folded into the count above.
    var nonViableDropCount = 0
    var unlabeledSessionCount = 0
    /// Sessions carrying a measurement. Below `sessionCount` only while traces recorded
    /// before gap measurement landed are still on disk.
    var measuredSessionCount = 0
    /// The largest silence inside any retained session, in milliseconds.
    var maxGapMillis: Int64 = 0
    var sessionsOver10MinGapCount = 0
    var sessionsOver20MinGapCount = 0

    static let empty = TraceSummary()
}

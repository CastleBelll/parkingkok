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
struct TraceSession: Sendable, Equatable, Codable {
    static let schemaVersion = 1

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

    init(
        sessionId: UUID,
        metadata: TraceDeviceMetadata,
        startedAt: Date,
        endedAt: Date,
        label: TraceLabel = .unlabeled,
        events: [TraceEvent] = []
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

    var duration: TimeInterval {
        endedAt.timeIntervalSince(startedAt)
    }

    init(_ session: TraceSession) {
        id = session.sessionId
        startedAt = session.startDate
        endedAt = session.endDate
        eventCount = session.events.count
        label = session.label
    }
}

/// The four numbers that answer "is recording working?" without retrieving a single trace
/// (docs/05 §9 rolling cap; the dropped count is the part a silent eviction would hide).
struct TraceSummary: Sendable, Equatable, Codable {
    var sessionCount = 0
    var eventCount = 0
    /// Sessions evicted by the rolling cap since install. Persisted, because the eviction
    /// that matters happens in a background process nobody is watching.
    var droppedSessionCount = 0
    var unlabeledSessionCount = 0

    static let empty = TraceSummary()
}

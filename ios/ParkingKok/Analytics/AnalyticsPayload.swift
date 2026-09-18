import Foundation

/// A value an analytics parameter may hold.
///
/// **Three cases, and `Double` is deliberately not one of them.** A latitude is a `Double`;
/// with no case to put one in, docs/17 §3's forbidden coordinates cannot reach a parameter
/// even through a mistake in the mapper below. Buckets exist precisely so that no
/// continuous quantity ever needs to travel.
enum AnalyticsValue: Sendable, Equatable {
    case string(String)
    case int(Int)
    case bool(Bool)
}

/// One event flattened into the `(name, parameters)` shape every analytics transport
/// speaks — and the only thing an `AnalyticsSending` adapter ever sees.
///
/// **The memberwise initialiser is private: a payload can be built only from an
/// `AnalyticsEvent`.** That is docs/07's "no `logEvent(name, params)`" expressed in code —
/// there is no seam anywhere between a call site and Firebase where a key could be
/// invented, so the day someone wants to attach a floor there is nothing to attach it to.
struct AnalyticsPayload: Sendable, Equatable {
    let name: String
    let parameters: [String: AnalyticsValue]
    /// Transport metadata from the injected clock, and deliberately **not** a parameter:
    /// docs/17 §3 fixes the allowed property list and a timestamp is not on it. An adapter
    /// may use this for ordering or backdating; it never becomes an event property.
    let occurredAt: Date

    private init(name: String, parameters: [String: AnalyticsValue], occurredAt: Date) {
        self.name = name
        self.parameters = parameters
        self.occurredAt = occurredAt
    }

    init(_ event: AnalyticsEvent, occurredAt: Date) {
        var parameters: [String: AnalyticsValue] = [Key.platform: .string(Self.platform)]
        switch event {
        case .onboardingCompleted,
             .parkingManualSaved,
             .paywallViewed,
             .referralShared,
             .referralRedeemed:
            // docs/17 §3 allows these nothing beyond `platform`, so they say nothing else.
            break
        case let .permissionMotionResult(result):
            parameters[Key.result] = .string(result.rawValue)
        case let .permissionLocationLevel(level):
            parameters[Key.level] = .string(level.rawValue)
        case let .smartDetectionEnabled(enabled):
            parameters[Key.enabled] = .bool(enabled)
        case let .parkingCandidateCreated(properties),
             let .parkingCandidateConfirmed(properties),
             let .parkingCandidateRejected(properties):
            Self.merge(properties, into: &parameters)
        case .parkingAutoEnd:
            // No confidence to report — an auto end is not a candidate — but docs/17 §4
            // wants the engine version behind it, so the constant is stamped here.
            parameters[Key.detectorVersion] = .int(DetectorVersion.current)
        case let .widgetFloorChanged(direction):
            parameters[Key.direction] = .string(direction.rawValue)
        case let .purchaseCompleted(store):
            parameters[Key.store] = .string(store.rawValue)
        }
        self.init(name: event.name, parameters: parameters, occurredAt: occurredAt)
    }

    /// Stable rendering for the debug sink and for failure messages. Keys sorted so two
    /// runs of the same event read identically.
    var debugSummary: String {
        let rendered = parameters.keys.sorted().map { key in
            "\(key)=\(Self.describe(parameters[key]))"
        }
        return "\(name) {\(rendered.joined(separator: ", "))}"
    }

    private static func merge(_ properties: DetectionProperties, into parameters: inout [String: AnalyticsValue]) {
        parameters[Key.confidenceBucket] = .string(properties.confidenceBucket.rawValue)
        parameters[Key.driveDurationBucket] = properties.driveDurationBucket.map { .string($0.rawValue) }
        parameters[Key.distanceBucket] = properties.distanceBucket.map { .string($0.rawValue) }
        parameters[Key.accuracyBucket] = properties.accuracyBucket.map { .string($0.rawValue) }
        parameters[Key.walkingEvidence] = .bool(properties.walkingEvidence)
        parameters[Key.gpsDegradation] = .bool(properties.gpsDegradation)
        parameters[Key.optionalVehicleSignal] = .bool(properties.optionalVehicleSignal)
        parameters[Key.detectorVersion] = .int(properties.detectorVersion)
    }

    private static func describe(_ value: AnalyticsValue?) -> String {
        switch value {
        case let .string(text): text
        case let .int(number): String(number)
        case let .bool(flag): String(flag)
        case nil: "nil"
        }
    }

    /// docs/17 §2: "Properties always include `platform` where useful."
    static let platform = "ios"

    /// Every parameter key this contract can produce.
    ///
    /// `snake_case` rather than docs/17 §3's camelCase field names because Firebase
    /// Analytics restricts parameter names to letters, digits and underscores; the two
    /// platforms must agree on these strings, so they are listed rather than derived.
    enum Key {
        static let platform = "platform"
        static let result = "result"
        static let level = "level"
        static let enabled = "enabled"
        static let direction = "direction"
        static let store = "store"
        static let confidenceBucket = "confidence_bucket"
        static let driveDurationBucket = "drive_duration_bucket"
        static let distanceBucket = "distance_bucket"
        static let accuracyBucket = "accuracy_bucket"
        static let walkingEvidence = "walking_evidence"
        static let gpsDegradation = "gps_degradation"
        static let optionalVehicleSignal = "optional_vehicle_signal"
        static let detectorVersion = "detector_version"

        /// The allowlist, so a test can hold every payload against it rather than trusting
        /// a reading of the `switch` above.
        static let all: Set<String> = [
            platform, result, level, enabled, direction, store,
            confidenceBucket, driveDurationBucket, distanceBucket, accuracyBucket,
            walkingEvidence, gpsDegradation, optionalVehicleSignal, detectorVersion
        ]
    }
}

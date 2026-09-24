import Foundation

/// The logical version of the detection engine's scoring and rules (docs/17 §4).
///
/// Bumped by hand whenever a weight or rule changes, so rejection rates from two builds
/// stay comparable without any location telemetry. It is the only thing that makes the
/// detection family of events comparable over time at all.
enum DetectorVersion {
    /// Never derived from the app version: a release that does not touch the engine must
    /// not look like an engine change, and two releases that share an engine must not look
    /// like two engines.
    static let current = 1
}

/// Coarse drive-duration band — docs/17 §3 `driveDurationBucket`.
///
/// **The edges are literals and stay that way**, for the reason `LocationAccuracyBucket`
/// spells out: a band bound to a tunable threshold would retroactively change what an
/// already-reported event meant, and a reporting format has to stay comparable over time.
enum DriveDurationBucket: String, Sendable, Equatable, CaseIterable {
    case under5Min = "under_5_min"
    case min5To15 = "min_5_15"
    case min15To45 = "min_15_45"
    case over45Min = "over_45_min"

    static let shortMaximumSeconds: TimeInterval = 5 * 60
    static let mediumMaximumSeconds: TimeInterval = 15 * 60
    static let longMaximumSeconds: TimeInterval = 45 * 60

    /// `nil` for a negative duration. A clock that ran backwards is a defect; giving it a
    /// band would hide it inside a healthy-looking distribution.
    init?(seconds: TimeInterval) {
        guard seconds >= 0 else { return nil }
        switch seconds {
        case ..<Self.shortMaximumSeconds: self = .under5Min
        case ..<Self.mediumMaximumSeconds: self = .min5To15
        case ..<Self.longMaximumSeconds: self = .min15To45
        default: self = .over45Min
        }
    }
}

/// Coarse straight-line distance band — docs/17 §3 `distanceBucket`.
///
/// Fixed edges, same reasoning as `DriveDurationBucket`.
enum DistanceBucket: String, Sendable, Equatable, CaseIterable {
    case under1Km = "under_1_km"
    case km1To5 = "km_1_5"
    case km5To20 = "km_5_20"
    case over20Km = "over_20_km"

    static let shortMaximumMeters: Double = 1000
    static let mediumMaximumMeters: Double = 5000
    static let longMaximumMeters: Double = 20000

    /// `nil` for a negative distance, for the reason `DriveDurationBucket` gives.
    init?(meters: Double) {
        guard meters >= 0 else { return nil }
        switch meters {
        case ..<Self.shortMaximumMeters: self = .under1Km
        case ..<Self.mediumMaximumMeters: self = .km1To5
        case ..<Self.longMaximumMeters: self = .km5To20
        default: self = .over20Km
        }
    }
}

/// The cross-platform shape of a motion-permission outcome.
///
/// docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §4: never ship a platform SDK enum as a
/// product code. `CMAuthorizationStatus` and Android's `ACTIVITY_RECOGNITION` grant both
/// map onto this, which is what makes the two platforms' numbers addable.
enum MotionPermissionResult: String, Sendable, Equatable, CaseIterable {
    case granted
    case denied
    case restricted
    case notDetermined = "not_determined"
}

/// The cross-platform shape of a location-permission level, same contract as
/// `MotionPermissionResult`.
enum LocationPermissionLevel: String, Sendable, Equatable, CaseIterable {
    case always
    case whenInUse = "when_in_use"
    case denied
    case restricted
    case notDetermined = "not_determined"
}

/// Which way the widget's `-` / `+` moved the floor.
///
/// The direction, never the floor: docs/17 §3 forbids `floor` outright.
enum WidgetFloorDirection: String, Sendable, Equatable, CaseIterable {
    case up
    case down
}

/// Which store took the money. docs/17 §7 counts new paid conversions by store.
enum PurchaseStore: String, Sendable, Equatable, CaseIterable {
    case appStore = "app_store"
    case playStore = "play_store"
}

/// Everything docs/17 §3 permits an event to say about a detection, and nothing else.
///
/// **This struct is the privacy boundary.** §3's forbidden list — lat/lon, route,
/// address/business/place, floor/spot/memo — has no member here and no free-form
/// dictionary to fall back on, so there is nowhere for a caller to put one. That is what
/// docs/07 means by "호출자의 주의가 아니라 타입으로" blocked.
struct DetectionProperties: Sendable, Equatable {
    /// The engine's external confidence contract (docs/05 §5).
    var confidenceBucket: ConfidenceBucket
    /// `nil` when the trip's duration is unknown — an absent property is honest, a
    /// defaulted bucket is not.
    var driveDurationBucket: DriveDurationBucket?
    var distanceBucket: DistanceBucket?
    /// Reuses the recorded-trace bucket (good/fair/poor) rather than defining a third
    /// good/fair/poor: docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2 fixed those edges.
    var accuracyBucket: LocationAccuracyBucket?
    var walkingEvidence: Bool
    var gpsDegradation: Bool
    var optionalVehicleSignal: Bool
    /// Carried rather than stamped at send time, so replaying an older run reports the
    /// version that produced it.
    var detectorVersion: Int = DetectorVersion.current
}

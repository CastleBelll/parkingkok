import Foundation

/// Which kind of car link a `carLinkConnected` / `carLinkDisconnected` event describes
/// (docs/05_PARKING_DETECTION_ENGINE.md §3a "The car link").
///
/// The kind is carried but never becomes a reason code: §4 is closed and
/// `car_projection_disconnected` already says the product-relevant thing — the phone was
/// attached to a car and stopped being attached. §3a puts the *kind* in §8 weighting, not
/// in a new string.
enum CarLinkKind: String, Sendable, Equatable, Codable, CaseIterable {
    /// CarPlay on iOS, Android Auto on Android.
    case projection
    /// The car's audio system, over Bluetooth.
    case bluetoothAudio
}

/// The normalized event vocabulary from `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §2.
///
/// SDK types never appear here: Core Motion and Core Location are mapped down at the
/// adapter boundary, which is what lets the same engine be driven by a real device, by a
/// unit test and by the JSON parity fixtures in `platform-tests/` (§8).
///
/// ### Enter/exit are levels, not samples
/// `vehicleEnter` means the device entered vehicle activity **and stays there until
/// `vehicleExit` arrives**. That is what the §2 wire table's enter/exit symmetry means and
/// it is how Android's Transition API delivers it. iOS polls Core Motion history instead,
/// so `BackgroundCoordinator` is responsible for deriving the exit edge — from a newer
/// non-automotive sample, or from the evidence going silent. An engine that expired
/// vehicle evidence on its own clock could never replay a fixture whose only vehicle
/// events are one enter and one exit 45 minutes apart, which is exactly what
/// `subway_commute_underground.json` is.
enum DetectionEvent: Sendable, Equatable {
    case vehicleEnter(at: Date, confidence: MotionConfidence? = nil)
    case vehicleExit(at: Date, confidence: MotionConfidence? = nil)
    case walkingEnter(at: Date, confidence: MotionConfidence? = nil)
    case stationaryEnter(at: Date, confidence: MotionConfidence? = nil)
    case stationaryExit(at: Date, confidence: MotionConfidence? = nil)
    /// One fix from the bounded driving session. The only event that carries a coordinate,
    /// and it never leaves the device (`LocationFix`).
    case location(LocationFix)
    /// The buckets are optional because a fixture may record the degradation without them
    /// (`tunnel_no_parking.json` does); §13 makes this supporting evidence either way.
    case locationQualityDegraded(at: Date, from: LocationAccuracyBucket? = nil, to: LocationAccuracyBucket? = nil)
    case carLinkConnected(at: Date, kind: CarLinkKind)
    case carLinkDisconnected(at: Date, kind: CarLinkKind)
    /// §2's `TimerTick`. Carries no evidence: it only lets the lazily-evaluated windows in
    /// §3a be re-examined when no other event is arriving.
    case timerTick(at: Date)
    case userConfirmedParking(at: Date)
    case userRejectedParking(at: Date)

    /// When the event says it happened — never when the engine got round to it. Expiry and
    /// every §3a window are measured from this, so a wake minutes late must not buy the
    /// user extra minutes.
    var timestamp: Date {
        switch self {
        case let .vehicleEnter(at, _),
             let .vehicleExit(at, _),
             let .walkingEnter(at, _),
             let .stationaryEnter(at, _),
             let .stationaryExit(at, _),
             let .locationQualityDegraded(at, _, _),
             let .carLinkConnected(at, _),
             let .carLinkDisconnected(at, _),
             let .timerTick(at),
             let .userConfirmedParking(at),
             let .userRejectedParking(at):
            at
        case let .location(fix):
            fix.timestamp
        }
    }
}

import Foundation

/// The wire vocabulary of a recorded trace
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9, reusing §2's normalized events and §8's
/// fixture vocabulary verbatim).
///
/// Raw strings are the contract, so they are written out rather than derived from the
/// case names. A platform SDK enum must never reach this type: §9 forbids it, and a trace
/// whose vocabulary drifts from the fixture vocabulary cannot be converted into one.
enum TraceEventType: String, Sendable, Equatable, Codable, CaseIterable {
    case vehicleEnter = "vehicle_enter"
    case vehicleExit = "vehicle_exit"
    case walkingEnter = "walking_enter"
    case stationaryEnter = "stationary_enter"
    case stationaryExit = "stationary_exit"
    case location
    case locationQualityDegraded = "location_quality_degraded"
}

/// Coarse horizontal-accuracy band, the only form in which location quality is recorded.
///
/// **The thresholds are literals on purpose, and must stay that way.** They look like
/// `ReliableLocationPolicy.maximumHorizontalAccuracy` by coincidence only. That value is
/// a detection threshold destined for remote tuning
/// (docs/03_SYSTEM_ARCHITECTURE.md §10 `detector.reliableAccuracyMeters`); binding the
/// buckets to it would retroactively change what an already-recorded trace *means* the
/// moment the threshold moved, and a recording format has to stay comparable over time.
///
/// There is deliberately no bucket for a negative accuracy. docs/05 §5 calls such a fix
/// invalid and `LocationQualitySample.isValid` / `LocationFix.isValid` reject it at the
/// adapter boundary; one reaching the recorder is an adapter defect, and giving it a
/// bucket would swallow the defect instead of leaving it visible as a `location` event
/// with a negative accuracy and no quality transition.
enum LocationAccuracyBucket: String, Sendable, Equatable, Codable, CaseIterable, Comparable {
    case good
    case fair
    case poor

    static let goodMaximumMeters: Double = 20
    static let fairMaximumMeters: Double = 35

    /// `nil` for an invalid (negative) accuracy — see the type's note.
    init?(horizontalAccuracy: Double) {
        guard horizontalAccuracy >= 0 else { return nil }
        switch horizontalAccuracy {
        case ...Self.goodMaximumMeters: self = .good
        case ...Self.fairMaximumMeters: self = .fair
        default: self = .poor
        }
    }

    private var rank: Int {
        switch self {
        case .good: 0
        case .fair: 1
        case .poor: 2
        }
    }

    /// Ordered best-first, so `>` reads as "worse quality than".
    static func < (lhs: LocationAccuracyBucket, rhs: LocationAccuracyBucket) -> Bool {
        lhs.rank < rhs.rank
    }
}

/// One recorded observation, in the exact shape docs/05 §9 fixed.
///
/// **A flat struct with optional fields rather than an enum with associated values.** The
/// contract is a JSON object per event whose keys depend on `type`; Swift's synthesized
/// encoder already omits `nil` optionals, so this shape round-trips the contract without
/// a hand-written coder to keep in sync with it.
///
/// **There is no coordinate member, and adding one is never the fix for anything.** §9
/// bans coordinates from the trace because the file's whole purpose is to be copied off
/// the device; a latitude here turns every recorded trip into a parking-location log.
/// `distanceFromPreviousM` is the derived value that survives instead — see
/// `TraceRecorder`, which holds the previous fix in memory only and stores the metres.
struct TraceEvent: Sendable, Equatable, Codable {
    let type: TraceEventType
    /// Absolute epoch milliseconds. Conversion to a fixture's relative `t` happens in the
    /// converter, not here (§9 "변환").
    let atMillis: Int64
    let confidence: MotionConfidence?
    /// Metres.
    let accuracy: Double?
    /// Metres per second.
    let speed: Double?
    /// Metres travelled since the previous recorded `location` event, summed over the
    /// fixes that were downsampled away. Never a position.
    let distanceFromPreviousM: Double?
    let fromBucket: LocationAccuracyBucket?
    let toBucket: LocationAccuracyBucket?

    private init(
        type: TraceEventType,
        at date: Date,
        confidence: MotionConfidence? = nil,
        accuracy: Double? = nil,
        speed: Double? = nil,
        distanceFromPreviousM: Double? = nil,
        fromBucket: LocationAccuracyBucket? = nil,
        toBucket: LocationAccuracyBucket? = nil
    ) {
        self.type = type
        atMillis = date.traceMillis
        self.confidence = confidence
        self.accuracy = accuracy
        self.speed = speed
        self.distanceFromPreviousM = distanceFromPreviousM
        self.fromBucket = fromBucket
        self.toBucket = toBucket
    }

    static func motion(_ type: TraceEventType, at date: Date, confidence: MotionConfidence?) -> TraceEvent {
        TraceEvent(type: type, at: date, confidence: confidence)
    }

    static func location(
        at date: Date,
        accuracy: Double,
        speed: Double? = nil,
        distanceFromPreviousM: Double? = nil
    ) -> TraceEvent {
        TraceEvent(
            type: .location,
            at: date,
            accuracy: accuracy,
            speed: speed,
            distanceFromPreviousM: distanceFromPreviousM
        )
    }

    static func qualityDegraded(
        at date: Date,
        from: LocationAccuracyBucket,
        to: LocationAccuracyBucket
    ) -> TraceEvent {
        TraceEvent(type: .locationQualityDegraded, at: date, fromBucket: from, toBucket: to)
    }

    var date: Date {
        Date(traceMillis: atMillis)
    }
}

/// §9 records absolute time as epoch milliseconds, not ISO-8601 — the same unit Android
/// gets from `System.currentTimeMillis()`, so both platforms write the same number.
extension Date {
    var traceMillis: Int64 {
        Int64((timeIntervalSince1970 * 1000).rounded())
    }

    init(traceMillis: Int64) {
        self.init(timeIntervalSince1970: Double(traceMillis) / 1000)
    }
}

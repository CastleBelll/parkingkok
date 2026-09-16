import Foundation

/// One fix from the bounded driving session
/// (docs/05_PARKING_DETECTION_ENGINE.md §5 "Location Sample Validation").
///
/// **This is the one domain type that carries a coordinate.** The significant-change
/// path (`LocationQualitySample`) deliberately drops it, because that path only needs to
/// know *that* the device moved. Selecting `lastReliableLocation` is impossible without
/// a coordinate (docs/05 §6), so the bounded session carries one — and only in memory
/// and in the checkpoint file.
///
/// The privacy boundary therefore moves from "the coordinate does not exist" to "the
/// coordinate never reaches an exportable surface": `DiagnosticsReport` is a hand-listed
/// projection with a test on the encoded bytes, `AppLog` call sites take accuracy and
/// timestamps only, and nothing here is uploaded (CLAUDE.md Hard Constraints,
/// docs/00_CORE_RULES.md Privacy).
struct LocationFix: Sendable, Equatable {
    let timestamp: Date
    let latitude: Double
    let longitude: Double
    /// Metres. Core Location reports a negative value when the fix is invalid.
    let horizontalAccuracy: Double
    /// Metres per second, or `nil` when Core Location had no estimate. Core Location's
    /// own sentinel (a negative value) is normalized away at the adapter boundary so no
    /// downstream comparison has to know about it.
    let speed: Double?

    init(
        timestamp: Date,
        latitude: Double,
        longitude: Double,
        horizontalAccuracy: Double,
        speed: Double? = nil
    ) {
        self.timestamp = timestamp
        self.latitude = latitude
        self.longitude = longitude
        self.horizontalAccuracy = horizontalAccuracy
        self.speed = speed
    }

    /// docs/05 §5: "negative accuracy invalid".
    var isValid: Bool {
        horizontalAccuracy >= 0
    }

    /// The durable form, once `ReliableLocationPolicy` has accepted this fix.
    var reliableLocation: LastReliableLocation {
        LastReliableLocation(
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy,
            capturedAt: timestamp
        )
    }
}

/// Great-circle distance between two fixes.
///
/// Kept in the domain rather than delegating to `CLLocation.distance(from:)` so the
/// driving-distance accumulator stays a pure function the tests can drive without
/// CoreLocation (docs/16_CODING_STANDARDS.md §1: SDK types live at the adapter boundary).
///
/// Haversine on a spherical earth. Error against the WGS-84 ellipsoid is well under
/// 0.5%, which is far inside the tolerance of an 800 m travel threshold.
enum GeoDistance {
    private static let earthRadiusMeters = 6_371_000.0

    static func meters(from origin: LocationFix, to destination: LocationFix) -> Double {
        let lat1 = origin.latitude * .pi / 180
        let lat2 = destination.latitude * .pi / 180
        let deltaLat = (destination.latitude - origin.latitude) * .pi / 180
        let deltaLon = (destination.longitude - origin.longitude) * .pi / 180

        let haversine = sin(deltaLat / 2) * sin(deltaLat / 2)
            + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * earthRadiusMeters * atan2(sqrt(haversine), sqrt(max(0, 1 - haversine)))
    }
}

/// docs/05_PARKING_DETECTION_ENGINE.md §5: "impossible speed/distance outliers rejected".
///
/// A GPS jump between two fixes inflates `travelDistanceEstimate`, and that estimate is
/// one half of the driving confirmation guard (§7). Without this, a single bad fix in a
/// tunnel could confirm a drive that never happened.
enum LocationOutlierPolicy {
    /// ~324 km/h. Above this the *pair* is implausible for a car, so the newer fix is
    /// treated as a jump rather than as travel. A field-tuning starting point in the same
    /// spirit as the §8 evidence weights, not a value the spec derives.
    static let maximumPlausibleSpeed: Double = 90

    static func isPlausibleStep(from previous: LocationFix, to current: LocationFix) -> Bool {
        let elapsed = current.timestamp.timeIntervalSince(previous.timestamp)
        // Same instant or time running backwards: no travel can be attributed either way,
        // so treat the step as implausible rather than dividing by zero.
        guard elapsed > 0 else { return false }
        return GeoDistance.meters(from: previous, to: current) / elapsed <= maximumPlausibleSpeed
    }
}

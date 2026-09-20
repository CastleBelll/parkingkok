import CoreLocation
import Foundation

/// What the detail screen is allowed to draw and say about where the car is (FR-008).
///
/// A value, not a view concern, because the honest-wording rule lives here: FR-008 and
/// docs/04 §9 permit `마지막으로 확인된 위치` and forbid anything that claims the exact
/// car position. Underground, `horizontalAccuracy` is routinely 40–100m, and a pin drawn
/// without its circle would be a lie the UI told for free.
///
/// Failing to build (`init?` returning `nil`) is the FR-001 case — a parking saved with
/// no location permission — and is what disables 길찾기 rather than sending the user to
/// a map of the null island.
struct ParkingMapPoint: Equatable, Sendable {
    /// FR-008's mandated wording. The one place it is spelled, so a screen cannot drift
    /// into `차량 정확한 위치`.
    static let label = "마지막으로 확인된 위치"

    /// The accuracy circle should sit inside the card with room around it.
    static let spanMultiplier: Double = 6
    /// Below this the map is zoomed past anything recognisable — a 3m-accuracy fix would
    /// otherwise frame a single doorway with no street to orient by.
    static let minimumSpanMeters: Double = 120
    /// A fix this vague says "somewhere in this neighbourhood"; framing it any wider
    /// stops being a map of where you parked.
    static let maximumSpanMeters: Double = 4000

    let latitude: Double
    let longitude: Double
    /// Metres. Always shown — it is what makes the pin honest.
    let accuracyMeters: Double
    let capturedAt: Date

    /// `nil` when the record has no location, or carries one no map can draw.
    init?(_ session: ParkingSession) {
        guard let location = session.location else { return nil }
        self.init(
            latitude: location.latitude,
            longitude: location.longitude,
            accuracyMeters: location.horizontalAccuracy,
            capturedAt: location.capturedAt
        )
    }

    /// The fix a candidate carries, which is what the confirmation screen has to draw from
    /// — there is no record yet for it to come off (docs/10 §7a "where").
    init?(_ location: LastReliableLocation) {
        self.init(
            latitude: location.latitude,
            longitude: location.longitude,
            accuracyMeters: location.horizontalAccuracy,
            capturedAt: location.capturedAt
        )
    }

    /// The one guard both sources go through. A corrupt or partially-written value reaches
    /// MapKit as NaN and takes the process with it, so it is refused here and the caller
    /// falls back to the no-location treatment.
    private init?(latitude: Double, longitude: Double, accuracyMeters: Double, capturedAt: Date) {
        let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        guard CLLocationCoordinate2DIsValid(coordinate),
              accuracyMeters.isFinite,
              accuracyMeters > 0
        else {
            return nil
        }
        self.latitude = latitude
        self.longitude = longitude
        self.accuracyMeters = accuracyMeters
        self.capturedAt = capturedAt
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// Edge length of the camera region, wide enough for the whole accuracy circle.
    var spanMeters: Double {
        min(max(accuracyMeters * Self.spanMultiplier, Self.minimumSpanMeters), Self.maximumSpanMeters)
    }

    /// `약 18m 이내` — the accuracy said out loud, which is the honest version of a pin.
    var accuracyText: String {
        "약 \(Int(accuracyMeters.rounded()))m 이내"
    }

    /// `마지막으로 확인된 위치 · 약 18m 이내`, the caption the map card carries.
    var captionText: String {
        "\(Self.label) · \(accuracyText)"
    }
}

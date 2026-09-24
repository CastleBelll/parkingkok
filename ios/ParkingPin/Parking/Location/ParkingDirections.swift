import Foundation
import UIKit

/// `길찾기` — hands the last reliable location to Apple Maps.
///
/// **This is the one place a coordinate deliberately leaves the app, and it leaves it to
/// the user.** CLAUDE.md's constraint is that coordinates never reach Firebase, logs,
/// analytics or a crash field; a walking route the user explicitly asked for is the
/// opposite of that — it is the feature. Nothing is recorded on the way out.
///
/// Apple Maps' URL scheme rather than `MKMapItem.openInMaps`: `MKMapItem(placemark:)` is
/// deprecated from iOS 26 and its replacement `init(location:address:)` needs iOS 26,
/// which would fork the code for a deployment target of 18. The documented URL is one
/// path on every supported version — and, unlike an `MKMapItem`, it is a pure value this
/// can be unit-tested on.
enum ParkingDirections {
    /// Walking, not driving. The user is on foot in a car park looking for their car;
    /// `dirflg=d` would route them around the one-way system to the entrance barrier.
    static let walkingDirectionsFlag = "w"

    /// The Apple Maps link for `point`, or `nil` if it cannot be formed.
    ///
    /// Coordinates are written with `Locale.invariant`-style formatting via
    /// `String(format:)` on a fixed locale, so a device set to a comma-decimal locale
    /// cannot produce `37,5` and send the user to the wrong continent.
    static func url(for point: ParkingMapPoint) -> URL? {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "maps.apple.com"
        components.path = "/"
        components.queryItems = [
            URLQueryItem(name: "daddr", value: "\(formatted(point.latitude)),\(formatted(point.longitude))"),
            URLQueryItem(name: "dirflg", value: walkingDirectionsFlag)
        ]
        return components.url
    }

    @MainActor
    static func open(_ point: ParkingMapPoint) {
        guard let url = url(for: point) else {
            // Nothing to recover from and nothing to say: the button is only offered
            // when a point exists, and the point's coordinate is already validated.
            AppLog.lifecycle.error("could not form a directions URL for a validated point")
            return
        }
        UIApplication.shared.open(url)
    }

    private static func formatted(_ degrees: Double) -> String {
        String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), degrees)
    }
}

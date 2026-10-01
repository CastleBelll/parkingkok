import Foundation
import UIKit

/// `길찾기` — hands the last reliable location to the map app the user picks.
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
    static func open(_ point: ParkingMapPoint, in app: MapApp = .apple) {
        guard let url = app.url(for: point) else {
            // Nothing to recover from and nothing to say: the button is only offered
            // when a point exists, and the point's coordinate is already validated.
            AppLog.lifecycle.error("could not form a directions URL for a validated point")
            return
        }
        UIApplication.shared.open(url)
    }

    /// The map apps `길찾기` offers, in the order they are listed.
    ///
    /// Apple Maps alone failed the user on the device (2026-10-01): it opened, and in Korea
    /// it often has no walking route to give, so the button read as one that did nothing.
    /// Kakao Map and Naver Map are what this user — and most users here — navigate with.
    /// Each is offered only when installed; Apple Maps always is.
    enum MapApp: String, CaseIterable, Identifiable, Sendable {
        case kakao
        case naver
        case apple

        var id: String { rawValue }

        var title: String {
            switch self {
            case .kakao: "카카오맵"
            case .naver: "네이버 지도"
            case .apple: "Apple 지도"
            }
        }

        /// The walking route to `point`, by each app's documented URL scheme.
        func url(for point: ParkingMapPoint) -> URL? {
            let latitude = formatted(point.latitude)
            let longitude = formatted(point.longitude)
            switch self {
            case .kakao:
                return URL(string: "kakaomap://route?ep=\(latitude),\(longitude)&by=FOOT")
            case .naver:
                var components = URLComponents()
                components.scheme = "nmap"
                components.host = "route"
                components.path = "/walk"
                components.queryItems = [
                    URLQueryItem(name: "dlat", value: latitude),
                    URLQueryItem(name: "dlng", value: longitude),
                    URLQueryItem(name: "dname", value: ParkingMapPoint.label),
                    URLQueryItem(name: "appname", value: Bundle.main.bundleIdentifier ?? "com.sjstudioz.parkingpin")
                ]
                return components.url
            case .apple:
                return ParkingDirections.url(for: point)
            }
        }

        /// Apple Maps is always offered; the others need their scheme in
        /// `LSApplicationQueriesSchemes` for `canOpenURL` to answer.
        @MainActor
        static var installed: [MapApp] {
            allCases.filter { app in
                switch app {
                case .apple: true
                case .kakao: UIApplication.shared.canOpenURL(URL(string: "kakaomap://")!)
                case .naver: UIApplication.shared.canOpenURL(URL(string: "nmap://")!)
                }
            }
        }
    }

    private static func formatted(_ degrees: Double) -> String {
        String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), degrees)
    }
}

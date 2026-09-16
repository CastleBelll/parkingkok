import CoreLocation

/// Core Location → domain mapping, kept at the adapter boundary
/// (docs/16_CODING_STANDARDS.md §1).
extension LocationAuthorization {
    init(_ status: CLAuthorizationStatus) {
        switch status {
        case .notDetermined: self = .notDetermined
        case .restricted: self = .restricted
        case .denied: self = .denied
        case .authorizedWhenInUse: self = .whenInUse
        case .authorizedAlways: self = .always
        @unknown default: self = .notDetermined
        }
    }
}

extension LocationQualitySample {
    /// Deliberately drops the coordinate.
    ///
    /// M0A-1 has no use for it — `lastReliableLocation` selection is M0A-2 — and not
    /// carrying it means no code path downstream can leak it into a log, an analytics
    /// event, or a crash field (CLAUDE.md Hard Constraints).
    init(_ location: CLLocation) {
        self.init(timestamp: location.timestamp, horizontalAccuracy: location.horizontalAccuracy)
    }
}

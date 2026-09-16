import Foundation

/// Location authorization, mapped off `CLAuthorizationStatus` at the adapter boundary
/// (docs/16_CODING_STANDARDS.md §1).
enum LocationAuthorization: String, Sendable, Codable, CaseIterable {
    case notDetermined
    case restricted
    case denied
    case whenInUse
    case always

    /// Significant-change monitoring only relaunches the app in the background under
    /// Always authorization (docs/04_IOS_IMPLEMENTATION.md §3 IDLE). When-In-Use is not
    /// enough, so the low-power trigger must not be started with it.
    var allowsSignificantLocationMonitoring: Bool {
        self == .always
    }

    /// The user has made a negative choice we cannot re-prompt out of; only Settings can
    /// change it. Not an app-wide failure — manual parking still works
    /// (CLAUDE.md Hard Constraints).
    var requiresSettingsChange: Bool {
        self == .denied || self == .restricted
    }
}

/// Motion authorization, mapped off `CMAuthorizationStatus`.
enum MotionAuthorization: String, Sendable, Codable, CaseIterable {
    case notDetermined
    case restricted
    case denied
    case authorized

    var allowsHistoryQuery: Bool {
        self == .authorized
    }
}

/// The location prompt it is appropriate to show next, if any.
enum LocationPermissionRequest: Sendable, Equatable {
    case whenInUse
    case always
}

/// When each location prompt may be raised.
///
/// docs/04_IOS_IMPLEMENTATION.md §4: Always is requested **contextually, after** the user
/// opts into Smart Detection — never on first launch. Keeping the rule here as a pure
/// function means it is unit-tested rather than buried in a view's `onAppear`.
enum PermissionRequestPolicy {
    static func nextRequest(
        location: LocationAuthorization,
        smartDetectionEnabled: Bool
    ) -> LocationPermissionRequest? {
        switch location {
        case .notDetermined:
            // iOS only offers Always after When-In-Use has been granted, so the ladder
            // always starts here regardless of the Smart Detection opt-in.
            .whenInUse
        case .whenInUse:
            smartDetectionEnabled ? .always : nil
        case .always, .denied, .restricted:
            nil
        }
    }

    /// Whether the low-power trigger should be running right now.
    static func shouldMonitorSignificantChanges(
        location: LocationAuthorization,
        smartDetectionEnabled: Bool
    ) -> Bool {
        smartDetectionEnabled && location.allowsSignificantLocationMonitoring
    }
}

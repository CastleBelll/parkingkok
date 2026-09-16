import Foundation

/// The local-only "last place we trusted the fix"
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §7).
///
/// Never leaves the device: not to Firebase, not to analytics, not to a log line
/// (CLAUDE.md Hard Constraints, docs/00_CORE_RULES.md Privacy).
///
/// **M0A-1 defines this field and never fills it.** Selecting a reliable fix needs the
/// bounded driving session and the accuracy/freshness policy from
/// `docs/05_PARKING_DETECTION_ENGINE.md` §6, which is M0A-2. Until then the
/// significant-change adapter maps `CLLocation` down to accuracy and timestamp only, so
/// no coordinate enters the app at all.
struct LastReliableLocation: Sendable, Equatable, Codable {
    let latitude: Double
    let longitude: Double
    /// Metres.
    let horizontalAccuracy: Double
    let capturedAt: Date
}

/// What a significant-change callback contributes in M0A-1: when it arrived and how good
/// the fix was. Coordinates are intentionally absent — see `LastReliableLocation`.
struct LocationQualitySample: Sendable, Equatable {
    let timestamp: Date
    /// Metres.
    let horizontalAccuracy: Double

    /// Core Location reports a negative accuracy when the fix is invalid
    /// (docs/05_PARKING_DETECTION_ENGINE.md §5).
    var isValid: Bool {
        horizontalAccuracy >= 0
    }
}

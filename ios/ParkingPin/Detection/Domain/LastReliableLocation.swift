import Foundation

/// The local-only "last place we trusted the fix"
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §7).
///
/// Never leaves the device: not to Firebase, not to analytics, not to a log line
/// (CLAUDE.md Hard Constraints, docs/00_CORE_RULES.md Privacy).
///
/// Filled only by `ReliableLocationPolicy` from a bounded-session `LocationFix`
/// (`docs/05_PARKING_DETECTION_ENGINE.md` §6). The significant-change path still maps
/// `CLLocation` down to accuracy and timestamp only (`LocationQualitySample`), so a
/// low-power wake never puts a coordinate in memory at all.
struct LastReliableLocation: Sendable, Equatable, Codable {
    let latitude: Double
    let longitude: Double
    /// Metres.
    let horizontalAccuracy: Double
    let capturedAt: Date
}

/// What a significant-change callback contributes: when it arrived and how good the fix
/// was. Coordinates are intentionally absent — that path only has to answer "did the
/// device move", and the fix it delivers is far too coarse and too late to be the parking
/// spot. The bounded session's `LocationFix` is the type that carries one.
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

/// How old a significant-change fix may be and still count as live evidence
/// (docs/05_PARKING_DETECTION_ENGINE.md §5, "stale sample excluded from live evidence").
///
/// Core Location hands over its cached last-known fix as soon as monitoring starts, and
/// that fix can be arbitrarily old: on the first real-device run it was 3h20m older than
/// the app install itself. Accuracy alone does not catch it — a cached fix is often a
/// *good* fix, just not a current one — so it passed `isValid` and was persisted as if
/// the user had just moved.
///
/// §6's 20s freshness — implemented in `ReliableLocationPolicy.maximumAge` — governs the
/// bounded driving session and is deliberately not reused here. A genuine significant
/// change can reach a suspended app minutes late through no fault of the fix, so this
/// bound only rejects samples that predate the wake by more than any plausible delivery
/// delay. The two bounds answer different questions on different paths: this one asks
/// "is this fix evidence that the device moved recently", the other asks "is this fix
/// good enough to remember as the parking spot".
///
/// The threshold is a starting default for field tuning, in the same spirit as the
/// evidence weights in §8 — not a value the spec derives.
enum LocationFreshnessPolicy {
    static let significantChangeMaxAge: TimeInterval = 300

    /// Small tolerance for device clock skew; a fix from the future is suspect, but a
    /// fraction of a second ahead is ordinary.
    static let futureTolerance: TimeInterval = 5

    static func isFresh(
        _ sample: LocationQualitySample,
        now: Date,
        maxAge: TimeInterval = significantChangeMaxAge
    ) -> Bool {
        let age = now.timeIntervalSince(sample.timestamp)
        return age <= maxAge && age >= -futureTolerance
    }
}

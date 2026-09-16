import Foundation

/// Normalized confidence of a motion observation
/// (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §5 keeps confidence a bucket, never a
/// platform SDK enum).
enum MotionConfidence: String, Sendable, Codable, CaseIterable, Comparable {
    case low
    case medium
    case high

    private var rank: Int {
        switch self {
        case .low: 0
        case .medium: 1
        case .high: 2
        }
    }

    static func < (lhs: MotionConfidence, rhs: MotionConfidence) -> Bool {
        lhs.rank < rhs.rank
    }
}

/// One normalized Core Motion observation
/// (shape from docs/04_IOS_IMPLEMENTATION.md §5).
///
/// **The flags are independent on purpose.** Core Motion can report `automotive` and
/// `stationary` as true at the same instant — a car waiting at a light is both. Folding
/// them into a single enum would silently destroy the evidence the engine needs, so this
/// type keeps every flag and lets the engine weigh them (docs/04 §5).
struct MotionSample: Sendable, Equatable, Codable {
    let timestamp: Date
    let automotive: Bool
    let walking: Bool
    let stationary: Bool
    let running: Bool
    let confidence: MotionConfidence

    init(
        timestamp: Date,
        automotive: Bool = false,
        walking: Bool = false,
        stationary: Bool = false,
        running: Bool = false,
        confidence: MotionConfidence
    ) {
        self.timestamp = timestamp
        self.automotive = automotive
        self.walking = walking
        self.stationary = stationary
        self.running = running
        self.confidence = confidence
    }

    /// False when Core Motion had no opinion for this slice, which happens routinely in
    /// history queries. Such samples are kept rather than dropped so gaps stay visible.
    var hasKnownActivity: Bool {
        automotive || walking || stationary || running
    }

    /// Every true flag, in a stable order, for the P0 diagnostics screen.
    ///
    /// Deliberately a list and not a single "dominant" label: on device the
    /// `automotive` + `stationary` pair is the case worth seeing with both parts intact.
    var activityFlagsDescription: String {
        let flags = [
            ("automotive", automotive),
            ("walking", walking),
            ("stationary", stationary),
            ("running", running)
        ]
        let active = flags.filter(\.1).map(\.0)
        return active.isEmpty ? "unknown" : active.joined(separator: "+")
    }
}

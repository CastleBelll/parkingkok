import Foundation

/// The analytics opt-in.
///
/// docs/07 "동의": default off, persisted locally, revocable at any time, and a revocation
/// stops transmission immediately.
///
/// **There is deliberately no event for a change to this flag.** Reporting a grant would be
/// a transmission decided by the state *before* the grant existed; reporting a revocation
/// would be a transmission after it was withdrawn. Both are the thing docs/07 forbids.
protocol AnalyticsConsentStoring: Sendable {
    var isGranted: Bool { get }
    func setGranted(_ granted: Bool)
}

/// `UserDefaults`-backed consent, the same home as `SmartDetectionPreference`: a plain
/// boolean preference with no location data in it (docs/00_CORE_RULES.md Data).
///
/// `@unchecked Sendable` because `UserDefaults` carries no `Sendable` annotation while
/// being documented as thread-safe, and this type adds no mutable state of its own. The
/// alternative — pinning analytics to the main actor — would lock out the detection
/// events, which are recorded from actor isolation.
final class UserDefaultsAnalyticsConsentStore: AnalyticsConsentStoring, @unchecked Sendable {
    private static let key = "pk.analytics.consentGranted"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Defaults to off: `bool(forKey:)` answers `false` for an absent key, which is exactly
    /// the required default and needs no migration on first launch.
    var isGranted: Bool {
        defaults.bool(forKey: Self.key)
    }

    func setGranted(_ granted: Bool) {
        defaults.set(granted, forKey: Self.key)
    }
}

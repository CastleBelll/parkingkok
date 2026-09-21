import Foundation

/// The Smart Detection opt-in.
///
/// docs/04_IOS_IMPLEMENTATION.md §4 hangs the Always-authorization prompt off this flag:
/// no opt-in, no Always prompt. `UserDefaults` is the right home because this is a plain
/// preference — no location data is stored here (docs/00_CORE_RULES.md Data).
@MainActor
final class SmartDetectionPreference {
    private static let key = "pk.smartDetection.enabled"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Defaults to off: the user opts in, never out.
    var isEnabled: Bool {
        get { defaults.bool(forKey: Self.key) }
        set { defaults.set(newValue, forKey: Self.key) }
    }
}

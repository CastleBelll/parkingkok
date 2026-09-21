import Foundation

/// The Smart Detection opt-in.
///
/// docs/04_IOS_IMPLEMENTATION.md §4 hangs the Always-authorization prompt off this flag:
/// no opt-in, no Always prompt. `UserDefaults` is the right home because this is a plain
/// preference — no location data is stored here (docs/00_CORE_RULES.md Data).
@MainActor
final class SmartDetectionPreference {
    private static let key = "pk.smartDetection.enabled"
    private static let firstRunKey = "pk.smartDetection.firstRunAnswered"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Defaults to off: the user opts in, never out.
    ///
    /// The opt-in is *asked for* on first launch (docs/10 §2a) rather than left in Settings
    /// for someone to find, because automatic detection is the product and a switch nobody
    /// presses is a product nobody sees work. What a default of `true` would buy instead is
    /// a preference with no authorization behind it — the screen claiming to detect while
    /// Core Location was never asked.
    var isEnabled: Bool {
        get { defaults.bool(forKey: Self.key) }
        set { defaults.set(newValue, forKey: Self.key) }
    }

    /// Whether the first-run question has been answered, either way.
    ///
    /// Its own key, because "said no" and "has not been asked" are different states and
    /// reading the answer off `isEnabled` would ask again on every launch.
    var isFirstRunAnswered: Bool {
        get { defaults.bool(forKey: Self.firstRunKey) }
        set { defaults.set(newValue, forKey: Self.firstRunKey) }
    }
}

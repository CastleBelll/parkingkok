import Foundation

/// Everything the root stack can push (docs/16 §5: "Typed navigation").
///
/// The stack is rooted at home, not at a tab bar, because `01-home-main.png` puts the
/// settings gear and the "전체보기" link in the home header — and because G2's
/// "zero-navigation lookup" means the parking has to be on the first screen, not one
/// tab away from it.
enum AppRoute: Hashable {
    /// The active parking, or a finished one opened from history.
    case parkingDetail(id: UUID)
    case history
    case settings
    /// P0 instrumentation. Reachable from settings → 개발자, and still the only way to
    /// read the field-test counters.
    case diagnostics
}

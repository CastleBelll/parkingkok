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
    /// docs/10 §7b. What the header's bell opens: the notifications the app has raised
    /// and what became of each. It used to open the notification *settings*, "which
    /// answered a question nobody had".
    case notificationHistory
    /// docs/10 §7a: "A screen, pushed, with a normal back." Never a sheet and never an
    /// alert — backing out has to leave the candidate pending rather than answer it.
    case candidateConfirmation(id: UUID)
    case settings
    /// P0 instrumentation. Reachable from settings → 개발자, and still the only way to
    /// read the field-test counters.
    case diagnostics
}

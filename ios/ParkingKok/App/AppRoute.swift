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
    case settings(focus: SettingsFocus?)
    /// P0 instrumentation. Reachable from settings → 개발자, and still the only way to
    /// read the field-test counters.
    case diagnostics
}

/// Which part of settings the caller wants in front of the user on arrival.
///
/// Exists so the home header's bell can be a real control rather than decoration. The
/// mock puts a bell next to the gear; the app has no notification centre for it to open,
/// and `docs/19`'s "거짓말하지 마라" spirit rules out a button that does nothing. Sending
/// it to the 알림 section of settings is the one destination that is actually about
/// notifications — and it is a different place from where the gear lands, so the two
/// buttons are not the same button drawn twice.
enum SettingsFocus: Hashable {
    case notifications
}

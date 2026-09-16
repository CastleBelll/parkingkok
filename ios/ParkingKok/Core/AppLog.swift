import OSLog

/// OSLog categories for the app (docs/16_CODING_STANDARDS.md §7).
///
/// Hard constraint (CLAUDE.md, docs/00_CORE_RULES.md Privacy): no exact coordinates
/// reach a log line. The detection stack keeps coordinates out of these call sites by
/// construction — adapters map `CLLocation` to accuracy + timestamp before anything
/// loggable sees it.
enum AppLog {
    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.parkingkok.app"

    /// App/scene lifecycle and background relaunch bookkeeping.
    static let lifecycle = Logger(subsystem: subsystem, category: "lifecycle")

    /// Detection rehydration, checkpoint IO, motion history reconstruction.
    static let detection = Logger(subsystem: subsystem, category: "detection")
}

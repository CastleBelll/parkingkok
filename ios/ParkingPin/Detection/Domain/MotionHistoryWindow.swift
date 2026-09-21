import Foundation

/// A closed time range to reconstruct motion history over.
struct MotionHistoryWindow: Sendable, Equatable {
    let start: Date
    let end: Date

    var duration: TimeInterval {
        end.timeIntervalSince(start)
    }
}

/// Decides how far back to replay Core Motion after a background relaunch.
///
/// docs/04_IOS_IMPLEMENTATION.md §6 step 3: query from `max(checkpoint.time, now - 30m)`.
/// Pure so the clamping rules are unit-tested instead of discovered in the field.
enum MotionHistoryWindowPolicy {
    /// Upper bound on replay. Older motion cannot describe the trip we just woke for,
    /// and querying wider only costs battery (docs/04 §6).
    static let maximumLookback: TimeInterval = 30 * 60

    /// `CMMotionActivityManager` keeps roughly seven days of history. A checkpoint older
    /// than that can never be reconciled from motion evidence.
    static let motionRetention: TimeInterval = 7 * 24 * 60 * 60

    static func window(now: Date, checkpointDate: Date?) -> MotionHistoryWindow {
        let lookbackFloor = now.addingTimeInterval(-maximumLookback)
        let requested = max(checkpointDate ?? lookbackFloor, lookbackFloor)
        // A checkpoint timestamp ahead of `now` means the device clock moved backwards.
        // Clamp instead of handing Core Motion an inverted range.
        return MotionHistoryWindow(start: min(requested, now), end: now)
    }

    /// True when the checkpoint predates Core Motion's retention, so motion history can
    /// no longer explain it. The engine's response to a stale checkpoint is M0A-2; this
    /// milestone only surfaces the fact.
    static func isBeyondMotionRetention(checkpointDate: Date, now: Date) -> Bool {
        now.timeIntervalSince(checkpointDate) > motionRetention
    }
}

import Foundation

/// Turns a parking's age into the line under the hero — `1시간 24분째 주차 중`.
///
/// A pure function over two dates. docs/16 §8: inject the clock, so every caller passes
/// `now` and the tests never sleep.
enum ParkingElapsed {
    private static let secondsPerMinute = 60
    private static let minutesPerHour = 60
    private static let hoursPerDay = 24

    /// Components of the elapsed interval, clamped at zero.
    ///
    /// A negative interval is reachable in the field: the user can move the clock back,
    /// and a record written before a DST change can look like it starts in the future.
    /// That is "just now", not a negative duration.
    static func components(from startedAt: Date, to now: Date) -> (days: Int, hours: Int, minutes: Int) {
        let totalMinutes = max(0, Int(now.timeIntervalSince(startedAt)) / secondsPerMinute)
        let totalHours = totalMinutes / minutesPerHour
        return (
            days: totalHours / hoursPerDay,
            hours: totalHours % hoursPerDay,
            minutes: totalMinutes % minutesPerHour
        )
    }

    /// The active-parking line on home and detail (`01-home-main.png`).
    static func describeActive(from startedAt: Date, to now: Date) -> String {
        let parts = components(from: startedAt, to: now)
        guard parts.days > 0 || parts.hours > 0 || parts.minutes > 0 else {
            // "방금째 주차 중" is not a sentence.
            return "방금 주차했어요"
        }
        return "\(describeDuration(from: startedAt, to: now)) 주차 중"
    }

    /// The same duration without the "still parked" tail — used for a finished record.
    static func describeDuration(from startedAt: Date, to now: Date) -> String {
        let parts = components(from: startedAt, to: now)
        if parts.days > 0 {
            return parts.hours > 0 ? "\(parts.days)일 \(parts.hours)시간째" : "\(parts.days)일째"
        }
        if parts.hours > 0 {
            return parts.minutes > 0 ? "\(parts.hours)시간 \(parts.minutes)분째" : "\(parts.hours)시간째"
        }
        if parts.minutes > 0 {
            return "\(parts.minutes)분째"
        }
        return "방금"
    }
}

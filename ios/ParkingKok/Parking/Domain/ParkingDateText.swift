import Foundation

/// Date and time labels for record lists (`04-history-list.png`: `어제` over `오후 6:24`).
///
/// Built on `Date.FormatStyle` rather than a cached `DateFormatter`: the style is a
/// value, so it needs no `nonisolated(unsafe)` escape hatch to be shared under Swift 6
/// strict concurrency, and it follows the device's locale and 12/24-hour preference
/// instead of a hard-coded Korean pattern.
///
/// `now` is a parameter everywhere it matters, so "오늘"/"어제" can be tested without
/// waiting for midnight (docs/16 §8).
enum ParkingDateText {
    /// Recent days read better as words; older ones need the date.
    static func day(_ date: Date, now: Date = Date(), calendar: Calendar = .autoupdatingCurrent) -> String {
        if calendar.isDate(date, inSameDayAs: now) {
            return "오늘"
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) {
            return "어제"
        }
        return date.formatted(.dateTime.month(.abbreviated).day())
    }

    static func time(_ date: Date) -> String {
        date.formatted(.dateTime.hour().minute())
    }

    /// Full stamp for the detail screen.
    static func dayAndTime(_ date: Date, now: Date = Date(), calendar: Calendar = .autoupdatingCurrent) -> String {
        "\(day(date, now: now, calendar: calendar)) \(time(date))"
    }
}

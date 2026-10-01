import Foundation

/// One MetricKit reporting period, reduced to what docs/05 §19's battery gate asks: how long
/// the app held the CPU and each Core Location accuracy, foreground and background.
///
/// Aggregates only — durations, never a place. MetricKit itself carries no coordinate, and
/// this projection keeps nothing a payload might add later.
struct EnergyDay: Sendable, Equatable, Codable {
    let periodStart: Date
    let periodEnd: Date
    let appBuild: String?
    let cpuSeconds: Double?
    let foregroundSeconds: Double?
    let backgroundSeconds: Double?
    let backgroundLocationSeconds: Double?
    /// Seconds Core Location ran at each accuracy for this app. `bestForNavigation` and
    /// `best` are the bounded driving capture; the coarse ones are significant change and
    /// the region monitoring around it.
    let locationBestForNavigationSeconds: Double?
    let locationBestSeconds: Double?
    let locationTenMetersSeconds: Double?
    let locationHundredMetersSeconds: Double?
    let locationKilometerSeconds: Double?
    let locationThreeKilometersSeconds: Double?
}

/// The on-device log of `EnergyDay`s the battery gate is read from.
enum EnergyMetricsLog {
    /// About two months of daily payloads: enough to compare a week with Smart Detection on
    /// against one with it off, small enough to stay a diagnostics file.
    static let maximumDays = 60

    /// `existing` with `incoming` folded in. MetricKit may deliver a period again (past
    /// payloads on every launch), so a period already held is replaced rather than repeated.
    /// Oldest first, newest `maximumDays` kept.
    ///
    /// Periods are matched to the whole second: the file stores ISO 8601, which drops the
    /// fraction a fresh payload still carries.
    static func merging(_ incoming: [EnergyDay], into existing: [EnergyDay]) -> [EnergyDay] {
        var byPeriod: [PeriodKey: EnergyDay] = [:]
        for day in existing + incoming {
            byPeriod[PeriodKey(day)] = day
        }
        let ordered = byPeriod.values.sorted {
            ($0.periodStart, $0.periodEnd) < ($1.periodStart, $1.periodEnd)
        }
        return Array(ordered.suffix(maximumDays))
    }

    private struct PeriodKey: Hashable {
        let start: Int
        let end: Int

        init(_ day: EnergyDay) {
            start = Int(day.periodStart.timeIntervalSince1970.rounded(.down))
            end = Int(day.periodEnd.timeIntervalSince1970.rounded(.down))
        }
    }
}

import Foundation
import Testing
@testable import ParkingPin

/// docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "gap 계측".
///
/// These numbers exist to let the 30-minute idle gap be re-set against a distribution
/// instead of against one day, so what they must be above all is *correct* — a measurement
/// that is off by a bucket would argue for the wrong threshold.
@Suite("Trace gap stats")
struct TraceGapStatsTests {
    private func events(at offsets: [TimeInterval]) -> [TraceEvent] {
        offsets.map { .motion(.stationaryEnter, at: TestTime.offset($0), confidence: .medium) }
    }

    @Test("A session with no gap to observe reports zero, not a missing measurement")
    func fewerThanTwoEventsHasNoGap() {
        // Arrange / Act / Assert
        #expect(TraceGapStats(events: []) == .empty)
        #expect(TraceGapStats(events: events(at: [0])) == .empty)
    }

    @Test("The maximum is the largest silence, not the last one")
    func maximumIsTheLargestGap() {
        // Arrange — the big gap is in the middle.
        let stats = TraceGapStats(events: events(at: [0, 60, 60 + 22 * 60, 60 + 22 * 60 + 30]))

        // Act / Assert
        #expect(stats.maxGapMillis == 22 * 60 * 1000)
    }

    /// The counters are strict: a gap of exactly ten minutes is not "over ten minutes".
    /// It matters because the field distribution clusters right at the round numbers.
    @Test("The thresholds are exclusive at exactly 10 and 20 minutes")
    func thresholdsAreExclusive() {
        // Arrange
        var atTenMinutes = TraceGapStats()
        atTenMinutes.record(gapMillis: 10 * 60 * 1000)

        var justOverTenMinutes = TraceGapStats()
        justOverTenMinutes.record(gapMillis: 10 * 60 * 1000 + 1)

        var atTwentyMinutes = TraceGapStats()
        atTwentyMinutes.record(gapMillis: 20 * 60 * 1000)

        var justOverTwentyMinutes = TraceGapStats()
        justOverTwentyMinutes.record(gapMillis: 20 * 60 * 1000 + 1)

        // Act / Assert
        #expect(atTenMinutes.gapsOver10MinCount == 0)
        #expect(justOverTenMinutes.gapsOver10MinCount == 1)
        #expect(justOverTenMinutes.gapsOver20MinCount == 0)
        #expect(atTwentyMinutes.gapsOver20MinCount == 0)
        #expect(justOverTwentyMinutes.gapsOver20MinCount == 1)
    }

    @Test("A gap over twenty minutes counts in both buckets")
    func bucketsNest() {
        // Arrange
        let stats = TraceGapStats(events: events(at: [0, 25 * 60, 25 * 60 + 15 * 60]))

        // Act / Assert
        #expect(stats.gapsOver10MinCount == 2)
        #expect(stats.gapsOver20MinCount == 1)
    }

    /// The recorder's watermark refuses any event not strictly newer than the last, so a
    /// negative delta cannot arrive — but a negative maximum would read as "no gap
    /// observed" and quietly understate the aggregate the threshold will be re-set from.
    @Test("A backwards delta is clamped rather than recorded as a negative maximum")
    func backwardsDeltaIsClamped() {
        // Arrange
        var stats = TraceGapStats()

        // Act
        stats.record(gapMillis: -5000)

        // Assert
        #expect(stats.maxGapMillis == 0)
        #expect(stats.gapsOver10MinCount == 0)
    }

    @Test("Folding event by event agrees with measuring the finished list")
    func incrementalMatchesBatch() {
        // Arrange
        let list = events(at: [0, 30, 30 + 12 * 60, 30 + 12 * 60 + 5, 30 + 12 * 60 + 5 + 21 * 60])

        // Act — the recorder's path: one `record` per appended event.
        var incremental = TraceGapStats()
        for (previous, next) in zip(list, list.dropFirst()) {
            incremental.record(gapMillis: next.atMillis - previous.atMillis)
        }

        // Assert
        #expect(incremental == TraceGapStats(events: list))
        #expect(incremental.gapsOver10MinCount == 2)
        #expect(incremental.gapsOver20MinCount == 1)
    }
}

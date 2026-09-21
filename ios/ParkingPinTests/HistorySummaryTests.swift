import Foundation
import Testing
@testable import ParkingPin

/// The `이번 달 N회` card and the filter chips on `04-history-list.png`.
struct HistorySummaryTests {
    private static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        return calendar
    }()

    private func date(_ iso: String) -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.date(from: iso) ?? .distantPast
    }

    private func session(startedAt: Date, source: ParkingSource = .manual) -> ParkingSession {
        ParkingSession(
            id: UUID(),
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(3600),
            source: source,
            confidenceBucket: nil,
            location: nil,
            floor: FloorValue.parse("B3"),
            zone: nil,
            spot: nil,
            memo: nil,
            photoRelativePath: nil,
            createdAt: startedAt,
            updatedAt: startedAt
        )
    }

    @Test("Only this calendar month is counted, not the last 30 days")
    func countsCalendarMonth() {
        // Arrange
        let now = date("2026-09-18T12:00:00Z")
        let sessions = [
            session(startedAt: date("2026-09-01T09:00:00Z")),
            session(startedAt: date("2026-09-18T08:00:00Z")),
            // Within 30 days, but the previous month.
            session(startedAt: date("2026-08-31T23:00:00Z"))
        ]

        // Act
        let summary = HistorySummary(sessions: sessions, now: now, calendar: Self.calendar)

        // Assert
        #expect(summary.thisMonth == 2)
        #expect(summary.lastMonth == 1)
    }

    @Test("A month with more parkings than the last says so")
    func reportsIncrease() {
        // Arrange
        let now = date("2026-09-18T12:00:00Z")
        let sessions = (0 ..< 4).map { session(startedAt: date("2026-09-0\($0 + 1)T09:00:00Z")) }
            + [session(startedAt: date("2026-08-05T09:00:00Z"))]

        // Act
        let summary = HistorySummary(sessions: sessions, now: now, calendar: Self.calendar)

        // Assert
        #expect(summary.comparisonText == "지난 달보다 3회 더 주차했어요.")
    }

    @Test("A quieter month is stated plainly rather than dressed up")
    func reportsDecrease() {
        // Arrange
        let now = date("2026-09-18T12:00:00Z")
        let sessions = [
            session(startedAt: date("2026-09-02T09:00:00Z")),
            session(startedAt: date("2026-08-05T09:00:00Z")),
            session(startedAt: date("2026-08-06T09:00:00Z"))
        ]

        // Act / Assert
        #expect(
            HistorySummary(sessions: sessions, now: now, calendar: Self.calendar).comparisonText
                == "지난 달보다 1회 적어요."
        )
    }

    @Test("The first month of use has nothing to compare against and says nothing")
    func omitsComparisonWithoutPreviousMonth() {
        // Arrange
        let now = date("2026-09-18T12:00:00Z")
        let sessions = [session(startedAt: date("2026-09-02T09:00:00Z"))]

        // Act
        let summary = HistorySummary(sessions: sessions, now: now, calendar: Self.calendar)

        // Assert — a "지난 달보다 1회 더" against a month the app was not installed for
        // would be an invented statistic.
        #expect(summary.lastMonth == 0)
        #expect(summary.comparisonText == nil)
    }

    @Test("An empty history reports zero rather than failing")
    func handlesEmptyHistory() {
        // Arrange / Act
        let summary = HistorySummary(sessions: [], now: date("2026-09-18T12:00:00Z"), calendar: Self.calendar)

        // Assert
        #expect(summary.thisMonth == 0)
        #expect(summary.comparisonText == nil)
    }

    @Test("A December-to-January rollover compares across the year boundary")
    func handlesYearBoundary() {
        // Arrange
        let now = date("2027-01-10T12:00:00Z")
        let sessions = [
            session(startedAt: date("2027-01-02T09:00:00Z")),
            session(startedAt: date("2026-12-20T09:00:00Z")),
            // Same month name, wrong year — must not be counted.
            session(startedAt: date("2026-01-20T09:00:00Z"))
        ]

        // Act
        let summary = HistorySummary(sessions: sessions, now: now, calendar: Self.calendar)

        // Assert
        #expect(summary.thisMonth == 1)
        #expect(summary.lastMonth == 1)
    }

    // ── Filters ─────────────────────────────────────────────────────────────

    @Test("Each chip keeps exactly the records it names")
    func filtersBySource() {
        // Arrange
        let manual = session(startedAt: date("2026-09-02T09:00:00Z"), source: .manual)
        let detected = session(startedAt: date("2026-09-03T09:00:00Z"), source: .detected)

        // Act / Assert
        #expect(HistoryFilter.all.matches(manual))
        #expect(HistoryFilter.all.matches(detected))
        #expect(HistoryFilter.manual.matches(manual))
        #expect(!HistoryFilter.manual.matches(detected))
        #expect(HistoryFilter.detected.matches(detected))
        #expect(!HistoryFilter.detected.matches(manual))
    }

    @Test("The chips are the three the mock shows, in its order")
    func exposesThreeFilters() {
        #expect(HistoryFilter.allCases.map(\.title) == ["전체", "자동 감지", "직접 저장"])
    }
}

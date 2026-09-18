import Foundation
import Testing
@testable import ParkingKok

/// The elapsed line under the hero (`01-home-main.png`: `1시간 24분째 주차 중`).
struct ParkingElapsedTests {
    private let start = Date(timeIntervalSince1970: 1_700_000_000)

    private func later(minutes: Int) -> Date {
        start.addingTimeInterval(TimeInterval(minutes * 60))
    }

    @Test("The mock's own value formats the way the mock shows it")
    func formatsHoursAndMinutes() {
        // Arrange / Act
        let text = ParkingElapsed.describeActive(from: start, to: later(minutes: 84))

        // Assert
        #expect(text == "1시간 24분째 주차 중")
    }

    @Test("Under an hour drops the hour component")
    func formatsMinutesOnly() {
        #expect(ParkingElapsed.describeActive(from: start, to: later(minutes: 24)) == "24분째 주차 중")
    }

    @Test("An exact hour does not trail a zero-minute component")
    func omitsZeroMinutes() {
        #expect(ParkingElapsed.describeActive(from: start, to: later(minutes: 120)) == "2시간째 주차 중")
    }

    @Test("Past a day the unit becomes days")
    func formatsDays() {
        // Arrange — 1일 3시간.
        let now = later(minutes: 27 * 60 + 30)

        // Act / Assert
        #expect(ParkingElapsed.describeActive(from: start, to: now) == "1일 3시간째 주차 중")
    }

    @Test("An exact number of days drops the hour component")
    func formatsWholeDays() {
        #expect(ParkingElapsed.describeActive(from: start, to: later(minutes: 48 * 60)) == "2일째 주차 중")
    }

    @Test("Under a minute reads as a sentence, not as 방금째")
    func formatsJustNow() {
        // Arrange / Act
        let text = ParkingElapsed.describeActive(from: start, to: start.addingTimeInterval(30))

        // Assert
        #expect(text == "방금 주차했어요")
    }

    @Test("A clock that moved backwards clamps to zero instead of showing a negative age")
    func clampsNegativeInterval() {
        // Arrange — the user rolled the device clock back an hour mid-parking.
        let now = start.addingTimeInterval(-3600)

        // Act
        let parts = ParkingElapsed.components(from: start, to: now)

        // Assert
        #expect(parts == (days: 0, hours: 0, minutes: 0))
        #expect(ParkingElapsed.describeActive(from: start, to: now) == "방금 주차했어요")
    }

    @Test("Seconds are truncated, never rounded up into the next minute")
    func truncatesSeconds() {
        // Arrange — 59 seconds is still zero minutes.
        let now = start.addingTimeInterval(59)

        // Act / Assert
        #expect(ParkingElapsed.components(from: start, to: now).minutes == 0)
        #expect(ParkingElapsed.components(from: start, to: start.addingTimeInterval(60)).minutes == 1)
    }

    @Test("A finished record is described without the still-parked tail")
    func describesFinishedDuration() {
        #expect(ParkingElapsed.describeDuration(from: start, to: later(minutes: 84)) == "1시간 24분째")
    }
}

/// `04-history-list.png` labels rows `오늘` / `어제` / a date.
struct ParkingDateTextTests {
    private let calendar = Calendar(identifier: .gregorian)

    private func date(_ iso: String) -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.date(from: iso) ?? .distantPast
    }

    @Test("Same calendar day reads 오늘")
    func namesToday() {
        // Arrange
        var calendar = calendar
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        let now = date("2026-09-18T20:14:00Z")

        // Act / Assert
        #expect(ParkingDateText.day(date("2026-09-18T08:00:00Z"), now: now, calendar: calendar) == "오늘")
    }

    @Test("The previous calendar day reads 어제")
    func namesYesterday() {
        // Arrange
        var calendar = calendar
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        let now = date("2026-09-18T20:14:00Z")

        // Act / Assert
        #expect(ParkingDateText.day(date("2026-09-17T18:24:00Z"), now: now, calendar: calendar) == "어제")
    }

    @Test("Anything older falls back to a date rather than counting days forever")
    func namesOlderDatesAsDates() {
        // Arrange
        var calendar = calendar
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        let now = date("2026-09-18T20:14:00Z")

        // Act
        let label = ParkingDateText.day(date("2026-09-13T13:17:00Z"), now: now, calendar: calendar)

        // Assert — the exact spelling is the locale's; what matters is that it is neither
        // of the two relative words.
        #expect(label != "오늘")
        #expect(label != "어제")
        #expect(!label.isEmpty)
    }

    @Test("Just before midnight is still 어제 the next morning, not a boundary bug")
    func handlesMidnightBoundary() {
        // Arrange
        var calendar = calendar
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        let now = date("2026-09-18T00:05:00Z")

        // Act / Assert — five minutes earlier, but a different calendar day.
        #expect(ParkingDateText.day(date("2026-09-17T23:55:00Z"), now: now, calendar: calendar) == "어제")
    }
}

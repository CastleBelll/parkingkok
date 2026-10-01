import Foundation
import Testing
@testable import ParkingPin

/// `위치 보내기` (docs/01 §5a). The same rows Android `ParkingShareTextTest` pins, so a family
/// member gets the same message from either phone.
@Suite("Share parking text")
struct ParkingShareTextTests {
    private let seoul = TimeZone(identifier: "Asia/Seoul")!

    private func date(hour: Int, minute: Int) -> Date {
        var components = DateComponents(year: 2026, month: 10, day: 1, hour: hour, minute: minute)
        components.timeZone = seoul
        return Calendar(identifier: .gregorian).date(from: components)!
    }

    private func session(
        floor: String? = nil,
        zone: String? = nil,
        spot: String? = nil,
        memo: String? = nil,
        location: ParkedLocation? = nil,
        startedAt: Date? = nil
    ) -> ParkingSession {
        let start = startedAt ?? date(hour: 14, minute: 35)
        return ParkingSession(
            id: UUID(),
            startedAt: start,
            endedAt: nil,
            source: .manual,
            confidenceBucket: nil,
            location: location,
            floor: floor.map(FloorValue.parse) ?? nil,
            zone: zone,
            spot: spot,
            memo: memo,
            photoRelativePath: nil,
            createdAt: start,
            updatedAt: start
        )
    }

    private var gangnam: ParkedLocation {
        ParkedLocation(latitude: 37.4979, longitude: 127.0276, horizontalAccuracy: 12, capturedAt: date(hour: 14, minute: 35))
    }

    @Test("A full record sends its place, its time and a map link")
    func fullRecord() {
        // Act
        let text = ParkingShareText.text(
            for: session(floor: "B3", zone: "A구역", spot: "142", location: gangnam),
            timeZone: seoul
        )

        // Assert
        #expect(text == """
        B3 · A구역 · 142에 주차했어요
        10월 1일 오후 2:35
        마지막으로 확인된 위치: https://www.google.com/maps/search/?api=1&query=37.49790,127.02760
        """)
    }

    @Test("A record with no location sends no map line")
    func noLocation() {
        #expect(ParkingShareText.text(for: session(floor: "B3"), timeZone: seoul) == "B3에 주차했어요\n10월 1일 오후 2:35")
    }

    @Test("A record with no place still says when")
    func noPlace() {
        #expect(ParkingShareText.text(for: session(), timeZone: seoul) == "주차했어요\n10월 1일 오후 2:35")
    }

    @Test("A spot alone takes 번, as on the home screen")
    func spotAlone() {
        #expect(
            ParkingShareText.text(for: session(floor: "B3", spot: "142"), timeZone: seoul)
                == "B3 · 142번에 주차했어요\n10월 1일 오후 2:35"
        )
    }

    @Test("A morning time reads 오전")
    func morning() {
        let text = ParkingShareText.text(for: session(startedAt: date(hour: 9, minute: 5)), timeZone: seoul)
        #expect(text == "주차했어요\n10월 1일 오전 9:05")
    }

    @Test("The memo never leaves the phone")
    func memoStays() {
        let text = ParkingShareText.text(for: session(floor: "B3", memo: "트렁크에 우산"), timeZone: seoul)
        #expect(!text.contains("트렁크"))
    }

    @Test("The coordinate is written with a dot whatever the device locale")
    func posixCoordinate() {
        #expect(
            ParkingShareText.mapURL(latitude: -33.8688, longitude: 151.2093)
                == "https://www.google.com/maps/search/?api=1&query=-33.86880,151.20930"
        )
    }
}

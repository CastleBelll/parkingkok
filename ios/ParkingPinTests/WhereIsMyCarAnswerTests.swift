import Foundation
import Testing
@testable import ParkingPin

/// docs/02 §15 `내 차 어디?`: the sentence Siri reads out.
@Suite("Where is my car answer")
struct WhereIsMyCarAnswerTests {
    private let start = Date(timeIntervalSince1970: 1_790_000_000)

    private func snapshot(floor: String? = nil, zone: String? = nil, spot: String? = nil) -> ActiveParkingSnapshot {
        ActiveParkingSnapshot(
            sessionId: UUID(),
            revision: 1,
            updatedAt: start,
            startedAt: start,
            floor: floor.map(FloorValue.parse) ?? nil,
            zone: zone,
            spot: spot
        )
    }

    @Test("A full parking reads its place and how long it has been there")
    func fullParking() {
        // Act
        let text = WhereIsMyCarAnswer.text(
            for: snapshot(floor: "B3", zone: "A구역", spot: "142"),
            now: start.addingTimeInterval(84 * 60)
        )

        // Assert
        #expect(text == "B3 · A구역 · 142, 1시간 24분째 주차 중이에요.")
    }

    @Test("A parking from moments ago says so")
    func justParked() {
        #expect(WhereIsMyCarAnswer.text(for: snapshot(floor: "B3"), now: start.addingTimeInterval(20)) == "B3, 방금 주차했어요.")
    }

    @Test("A spot alone takes 번, as on the home screen")
    func spotAlone() {
        let text = WhereIsMyCarAnswer.text(for: snapshot(spot: "142"), now: start.addingTimeInterval(5 * 60))
        #expect(text == "142번, 5분째 주차 중이에요.")
    }

    @Test("A parking with nothing entered still says how long, and that no floor was kept")
    func nothingEntered() {
        let text = WhereIsMyCarAnswer.text(for: snapshot(), now: start.addingTimeInterval(2 * 3600))
        #expect(text == "2시간째 주차 중이에요. 층은 기록하지 않았어요.")
    }

    @Test("No active parking is said plainly")
    func noParking() {
        #expect(WhereIsMyCarAnswer.text(for: nil, now: start) == "진행 중인 주차가 없어요.")
    }
}

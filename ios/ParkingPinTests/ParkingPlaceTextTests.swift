import Foundation
import Testing
@testable import ParkingPin

/// `A구역 · 142`, `A구역`, `142번` — how a record names where inside the floor it is.
@Suite("Parking place text")
struct ParkingPlaceTextTests {
    @Test("A bay on its own takes the word that says what it is")
    func bareSpotTakesTheWord() {
        #expect(session(zone: nil, spot: "142").placeText == "142번")
    }

    @Test("A bay the user already wrote the word on keeps exactly what they wrote")
    func spotKeepsItsOwnWord() {
        // FR-006 lets the field hold any text, and a person copying a wall writes `01번`
        // as often as `01`. Appending unconditionally drew `01번번` on the home hero.
        #expect(session(zone: nil, spot: "01번").placeText == "01번")
    }

    @Test("A zone beside a bay is joined, and the word is not added")
    func zoneAndSpotAreJoined() {
        #expect(session(zone: "A구역", spot: "142").placeText == "A구역 · 142")
    }

    @Test("Neither recorded is nothing to draw")
    func nothingIsNil() {
        #expect(session(zone: nil, spot: nil).placeText == nil)
    }

    private func session(zone: String?, spot: String?) -> ParkingSession {
        ParkingSession(
            id: UUID(),
            startedAt: .distantPast,
            endedAt: nil,
            source: .manual,
            confidenceBucket: nil,
            location: nil,
            floor: FloorValue.parse("B5"),
            zone: zone,
            spot: spot,
            memo: nil,
            photoRelativePath: nil,
            createdAt: .distantPast,
            updatedAt: .distantPast
        )
    }
}

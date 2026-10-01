import Foundation
import Testing
@testable import ParkingPin

/// docs/02 §18: last time's floor and zone, offered where the car was left before. The same
/// cases Android `UsualSpotLookupTest` pins.
@Suite("Usual spot lookup")
struct UsualSpotLookupTests {
    private let epoch = Date(timeIntervalSince1970: 1_700_000_000)

    // ~111 m per 0.001° of latitude.
    private var mart: ParkedLocation { location(37.5000) }
    private var acrossTheCarPark: ParkedLocation { location(37.5010) }
    private var nextBlock: ParkedLocation { location(37.5020) }

    @Test func `the newest parking here is the suggestion`() {
        let history = [
            session(at: mart, floor: "B2", zone: "A"),
            session(at: mart, floor: "B4", zone: "C"),
        ]

        #expect(UsualSpotLookup.find(near: mart, in: history) == PillarReading(floorText: "B2", zone: "A"))
    }

    @Test func `the far side of one car park still counts`() {
        let history = [session(at: acrossTheCarPark, floor: "B1")]

        #expect(UsualSpotLookup.find(near: mart, in: history)?.floorText == "B1")
    }

    @Test func `the next block is a different car park`() {
        #expect(UsualSpotLookup.find(near: mart, in: [session(at: nextBlock, floor: "B1")]) == nil)
    }

    @Test func `a parking that said nothing is skipped for an older one that did`() {
        let history = [
            session(at: mart, floor: nil, zone: ""),
            session(at: mart, floor: "3F"),
        ]

        #expect(UsualSpotLookup.find(near: mart, in: history)?.floorText == "3F")
    }

    @Test func `the bay number is never carried over`() {
        let history = [session(at: mart, floor: "B2", zone: "A", spot: "47")]

        #expect(UsualSpotLookup.find(near: mart, in: history)?.spot == nil)
    }

    @Test func `a vague or unknown fix here offers nothing`() {
        let history = [session(at: mart, floor: "B2")]

        #expect(UsualSpotLookup.find(near: location(37.5, accuracy: 300), in: history) == nil)
        #expect(UsualSpotLookup.find(near: location(37.5, accuracy: -1), in: history) == nil)
        #expect(UsualSpotLookup.find(near: nil, in: history) == nil)
    }

    @Test func `a vague fix in history is not a match`() {
        let history = [session(at: location(37.5, accuracy: 250), floor: "B2")]

        #expect(UsualSpotLookup.find(near: mart, in: history) == nil)
    }

    @Test func `a saved parking is offered only what it left blank`() {
        let history = [session(at: mart, floor: "B2", zone: "A")]
        let active = session(at: mart, floor: "B2", zone: nil)

        #expect(UsualSpotLookup.find(for: active, in: history) == PillarReading(floorText: nil, zone: "A"))
    }

    @Test func `a saved parking that says everything is not second-guessed`() {
        let history = [session(at: mart, floor: "B2", zone: "A")]
        let active = session(at: mart, floor: "B3", zone: "D")

        #expect(UsualSpotLookup.find(for: active, in: history) == nil)
    }

    @Test func `a parking is not its own precedent`() {
        let active = session(at: mart, floor: nil, zone: nil)

        #expect(UsualSpotLookup.find(for: active, in: [active]) == nil)
    }

    @Test func `a parking on another floor is not offered last time's zone`() {
        let history = [session(at: mart, floor: "B2", zone: "C")]
        let active = session(at: mart, floor: "B3", zone: nil)

        #expect(UsualSpotLookup.find(for: active, in: history) == nil)
    }

    @Test func `the same floor written another way still agrees`() {
        #expect(UsualSpotLookup.agrees(FloorValue.parse("지하 2층"), usualFloorText: "B2"))
        #expect(!UsualSpotLookup.agrees(FloorValue.parse("B3"), usualFloorText: "B2"))
        #expect(UsualSpotLookup.agrees(nil, usualFloorText: "B2"))
        #expect(!UsualSpotLookup.agrees(FloorValue.parse("B2"), usualFloorText: nil))
    }

    private func location(_ latitude: Double, accuracy: Double = 20) -> ParkedLocation {
        ParkedLocation(latitude: latitude, longitude: 127.0, horizontalAccuracy: accuracy, capturedAt: epoch)
    }

    private func session(
        at location: ParkedLocation,
        floor: String? = nil,
        zone: String? = nil,
        spot: String? = nil
    ) -> ParkingSession {
        ParkingSession(
            id: UUID(),
            startedAt: epoch,
            endedAt: epoch,
            source: .manual,
            confidenceBucket: nil,
            location: location,
            floor: floor.map(FloorValue.parse) ?? nil,
            zone: zone,
            spot: spot,
            memo: nil,
            photoRelativePath: nil,
            createdAt: epoch,
            updatedAt: epoch
        )
    }
}

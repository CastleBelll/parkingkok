import Foundation
import Testing
@testable import ParkingPin

/// docs/02 §6a on the detail screen: offered when the photo fills a blank, never over what
/// the record already says (audit 2026-10-01 L1).
@Suite("Detail pillar offer")
struct DetailPillarOfferTests {
    private let epoch = Date(timeIntervalSince1970: 1_700_000_000)

    @Test func `a zone read for a record with a floor and no zone is offered`() {
        let session = session(floor: "B1", zone: nil)

        #expect(ParkingDetailView.reading(PillarReading(floorText: nil, zone: "A", spot: "47"), fillsBlanksOf: session))
    }

    @Test func `a floor read for a record with no floor is offered`() {
        #expect(ParkingDetailView.reading(PillarReading(floorText: "B3"), fillsBlanksOf: session(floor: nil, zone: nil)))
    }

    @Test func `a read that only repeats what the record says is not offered`() {
        let session = session(floor: "B1", zone: "A", spot: "47")

        #expect(!ParkingDetailView.reading(PillarReading(floorText: "B2", zone: "C", spot: "9"), fillsBlanksOf: session))
    }

    @Test func `a read that found nothing is not offered`() {
        #expect(!ParkingDetailView.reading(.none, fillsBlanksOf: session(floor: nil, zone: nil)))
    }

    private func session(floor: String?, zone: String?, spot: String? = nil) -> ParkingSession {
        ParkingSession(
            id: UUID(),
            startedAt: epoch,
            endedAt: nil,
            source: .manual,
            confidenceBucket: nil,
            location: nil,
            floor: floor.flatMap(FloorValue.parse),
            zone: zone,
            spot: spot,
            memo: nil,
            photoRelativePath: nil,
            createdAt: epoch,
            updatedAt: epoch
        )
    }
}

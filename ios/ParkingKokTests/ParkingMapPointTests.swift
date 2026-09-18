import Foundation
import Testing
@testable import ParkingKok

/// FR-008's map: what the detail screen may draw, and the wording it must use.
struct ParkingMapPointTests {
    private static let start = Date(timeIntervalSince1970: 1_700_000_000)

    private func session(location: ParkedLocation?) -> ParkingSession {
        ParkingSession(
            id: UUID(),
            startedAt: Self.start,
            endedAt: nil,
            source: .manual,
            confidenceBucket: nil,
            location: location,
            floor: FloorValue.parse("B3"),
            zone: "A구역",
            spot: "142",
            memo: nil,
            photoRelativePath: nil,
            createdAt: Self.start,
            updatedAt: Self.start
        )
    }

    private func location(
        latitude: Double = 37.123_456_7,
        longitude: Double = 127.987_654_3,
        accuracy: Double = 18
    ) -> ParkedLocation {
        ParkedLocation(
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: accuracy,
            capturedAt: Self.start
        )
    }

    /// The acceptance case: a parking saved with location permission denied (FR-001) has
    /// no point, and the screen reads that as "no map, no 길찾기".
    @Test("A record with no coordinate produces no map point, which is what disables 길찾기")
    func recordWithoutLocationHasNoPoint() {
        // Arrange
        let record = session(location: nil)

        // Act
        let point = ParkingMapPoint(record)

        // Assert
        #expect(point == nil)
    }

    @Test("A record with a coordinate produces a point carrying its accuracy")
    func recordWithLocationHasPoint() throws {
        // Arrange
        let record = session(location: location(accuracy: 18))

        // Act
        let point = try #require(ParkingMapPoint(record))

        // Assert
        #expect(point.accuracyMeters == 18)
        #expect(point.capturedAt == Self.start)
    }

    /// Edge case: a corrupt or partially written row reaches MapKit as NaN and takes the
    /// process with it. Refusing it here falls back to the no-location card instead.
    @Test("A coordinate no map can draw is refused rather than handed to MapKit")
    func refusesUnusableCoordinates() {
        // Arrange
        let unusable: [ParkedLocation] = [
            location(latitude: .nan),
            location(longitude: .nan),
            location(latitude: 91),
            location(longitude: -181),
            location(accuracy: 0),
            location(accuracy: -1),
            location(accuracy: .infinity)
        ]

        // Act / Assert
        for candidate in unusable {
            #expect(ParkingMapPoint(session(location: candidate)) == nil)
        }
    }

    // ── Camera framing ──────────────────────────────────────────────────────

    @Test("The camera span leaves room around the accuracy circle")
    func spanFitsAccuracyCircle() throws {
        // Arrange
        let point = try #require(ParkingMapPoint(session(location: location(accuracy: 100))))

        // Act
        let span = point.spanMeters

        // Assert — the circle is 200m across; the frame must be wider than that.
        #expect(span == 600)
        #expect(span > point.accuracyMeters * 2)
    }

    /// Edge case: a 3m fix would otherwise frame a single doorway with no street to
    /// orient by.
    @Test("A very precise fix still gets a readable frame")
    func spanHasAFloor() throws {
        // Arrange
        let point = try #require(ParkingMapPoint(session(location: location(accuracy: 3))))

        // Act / Assert
        #expect(point.spanMeters == ParkingMapPoint.minimumSpanMeters)
    }

    /// Edge case: a cell-tower-grade fix underground should not zoom out to a province.
    @Test("A very vague fix is capped rather than zooming out to nothing")
    func spanHasACeiling() throws {
        // Arrange
        let point = try #require(ParkingMapPoint(session(location: location(accuracy: 5000))))

        // Act / Assert
        #expect(point.spanMeters == ParkingMapPoint.maximumSpanMeters)
    }

    // ── Wording (FR-008, docs/04 §9) ────────────────────────────────────────

    /// `차량 정확한 위치` is forbidden: underground the accuracy is routinely 40m+, and
    /// claiming the exact spot would be a lie the UI told for free.
    @Test("The map label is 마지막으로 확인된 위치 and never claims the exact car position")
    func usesTheMandatedWording() throws {
        // Arrange
        let point = try #require(ParkingMapPoint(session(location: location(accuracy: 18))))

        // Act
        let caption = point.captionText

        // Assert
        #expect(ParkingMapPoint.label == "마지막으로 확인된 위치")
        #expect(caption == "마지막으로 확인된 위치 · 약 18m 이내")
        for forbidden in ["정확한 위치", "차량 위치", "여기에 주차"] {
            #expect(!caption.contains(forbidden))
        }
    }

    @Test("The accuracy is rounded to a whole metre for display")
    func roundsAccuracyForDisplay() throws {
        // Arrange
        let point = try #require(ParkingMapPoint(session(location: location(accuracy: 17.6))))

        // Act / Assert
        #expect(point.accuracyText == "약 18m 이내")
    }

    // ── 길찾기 handoff ───────────────────────────────────────────────────────

    @Test("The directions link asks Apple Maps for walking directions to the point")
    func buildsWalkingDirectionsURL() throws {
        // Arrange
        let point = try #require(ParkingMapPoint(session(location: location())))

        // Act
        let url = try #require(ParkingDirections.url(for: point))

        // Assert
        #expect(url.absoluteString.contains("maps.apple.com"))
        #expect(url.absoluteString.contains("daddr=37.123457,127.987654"))
        #expect(url.absoluteString.contains("dirflg=w"))
    }

    /// A device set to a comma-decimal locale must not produce `37,123457` and send the
    /// user to a different continent.
    @Test("The coordinate is formatted the same whatever the device locale is")
    func formatsCoordinateLocaleIndependently() throws {
        // Arrange
        let point = try #require(ParkingMapPoint(session(location: location(latitude: 37.5, longitude: 127.5))))

        // Act
        let url = try #require(ParkingDirections.url(for: point))

        // Assert — one comma, and it is the one separating the pair.
        #expect(url.absoluteString.contains("daddr=37.500000,127.500000"))
        #expect(url.absoluteString.filter { $0 == "," }.count == 1)
    }
}

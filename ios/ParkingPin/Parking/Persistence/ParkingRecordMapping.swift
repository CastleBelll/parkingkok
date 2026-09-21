import Foundation

/// Translation between the persisted row and the domain value.
///
/// Kept in one file so the two directions cannot drift: every field added to docs/06 §2
/// has to appear on both sides of this file or the compiler complains.
extension ParkingSession {
    init(_ record: ParkingRecord) {
        self.init(
            id: record.id,
            startedAt: record.startedAt,
            endedAt: record.endedAt,
            // An unreadable value from a newer build degrades to `manual` rather than
            // dropping the record: the user's parking still shows up, just without the
            // badge that says how it was created.
            source: ParkingSource(rawValue: record.source) ?? .manual,
            confidenceBucket: record.confidenceBucket.flatMap(ConfidenceBucket.init(rawValue:)),
            location: Self.location(from: record),
            floor: Self.floor(from: record),
            zone: record.zone,
            spot: record.spot,
            memo: record.memo,
            photoRelativePath: record.photoRelativePath,
            createdAt: record.createdAt,
            updatedAt: record.updatedAt
        )
    }

    /// All four columns or none: a coordinate without its accuracy cannot be drawn
    /// honestly, and FR-008's `마지막으로 확인된 위치` copy depends on having the accuracy.
    private static func location(from record: ParkingRecord) -> ParkedLocation? {
        guard let latitude = record.latitude,
              let longitude = record.longitude,
              let horizontalAccuracy = record.horizontalAccuracy,
              let capturedAt = record.locationCapturedAt
        else {
            return nil
        }
        return ParkedLocation(
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy,
            capturedAt: capturedAt
        )
    }

    private static func floor(from record: ParkingRecord) -> FloorValue? {
        guard let raw = record.floorRaw else { return nil }
        guard let kind = record.floorKind.flatMap(FloorKind.init(rawValue:)) else {
            // Stored with a kind this build does not know: keep the user's text, drop
            // the stepper. Better a floor that cannot be nudged than a lost floor.
            return FloorValue.stored(raw: raw, kind: .freeText, number: nil)
        }
        return FloorValue.stored(raw: raw, kind: kind, number: record.floorNumber)
    }

    /// FR-006 caps zone and spot at 40 characters; blank text is stored as absence so
    /// `nil` and `""` never both mean "no zone".
    func sanitized() -> ParkingSession {
        var copy = self
        copy.zone = Self.trimmed(zone, limit: Self.maximumFieldLength)
        copy.spot = Self.trimmed(spot, limit: Self.maximumFieldLength)
        copy.memo = Self.trimmed(memo, limit: Self.maximumMemoLength)
        return copy
    }

    private static func trimmed(_ value: String?, limit: Int) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(limit))
    }
}

extension ParkingRecord {
    convenience init(_ session: ParkingSession) {
        self.init(
            id: session.id,
            startedAt: session.startedAt,
            endedAt: session.endedAt,
            source: session.source.rawValue,
            confidenceBucket: session.confidenceBucket?.rawValue,
            latitude: session.location?.latitude,
            longitude: session.location?.longitude,
            horizontalAccuracy: session.location?.horizontalAccuracy,
            locationCapturedAt: session.location?.capturedAt,
            floorRaw: session.floor?.raw,
            floorKind: session.floor?.kind.rawValue,
            floorNumber: session.floor?.number,
            zone: session.zone,
            spot: session.spot,
            memo: session.memo,
            photoRelativePath: session.photoRelativePath,
            createdAt: session.createdAt,
            updatedAt: session.updatedAt
        )
    }

    /// Writes the mutable half of a session onto an existing row. `id`, `startedAt`,
    /// `source` and `createdAt` are not here on purpose — they are what makes this the
    /// same parking (docs/06 §8 keeps the id across the completion commit).
    func apply(_ session: ParkingSession, updatedAt: Date) {
        endedAt = session.endedAt
        confidenceBucket = session.confidenceBucket?.rawValue
        latitude = session.location?.latitude
        longitude = session.location?.longitude
        horizontalAccuracy = session.location?.horizontalAccuracy
        locationCapturedAt = session.location?.capturedAt
        floorRaw = session.floor?.raw
        floorKind = session.floor?.kind.rawValue
        floorNumber = session.floor?.number
        zone = session.zone
        spot = session.spot
        memo = session.memo
        photoRelativePath = session.photoRelativePath
        self.updatedAt = updatedAt
    }
}

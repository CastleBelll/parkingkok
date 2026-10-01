import Foundation

/// Where the car went last time it was left here (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §18).
///
/// The same office, the same mart: people park where they parked before, so the floor and
/// zone of the newest finished parking near this one are the likeliest answer. Offered,
/// never written — §6a's rule for a guess holds whether the guess came from a camera or
/// from history, which is why the answer is a `PillarReading`: one shape for "fill these
/// blanks if the user agrees", whatever produced it.
///
/// The spot is never carried over. A bay number is the one field that is almost always
/// different the next time.
enum UsualSpotLookup {
    /// Far enough to cover one large car park, near enough not to reach the next one.
    static let maximumDistanceMeters = 150.0

    /// A fix vaguer than this cannot say which car park it was in.
    static let maximumAccuracyMeters = 100.0

    /// History is read newest first; a match older than this many parkings is not "usual".
    static let historyScanLimit = 200

    /// The newest of `history` (newest first) parked within `maximumDistanceMeters` of
    /// `here` that says a floor or a zone; nil when there is none or `here` is too vague.
    static func find(near here: ParkedLocation?, in history: [ParkingSession]) -> PillarReading? {
        guard let here, isPreciseEnough(here) else { return nil }
        let match = history.prefix(historyScanLimit).first { session in
            guard session.floor != nil || session.zone.isPresent else { return false }
            guard let there = session.location, isPreciseEnough(there) else { return false }
            return distanceMeters(here, there) <= maximumDistanceMeters
        }
        guard let match else { return nil }
        return PillarReading(floorText: match.floor?.raw, zone: match.zone.presentValue)
    }

    /// What `session` left blank, from the parking here before it; nil when nothing is
    /// left to offer — a record that already says `B3` is not second-guessed (§6a).
    static func find(for session: ParkingSession, in history: [ParkingSession]) -> PillarReading? {
        let hasFloor = session.floor != nil
        let hasZone = session.zone.isPresent
        guard !(hasFloor && hasZone) else { return nil }
        let others = history.filter { $0.id != session.id }
        guard let usual = find(near: session.location, in: others) else { return nil }
        guard agrees(session.floor, usualFloorText: usual.floorText) else { return nil }
        let offer = PillarReading(
            floorText: hasFloor ? nil : usual.floorText,
            zone: hasZone ? nil : usual.zone
        )
        return offer.isEmpty ? nil : offer
    }

    /// Whether a floor already given agrees with last time's. A zone belongs to its floor,
    /// so a parking on B3 is not offered the `C구역` of a B2 one: the offer is all or
    /// nothing. Nothing given yet agrees with anything.
    static func agrees(_ given: FloorValue?, usualFloorText: String?) -> Bool {
        guard let usual = usualFloorText.flatMap(FloorValue.parse) else { return given == nil }
        guard let given else { return true }
        guard given.kind == usual.kind else { return false }
        if given.kind == .freeText {
            return given.raw.trimmingCharacters(in: .whitespaces) == usual.raw.trimmingCharacters(in: .whitespaces)
        }
        return given.number == usual.number
    }

    /// Zero or negative is CoreLocation's "unknown", and an unstated accuracy is not a
    /// precise one: §18 would rather offer nothing than name the wrong car park.
    private static func isPreciseEnough(_ location: ParkedLocation) -> Bool {
        location.horizontalAccuracy > 0 && location.horizontalAccuracy <= maximumAccuracyMeters
    }

    private static func distanceMeters(_ from: ParkedLocation, _ to: ParkedLocation) -> Double {
        GeoDistance.meters(
            fromLatitude: from.latitude,
            fromLongitude: from.longitude,
            toLatitude: to.latitude,
            toLongitude: to.longitude
        )
    }
}

extension PillarReading {
    /// `B2 · A구역`: what §18 shows on the form row and the home card.
    var floorAndZoneLabel: String {
        [floorText, zone].compactMap(\.self).joined(separator: " · ")
    }
}

private extension String? {
    /// A zone of only whitespace is no zone: the form stores what was typed.
    var presentValue: String? {
        guard let self, !self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return self
    }

    var isPresent: Bool {
        presentValue != nil
    }
}

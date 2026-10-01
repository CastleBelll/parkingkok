import Foundation

/// docs/05 §11d "A ride in someone else's car is not a departure" (2026-10-01).
///
/// The user's car stays parked while they ride in another — a colleague's, a taxi. Without
/// this the drive read as the parked car leaving: §11 asked whether the parking was over, and
/// the parking at the ride's end was offered as the user's own.
///
/// The one thing that tells the two apart without any setup: **the user's own car starts
/// where it is parked.** A departure's fixes arrive a little after the car moved off, so the
/// test allows the distance a car could have covered since vehicle activity began, plus both
/// fixes' uncertainty. A fix beyond that cannot be this car leaving its spot.
///
/// What it cannot see, written down so nobody mistakes it for a guarantee: a ride that starts
/// next to the parked car (a friend parked in the same garage), and any parking saved without
/// a location — both are treated as the user's own departure, as before.
enum PassengerRidePolicy {
    /// Slack for a garage exit and a first fix that lands down the road. **unvalidated**
    static let baseMeters: Double = 250
    /// The speed the allowance grows at: 90 km/h, faster than a car leaves a car park.
    /// **unvalidated**
    static let maximumSpeed: Double = 25

    /// Whether `fix` puts the vehicle somewhere the parked car could not have reached.
    static func isElsewhere(fix: LocationFix, parked: LastReliableLocation, vehicleStartedAt: Date) -> Bool {
        guard fix.isValid, parked.horizontalAccuracy >= 0 else { return false }
        let elapsed = max(0, fix.timestamp.timeIntervalSince(vehicleStartedAt))
        let allowance = baseMeters + parked.horizontalAccuracy + fix.horizontalAccuracy + maximumSpeed * elapsed
        let parkedFix = LocationFix(
            timestamp: parked.capturedAt,
            latitude: parked.latitude,
            longitude: parked.longitude,
            horizontalAccuracy: parked.horizontalAccuracy
        )
        return GeoDistance.meters(from: parkedFix, to: fix) > allowance
    }
}

package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.GeoDistance
import com.sjstudioz.parkingpin.domain.location.LocationSample

/**
 * docs/05 §11d "A ride in someone else's car is not a departure" (2026-10-01). iOS
 * `PassengerRidePolicy`, the same constants and the same test.
 *
 * The user's own car starts where it is parked. A departure's fixes arrive a little after the
 * car moved off, so the test allows the distance a car could have covered since vehicle
 * activity began, plus both fixes' uncertainty; a fix beyond that cannot be this car leaving
 * its spot. It cannot see a ride that starts next to the parked car, nor any parking saved
 * without a location — both are treated as the user's own departure, as before.
 */
object PassengerRidePolicy {

    /** Slack for a garage exit and a first fix that lands down the road. **unvalidated** */
    const val BASE_METERS: Double = 250.0

    /** 90 km/h, faster than a car leaves a car park. **unvalidated** */
    const val MAXIMUM_SPEED_MPS: Double = 25.0

    fun isElsewhere(fix: LocationSample, parked: ReliableLocation, vehicleStartedAtMillis: Long): Boolean {
        if (!fix.quality.isValid || parked.horizontalAccuracyM < 0f) return false
        val elapsedSeconds = maxOf(0L, fix.atMillis - vehicleStartedAtMillis) / MILLIS_PER_SECOND
        val allowance = BASE_METERS + parked.horizontalAccuracyM + fix.horizontalAccuracyM +
            MAXIMUM_SPEED_MPS * elapsedSeconds
        val distance = GeoDistance.meters(parked.latitude, parked.longitude, fix.latitude, fix.longitude)
        return distance > allowance
    }

    private const val MILLIS_PER_SECOND = 1_000.0
}

package com.parkingpin.app.domain.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance in metres.
 *
 * Implemented here rather than via `android.location.Location.distanceBetween` so the
 * driving-distance guard stays a pure domain rule that JVM unit tests can exercise; the
 * platform helper returns 0 under the unit-test stub and would make those tests
 * meaningless.
 *
 * Haversine is accurate to well under a metre at the few-hundred-metre scale §7's
 * 800 m guard works at, which is all this is used for.
 */
object GeoDistance {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun meters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val deltaLatitude = Math.toRadians(toLatitude - fromLatitude)
        val deltaLongitude = Math.toRadians(toLongitude - fromLongitude)
        val fromLatitudeRadians = Math.toRadians(fromLatitude)
        val toLatitudeRadians = Math.toRadians(toLatitude)

        val haversine = sin(deltaLatitude / 2).let { it * it } +
            cos(fromLatitudeRadians) * cos(toLatitudeRadians) * sin(deltaLongitude / 2).let { it * it }
        // Clamped because floating-point error can push the term a hair above 1 and make
        // asin return NaN for two effectively identical points.
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(haversine)))
    }
}

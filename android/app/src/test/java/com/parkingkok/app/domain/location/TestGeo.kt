package com.parkingkok.app.domain.location

import kotlin.math.PI

/**
 * Builds fixes along a single meridian, so "how far did it move" is a number the test
 * writes rather than one it has to trust a projection for.
 *
 * The metres-per-degree figure is derived from the same sphere [GeoDistance] uses, which
 * makes the round trip exact: a fix built at `metersNorth = 900.0` measures 900 m from the
 * origin to well under a millimetre.
 */
object TestGeo {

    /** Seoul City Hall, near enough. Only the *offsets* matter to any assertion. */
    const val ORIGIN_LATITUDE: Double = 37.5665
    const val ORIGIN_LONGITUDE: Double = 126.9780

    const val METERS_PER_DEGREE_LATITUDE: Double = 6_371_008.8 * PI / 180

    fun sample(
        atMillis: Long,
        metersNorth: Double = 0.0,
        accuracyM: Float = 10f,
        speedMps: Float? = null,
    ): LocationSample = LocationSample(
        atMillis = atMillis,
        latitude = ORIGIN_LATITUDE + metersNorth / METERS_PER_DEGREE_LATITUDE,
        longitude = ORIGIN_LONGITUDE,
        horizontalAccuracyM = accuracyM,
        speedMps = speedMps,
    )
}

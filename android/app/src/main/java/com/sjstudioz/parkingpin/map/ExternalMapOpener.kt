package com.sjstudioz.parkingpin.map

import android.content.ActivityNotFoundException
import com.sjstudioz.parkingpin.domain.map.ParkingMapLink
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation

/** What happened when `길찾기` was pressed. */
enum class MapOpenResult {

    /** A maps app took the intent. */
    OPENED,

    /**
     * The record has no coordinate, so there is nowhere to send anyone.
     *
     * An ordinary outcome: a manual save with location permission denied is fully
     * supported (FR-001), and the screen disables the action rather than reporting a fault.
     */
    NO_LOCATION,

    /** No installed app answers `geo:`. */
    NO_MAPS_APP,
}

/** Hands a `geo:` URI to the system. Throws [ActivityNotFoundException] when nothing takes it. */
fun interface MapUriStarter {
    fun start(uri: String)
}

/**
 * The `길찾기` action of `03-parking-detail.png`.
 *
 * FR-008 on Android is an external maps intent (docs/04_ANDROID_IMPLEMENTATION.md §12),
 * so the whole feature is: build a URI, hand it over, survive there being no taker.
 *
 * ## Why this catches instead of asking first
 *
 * `PackageManager.resolveActivity` is the obvious pre-flight check and it is the wrong
 * one here: since API 30, package visibility hides apps this app has not declared a
 * `<queries>` entry for, so it can answer "nothing handles this" about a maps app that is
 * installed and would have handled it. Declaring the query to un-hide them buys a manifest
 * entry and a Play listing question in exchange for information that catching the throw
 * gives for free and correctly.
 */
class ExternalMapOpener(
    private val starter: MapUriStarter,
    private val pinLabel: String,
) {

    fun openDirections(location: ParkingLocation?): MapOpenResult {
        val uri = location
            ?.let { ParkingMapLink.directionsUri(it.latitude, it.longitude, pinLabel) }
            ?: return MapOpenResult.NO_LOCATION

        return try {
            starter.start(uri)
            MapOpenResult.OPENED
        } catch (_: ActivityNotFoundException) {
            MapOpenResult.NO_MAPS_APP
        }
    }
}

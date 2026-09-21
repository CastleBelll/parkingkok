package com.parkingpin.app.domain.map

import java.net.URLEncoder
import java.util.Locale

/**
 * The `geo:` URI the `길찾기` action hands to whichever maps app the user has.
 *
 * FR-008 on Android is an external maps intent, not an embedded map
 * (docs/04_ANDROID_IMPLEMENTATION.md §12, decision of 2026-09-18). That decision is why
 * this file builds a string instead of configuring a map view: no API key, no Data Safety
 * disclosure, and no tile request carrying the coordinate off the device.
 *
 * `geo:` rather than `google.navigation:` so any installed maps app can answer, and the
 * `q=` form so the app drops a labelled pin rather than merely centring the camera.
 *
 * ## What may go in the label
 *
 * Nothing from the record. docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §1 classifies floor,
 * zone, spot and memo as sensitive local-only data, and a pin label is handed to a
 * foreign app that may log it, cache it or sync it. The caller passes a fixed, generic
 * string; [MAX_LABEL_LENGTH] bounds whatever it is.
 */
object ParkingMapLink {

    /** A label longer than this is a bug upstream, not something to pass along. */
    const val MAX_LABEL_LENGTH: Int = 32

    /**
     * Seven decimal places is ~1cm — finer than any consumer GNSS fix, and far finer than
     * the underground accuracy this app is honest about.
     */
    private const val COORDINATE_FORMAT = "%.7f"

    /**
     * The URI for [latitude]/[longitude], or null when the pair is not a point on Earth.
     *
     * A corrupt row returning null rather than a malformed URI is deliberate: the screen
     * then shows `길찾기` as unavailable, which is true, instead of opening a maps app on
     * null island.
     */
    fun directionsUri(latitude: Double, longitude: Double, label: String): String? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in MIN_LATITUDE..MAX_LATITUDE) return null
        if (longitude !in MIN_LONGITUDE..MAX_LONGITUDE) return null

        val point = "${format(latitude)},${format(longitude)}"
        val encoded = encodeLabel(label)
        return if (encoded == null) "geo:$point?q=$point" else "geo:$point?q=$point($encoded)"
    }

    /** Locale.ROOT so a Korean or Arabic locale cannot produce a decimal comma. */
    private fun format(value: Double): String = String.format(Locale.ROOT, COORDINATE_FORMAT, value)

    private fun encodeLabel(label: String): String? {
        val trimmed = label.trim().take(MAX_LABEL_LENGTH)
        if (trimmed.isEmpty()) return null
        // URLEncoder is form encoding, which spells a space `+`; inside a URI path-like
        // segment that has to be %20 or the maps app shows the plus sign.
        return URLEncoder.encode(trimmed, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("(", "%28")
            .replace(")", "%29")
    }

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
}

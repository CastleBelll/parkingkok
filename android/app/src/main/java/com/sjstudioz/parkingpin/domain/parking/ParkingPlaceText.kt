package com.sjstudioz.parkingpin.domain.parking

/**
 * The zone/spot line under the floor — one rule for every surface that shows it: the home
 * hero, the detail screen, and the departure question's notification and home row
 * (docs/05 §11a: the place text is the same on both platforms).
 *
 * Mirrors iOS `ParkingSession.placeText(zone:spot:)` string for string. Not a Composable so
 * a notification built off the main thread uses the same function the UI does.
 */
object ParkingPlaceText {

    private const val BAY_WORD = "번"
    private const val SEPARATOR = " · "

    /**
     * `A구역 · 142`, the zone alone, or `142번` for a spot alone — or null when the record
     * holds neither.
     *
     * A bare number under the floor says nothing about what the number is, so a spot with
     * no zone beside it takes the word. FR-006 lets the field hold any text, and someone
     * copying a wall writes `01번` as often as `01`, so the word is added only when it is not
     * already there.
     */
    fun zoneAndSpot(zone: String?, spot: String?): String? = when {
        zone != null && spot != null -> "$zone$SEPARATOR$spot"
        zone != null -> zone
        spot != null -> if (spot.endsWith(BAY_WORD)) spot else "$spot$BAY_WORD"
        else -> null
    }
}

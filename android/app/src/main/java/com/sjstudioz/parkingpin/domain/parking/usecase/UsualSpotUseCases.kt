package com.sjstudioz.parkingpin.domain.parking.usecase

import com.sjstudioz.parkingpin.domain.location.GeoDistance
import com.sjstudioz.parkingpin.domain.parking.Floor
import com.sjstudioz.parkingpin.domain.parking.FloorKind
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import com.sjstudioz.parkingpin.domain.photo.PillarSuggestion
import kotlinx.coroutines.flow.first

/**
 * Where the car went last time it was left here (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §18).
 *
 * The same office, the same mart: people park where they parked before, so the floor and
 * zone of the newest completed record near this one are the likeliest answer. Offered,
 * never written — a mart's floor changes from visit to visit, and §6a's rule for a guess
 * ("a suggestion, never a saved value") holds whether the guess came from a camera or from
 * history. That is why the answer is a [PillarSuggestion]: one shape for "fill these blanks
 * if the user agrees", whatever produced it.
 *
 * The spot is never carried over. A bay number is the one field that is almost always
 * different the next time, and filling it would be wrong more often than right.
 */
object UsualSpotLookup {

    /** Far enough to cover one large car park, near enough not to reach the next one. */
    const val MAX_DISTANCE_METERS = 150.0

    /** A fix vaguer than this cannot say which car park it was in. */
    const val MAX_ACCURACY_METERS = 100f

    /** History is read newest first; a match older than this many parkings is not "usual". */
    const val HISTORY_SCAN_LIMIT = 200

    /**
     * The newest record in [history] (newest first) that was parked within
     * [MAX_DISTANCE_METERS] of [here] and says a floor or a zone, as a suggestion; null
     * when there is none or [here] is too vague to compare.
     */
    fun find(here: ParkingLocation?, history: List<ParkingRecord>): PillarSuggestion? {
        if (here == null || !here.isPreciseEnough()) return null
        return history.asSequence()
            .filter { it.floor != null || it.zone != null }
            .firstOrNull { record ->
                val there = record.location ?: return@firstOrNull false
                there.isPreciseEnough() && distanceMeters(here, there) <= MAX_DISTANCE_METERS
            }
            ?.let { PillarSuggestion(floorRaw = it.floor?.raw, zone = it.zone) }
    }

    /**
     * Whether a floor already given agrees with last time's. A zone belongs to its floor, so
     * a parking on B3 is not offered the `C구역` of a B2 one: the offer is all or nothing.
     * Nothing given yet agrees with anything.
     */
    fun agrees(given: Floor?, usualFloorRaw: String?): Boolean {
        val usual = FloorParser.parse(usualFloorRaw) ?: return given == null
        if (given == null) return true
        if (given.kind != usual.kind) return false
        return if (given.kind == FloorKind.FREE_TEXT) given.raw.trim() == usual.raw.trim() else given.number == usual.number
    }

    // An unstated accuracy is not a precise one: the record may be from a fix that never
    // said how good it was, and §18 would rather offer nothing than the wrong car park.
    private fun ParkingLocation.isPreciseEnough(): Boolean =
        horizontalAccuracyM?.let { it <= MAX_ACCURACY_METERS } ?: false

    private fun distanceMeters(from: ParkingLocation, to: ParkingLocation): Double =
        GeoDistance.meters(from.latitude, from.longitude, to.latitude, to.longitude)
}

/**
 * [UsualSpotLookup] against the stored history.
 *
 * Two arrivals, one rule: an empty manual form asks about a location; a parking already
 * saved asks about itself, and is offered only what it left blank — the same "a record
 * that already says `B3` is not second-guessed" rule as [SuggestFromPillarPhotoUseCase].
 */
class SuggestUsualSpotUseCase(private val repository: ParkingRepository) {

    suspend fun forLocation(here: ParkingLocation?): PillarSuggestion? {
        if (here == null) return null
        return UsualSpotLookup.find(here, history())
    }

    suspend fun forRecord(record: ParkingRecord): PillarSuggestion? {
        if (record.floor != null && record.zone != null) return null
        val usual = UsualSpotLookup.find(record.location, history().filter { it.id != record.id })
            ?: return null
        if (!UsualSpotLookup.agrees(record.floor, usual.floorRaw)) return null
        return PillarSuggestion(
            floorRaw = usual.floorRaw.takeIf { record.floor == null },
            zone = usual.zone.takeIf { record.zone == null },
        ).takeUnless { it.isEmpty }
    }

    private suspend fun history(): List<ParkingRecord> =
        repository.observeCompleted(UsualSpotLookup.HISTORY_SCAN_LIMIT).first()
}

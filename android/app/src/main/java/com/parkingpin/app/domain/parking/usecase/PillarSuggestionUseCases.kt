package com.parkingpin.app.domain.parking.usecase

import com.parkingpin.app.core.Clock
import com.parkingpin.app.domain.parking.FloorParser
import com.parkingpin.app.domain.parking.ParkingRecord
import com.parkingpin.app.domain.parking.ParkingRepository
import com.parkingpin.app.domain.photo.PhotoSource
import com.parkingpin.app.domain.photo.PillarSuggestion
import com.parkingpin.app.domain.photo.ReadPillarSuggestionUseCase

/**
 * What a photo attached from home or detail said that the record does not already say
 * (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a).
 *
 * §6a is explicit that these two screens are different from the confirmation screen:
 * there is no editable floor or zone field for a suggestion to land in, so it is offered
 * **only for the fields the record leaves empty**, and applied only when tapped. "A
 * record that already says `B3` is not second-guessed by a photo" — re-asking about a
 * value the user already gave is how a helpful feature becomes a nagging one.
 */
class SuggestFromPillarPhotoUseCase(
    private val repository: ParkingRepository,
    private val readPillar: ReadPillarSuggestionUseCase,
) {

    /** [PillarSuggestion.NONE] whenever there is nothing worth offering. */
    suspend operator fun invoke(recordId: String, source: PhotoSource): PillarSuggestion {
        val record = repository.find(recordId) ?: return PillarSuggestion.NONE
        val read = readPillar(source)
        return PillarSuggestion(
            floorRaw = read.floorRaw.takeIf { record.floor == null },
            zone = read.zone.takeIf { record.zone == null },
            spot = read.spot.takeIf { record.spot == null },
        )
    }
}

/**
 * Writes an offered suggestion, on the user's tap and never before
 * (docs/02 §6a "never a saved value").
 *
 * The blank check is repeated inside the transaction rather than trusted from the offer:
 * the user may have set the floor from the stepper between the photo and the tap, and a
 * value a person chose outranks one a camera read.
 */
class ApplyPillarSuggestionUseCase(
    private val repository: ParkingRepository,
    private val clock: Clock,
) {

    suspend operator fun invoke(recordId: String, suggestion: PillarSuggestion): ParkingRecord? {
        if (suggestion.isEmpty) return null
        val now = clock.nowEpochMillis()
        return repository.update(recordId) { record ->
            record.copy(
                floor = record.floor ?: FloorParser.parse(suggestion.floorRaw),
                zone = record.zone ?: suggestion.zone,
                spot = record.spot ?: suggestion.spot,
                updatedAtMillis = now,
                revision = record.revision + 1,
            )
        }
    }
}

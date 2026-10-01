package com.sjstudioz.parkingpin.domain.parking.usecase

import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingFieldLimits
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository

/**
 * `수정` on the detail screen: the floor, zone, spot and memo of any record, active or
 * finished (audit 2026-10-01 — Android had no way to correct a record, so a detected parking
 * confirmed without a floor could never be given one). iOS `ManualParkingSheet(editing:)`.
 *
 * What the user typed replaces what was there, blanks included: clearing a wrong zone is an
 * edit too. Time, source, location and photo are not this form's to change.
 */
class EditParkingDetailsUseCase(
    private val repository: ParkingRepository,
    private val clock: Clock,
) {

    /** The stored result, or null when the record is gone. */
    suspend operator fun invoke(recordId: String, input: ManualParkingInput): ParkingRecord? {
        val now = clock.nowEpochMillis()
        return repository.update(recordId) { record ->
            record.copy(
                floor = FloorParser.parse(input.floorRaw),
                zone = ParkingFieldLimits.normalize(input.zone, ParkingFieldLimits.MAX_SHORT_FIELD),
                spot = ParkingFieldLimits.normalize(input.spot, ParkingFieldLimits.MAX_SHORT_FIELD),
                memo = ParkingFieldLimits.normalize(input.memo, ParkingFieldLimits.MAX_MEMO),
                updatedAtMillis = now,
                revision = record.revision + 1,
            )
        }
    }
}

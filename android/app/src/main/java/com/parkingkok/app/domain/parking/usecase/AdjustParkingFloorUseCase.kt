package com.parkingkok.app.domain.parking.usecase

import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingRepository

/**
 * The home screen's `-` / `+` keys (docs/10_DESIGN_UX_SPEC.md §6 item 4).
 *
 * FR-005 restricts stepping to floors the parser could read, so a free-text floor is left
 * exactly as typed and the call reports that nothing moved.
 */
class AdjustParkingFloorUseCase(
    private val repository: ParkingRepository,
    private val clock: Clock,
) {

    /**
     * Steps the active record's floor by [delta] levels.
     *
     * Returns the updated record, or null when there is no active parking, when its floor
     * is not steppable, or when the step would leave the floor ladder.
     */
    suspend operator fun invoke(delta: Int): ParkingRecord? {
        val active = repository.findActive() ?: return null
        if (FloorParser.step(active.floor, delta) == null) return null

        val now = clock.nowEpochMillis()
        var moved = false
        val updated = repository.update(active.id) { record ->
            // Recomputed from the stored record, not from `active`: the floor may have
            // been stepped again while this call was in flight.
            val next = FloorParser.step(record.floor, delta)
            if (next == null) {
                record
            } else {
                moved = true
                record.copy(floor = next, updatedAtMillis = now, revision = record.revision + 1)
            }
        }
        return updated.takeIf { moved }
    }
}

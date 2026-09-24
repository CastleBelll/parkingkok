package com.sjstudioz.parkingpin.domain.parking.usecase

import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository

/**
 * Closes the open session — `주차 종료` on the home and detail screens.
 *
 * Ending is an update in place, not a move between tables: docs/06 §8's "insert completed
 * record with same sessionId, then clear active" sequence exists to keep two stores in
 * step, and this app keeps one (see `ParkingDatabase`). Stamping `endedAt` is the whole
 * commit, so there is no window in which the record is in both states or neither.
 */
class EndParkingUseCase(
    private val repository: ParkingRepository,
    private val clock: Clock,
) {

    /**
     * The closed record, or null when nothing was open.
     *
     * [endedAtMillis] is when the parking actually ended. The manual 주차 종료 button leaves
     * it null and means "now"; §11's automatic departure passes the moment the car pulled
     * away, which is minutes before the engine could be sure of it. Stamping "now" there
     * would put the end of the parking somewhere down the road.
     */
    suspend operator fun invoke(endedAtMillis: Long? = null): ParkingRecord? {
        val active = repository.findActive() ?: return null
        val now = clock.nowEpochMillis()
        val endedAt = endedAtMillis ?: now
        return repository.update(active.id) { record ->
            // Re-checked inside the transaction: a widget or a second screen may have
            // closed it between findActive and here, and the first end is the real one.
            if (record.endedAtMillis != null) {
                record
            } else {
                record.copy(
                    // A clock that moved backwards must not produce a negative duration.
                    endedAtMillis = maxOf(endedAt, record.startedAtMillis),
                    updatedAtMillis = now,
                    revision = record.revision + 1,
                )
            }
        }
    }
}

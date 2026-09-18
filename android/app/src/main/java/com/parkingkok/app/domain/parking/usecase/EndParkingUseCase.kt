package com.parkingkok.app.domain.parking.usecase

import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingRepository

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

    /** The closed record, or null when nothing was open. */
    suspend operator fun invoke(): ParkingRecord? {
        val active = repository.findActive() ?: return null
        val now = clock.nowEpochMillis()
        return repository.update(active.id) { record ->
            // Re-checked inside the transaction: a widget or a second screen may have
            // closed it between findActive and here, and the first end is the real one.
            if (record.endedAtMillis != null) {
                record
            } else {
                record.copy(
                    // A clock that moved backwards must not produce a negative duration.
                    endedAtMillis = maxOf(now, record.startedAtMillis),
                    updatedAtMillis = now,
                    revision = record.revision + 1,
                )
            }
        }
    }
}

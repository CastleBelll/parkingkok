package com.parkingkok.app.domain.parking.usecase

import com.parkingkok.app.domain.parking.Floor
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingRepository
import com.parkingkok.app.domain.parking.RecentFloorPicks
import kotlinx.coroutines.flow.Flow

/**
 * The read side of the parking feature.
 *
 * These are thin by design. docs/03_SYSTEM_ARCHITECTURE.md §5 routes every ViewModel
 * through a use case, and keeping the reads on that path means a later rule — the FR-009
 * free/Plus history cap, say — lands in one place instead of in three ViewModels.
 */
class ObserveActiveParkingUseCase(private val repository: ParkingRepository) {
    operator fun invoke(): Flow<ParkingRecord?> = repository.observeActive()
}

class ObserveParkingHistoryUseCase(private val repository: ParkingRepository) {

    /**
     * Completed records, newest first.
     *
     * [limit] defaults to [ALL]. FR-009's "free shows the latest 5" is not applied yet:
     * there is no subscription in this build, and showing five while claiming nothing
     * about Plus would just hide the user's own data from them.
     */
    operator fun invoke(limit: Int = ALL): Flow<List<ParkingRecord>> =
        repository.observeCompleted(limit)

    companion object {
        /** No cap. Room takes a negative LIMIT to mean "all rows". */
        const val ALL: Int = -1

        /** How many rows the home screen previews (docs/10_DESIGN_UX_SPEC.md §6 item 7). */
        const val HOME_PREVIEW: Int = 3
    }
}

class ObserveParkingRecordUseCase(private val repository: ParkingRepository) {
    operator fun invoke(id: String): Flow<ParkingRecord?> = repository.observeRecord(id)
}

/**
 * The floor buttons on the confirmation screen (docs/10_DESIGN_UX_SPEC.md §7a).
 *
 * A read, not a guess: it returns what the user has actually saved before, and nothing
 * when they have saved nothing. [RecentFloorPicks] holds the rule; this supplies it with
 * history.
 */
class RecentFloorPicksUseCase(private val repository: ParkingRepository) {

    suspend operator fun invoke(limit: Int = RecentFloorPicks.MAX_PICKS): List<Floor> =
        RecentFloorPicks.of(repository.recentFloorRaws(HISTORY_SCAN), limit)

    private companion object {
        /**
         * How many rows are read to find [RecentFloorPicks.MAX_PICKS] distinct floors.
         *
         * Larger than the three buttons because a user who parked on B3 six times running
         * would otherwise be offered one button and two blanks. Bounded because this runs
         * while a notification is being answered and an unbounded scan of a year of
         * history is not what that moment is for.
         */
        const val HISTORY_SCAN: Int = 30
    }
}

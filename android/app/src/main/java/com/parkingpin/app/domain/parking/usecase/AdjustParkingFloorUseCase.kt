package com.parkingpin.app.domain.parking.usecase

import com.parkingpin.app.core.Clock
import com.parkingpin.app.domain.parking.FloorParser
import com.parkingpin.app.domain.parking.ParkingRecord
import com.parkingpin.app.domain.parking.ParkingRepository

/**
 * The `-` / `+` keys — the home screen's (docs/10_DESIGN_UX_SPEC.md §6 item 4) and the
 * widget's (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a).
 *
 * FR-005 restricts stepping to floors the parser could read, so a free-text floor is left
 * exactly as typed and the call reports that nothing moved.
 *
 * ## Why a delta and not a floor
 *
 * docs/06 §7a is explicit that a stepper carries `+1`/`-1` and never a resulting value.
 * Each press re-reads inside the transaction and applies its own step, so two presses
 * landing together move two floors; a call carrying "the floor should now be B4" would
 * have silently discarded whichever write lost the race.
 */
class AdjustParkingFloorUseCase(
    private val repository: ParkingRepository,
    private val clock: Clock,
) {

    /**
     * Steps a record's floor by [delta] levels.
     *
     * [sessionId] names the session the caller was looking at when it decided to step. A
     * caller with a live view of the record — the home screen — passes null and means
     * "whatever is open now". The widget passes the id it rendered, because minutes can
     * pass between the draw and the tap: docs/06 §7a requires that a tap arriving after
     * that session ended is dropped, never applied to the session that came next.
     *
     * Returns the updated record, or null when there is nothing to step, when the named
     * session is gone or already closed, when its floor is free text, or when the step
     * would run off either end of the floor ladder. The bounds case is deliberately a
     * silent no-op: §7a wants the display to snap back to the true value rather than
     * appear to have moved.
     */
    suspend operator fun invoke(delta: Int, sessionId: String? = null): ParkingRecord? {
        val targetId = sessionId ?: repository.findActive()?.id ?: return null

        val now = clock.nowEpochMillis()
        var moved = false
        // Read-modify-write inside Room's transaction (docs/06 §5) — that transaction is
        // the shared-write lock §7a's mutation sequence asks for. Everything below is
        // decided from the stored row, never from what the caller last saw.
        val updated = repository.update(targetId) { record ->
            val next = FloorParser.step(record.floor, delta)
            // A closed session is history; the widget must not edit it.
            if (next == null || !record.isActive) {
                record
            } else {
                moved = true
                record.copy(floor = next, updatedAtMillis = now, revision = record.revision + 1)
            }
        }
        return updated.takeIf { moved }
    }
}

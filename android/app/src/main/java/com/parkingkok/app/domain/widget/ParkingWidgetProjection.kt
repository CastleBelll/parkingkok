package com.parkingkok.app.domain.widget

import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingRecord
import kotlinx.serialization.Serializable

/**
 * What a placed widget is allowed to know (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a).
 *
 * Room stays canonical (see `ParkingDatabase`); this is the derived cache §4 permits, and
 * nothing reads it back to decide what a parking *is*.
 *
 * ## Why these fields and no others
 *
 * docs/09_SECURITY_PRIVACY_COMPLIANCE.md and §7a forbid the widget rendering a coordinate,
 * an address or a photo, so none of them has a field here. A widget's state file is
 * readable by anything that can read the app's data directory and is copied by device
 * backup, so "the widget does not draw it" is not enough — it must never arrive.
 *
 * [floorLabel] and [zoneSpot] are pre-rendered rather than raw columns: the widget is the
 * hero card of `design-references/01-home-main.png` with the map and the primary action
 * removed, and keeping the join here means the Glance composable holds no product rule.
 *
 * [canStepDown]/[canStepUp] are the same predicate the home screen's keys use, so a key
 * the domain would reject is drawn disabled instead of bouncing the value back.
 */
@Serializable
data class ParkingWidgetProjection(
    /**
     * The rendered session, or null when nothing is parked.
     *
     * The stepper callback carries this back so a tap that lands after the session ended
     * can be dropped rather than applied to whatever came next (§7a).
     */
    val sessionId: String? = null,
    /** docs/06 §5. Carried so a reader can tell two snapshots apart. */
    val revision: Int = 0,
    /** `B3`, `3F`, or free text. Null when the record carries no floor at all. */
    val floorLabel: String? = null,
    /** `지하 3층` — what TalkBack says instead of spelling out `B3`. */
    val floorSpokenLabel: String? = null,
    /** `A구역 · 142`, or null when neither was filled in. */
    val zoneSpot: String? = null,
    /** Start of the session, so the elapsed line is computed at draw time. */
    val startedAtMillis: Long = 0L,
    val canStepDown: Boolean = false,
    val canStepUp: Boolean = false,
    /**
     * Whether the interactive stepper is drawn at all (§7a Entitlement).
     *
     * Carried in the snapshot so the widget never reads entitlement itself; the one
     * function that decides is `WidgetStepperEntitlement`.
     */
    val stepperEntitled: Boolean = false,
) {

    /** Null [sessionId] is the whole "no active parking" state. */
    val isActive: Boolean get() = sessionId != null

    /** Whether the `-`/`+` row has anything to offer at all. */
    val showsStepper: Boolean get() = stepperEntitled && (canStepDown || canStepUp)

    companion object {

        /** No active parking — what the widget shows before the first save, and after the last. */
        val Empty: ParkingWidgetProjection = ParkingWidgetProjection()

        /**
         * The projection of [record], or [Empty] when there is nothing to show.
         *
         * A record whose `endedAt` is set maps to [Empty] as well, which is docs/06 §8's
         * startup repair falling out of the mapping: a projection left behind for a
         * session that has since been completed cannot survive the next write.
         */
        fun of(record: ParkingRecord?, stepperEntitled: Boolean): ParkingWidgetProjection {
            if (record == null || !record.isActive) return Empty
            val floor = record.floor
            return ParkingWidgetProjection(
                sessionId = record.id,
                revision = record.revision,
                floorLabel = floor?.displayLabel,
                floorSpokenLabel = floor?.spokenLabel,
                zoneSpot = listOfNotNull(record.zone, record.spot)
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(SEPARATOR),
                startedAtMillis = record.startedAtMillis,
                canStepDown = FloorParser.step(floor, -1) != null,
                canStepUp = FloorParser.step(floor, 1) != null,
                stepperEntitled = stepperEntitled,
            )
        }

        /** `01-home-main.png` joins the two with a middle dot. */
        private const val SEPARATOR = " · "
    }
}

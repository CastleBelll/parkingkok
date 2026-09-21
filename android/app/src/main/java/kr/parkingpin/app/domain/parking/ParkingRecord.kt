package kr.parkingpin.app.domain.parking

import kotlinx.serialization.Serializable

/**
 * How a record came to exist (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §2 `source`).
 *
 * Persisted by enum name and shared with iOS, so the spelling is part of the contract.
 */
enum class ParkingSource { MANUAL, DETECTED }

/** docs/06 §2 `confidenceBucket`. Only detection sets it; a manual save leaves it null. */
@Serializable
enum class ConfidenceBucket { LOW, MEDIUM, HIGH }

/**
 * Where the car is, as far as the app can honestly claim.
 *
 * Like the detection engine's `ReliableLocation`, [toString] is redacted so an accidental
 * string interpolation into a log cannot leak a coordinate (docs/00_CORE_RULES.md Privacy).
 */
data class ParkingLocation(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float?,
    val capturedAtMillis: Long,
) {
    override fun toString(): String =
        "ParkingLocation(redacted, accuracyM=$horizontalAccuracyM, capturedAtMillis=$capturedAtMillis)"
}

/**
 * One parking session — the common record of docs/06 §2, with the three floor columns
 * folded into a [Floor] and the four location columns into a [ParkingLocation].
 *
 * There is no separate "active session" type. A record with a null [endedAtMillis] *is*
 * the active parking; see `ParkingDatabase` for why that is one table and not two.
 *
 * [toString] is redacted in full. Coordinates are the obvious hazard, but docs/06 §1
 * classifies floor, zone, spot and memo as sensitive local-only data too, and a crash
 * report that interpolated a record would carry all of them.
 */
data class ParkingRecord(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val source: ParkingSource,
    val confidenceBucket: ConfidenceBucket?,
    val location: ParkingLocation?,
    val floor: Floor?,
    val zone: String?,
    val spot: String?,
    val memo: String?,
    val photoRelativePath: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /**
     * Monotonic local revision (docs/06 §5). Every mutation increments it; the widget's
     * read-modify-write will need it, and it costs one column to have it already there.
     */
    val revision: Int,
) {
    /** docs/06 §4: the active session is the open record, not a second copy of one. */
    val isActive: Boolean get() = endedAtMillis == null

    override fun toString(): String =
        "ParkingRecord(id=$id, redacted, source=$source, active=$isActive, revision=$revision)"
}

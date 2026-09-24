package com.sjstudioz.parkingpin.data.parking

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The stored row. Columns are the logical fields of
 * docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §2, spelled exactly as §2 spells them so the
 * Android table and the iOS model stay legibly the same record.
 *
 * Where docs/04_ANDROID_IMPLEMENTATION.md §11 sketches different names for two of them —
 * `detectionType` for §2's `source`, `floorParsed` for §2's `floorKind`/`floorNumber` pair
 * — §2 wins: it is the cross-platform contract, and §11 calls its own list "suggested".
 *
 * Enums are stored as their names rather than ordinals. An ordinal silently re-points at a
 * different constant the day someone inserts a value into the middle of the enum, and this
 * data is never re-derivable from anywhere else.
 *
 * Indices: `endedAt` because every read filters on it (active vs. history), `startedAt`
 * because every read orders by it.
 */
@Entity(
    tableName = "parking_record",
    indices = [Index("endedAt"), Index("startedAt")],
)
data class ParkingRecordEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val source: String,
    val confidenceBucket: String?,
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracy: Float?,
    val locationCapturedAt: Long?,
    val floorRaw: String?,
    val floorKind: String?,
    val floorNumber: Int?,
    val zone: String?,
    val spot: String?,
    val memo: String?,
    val photoRelativePath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** docs/06 §5 monotonic local revision. */
    val revision: Int,
)

package com.parkingpin.app.data.parking

import com.parkingpin.app.domain.parking.ConfidenceBucket
import com.parkingpin.app.domain.parking.FloorParser
import com.parkingpin.app.domain.parking.ParkingLocation
import com.parkingpin.app.domain.parking.ParkingRecord
import com.parkingpin.app.domain.parking.ParkingRepository
import com.parkingpin.app.domain.parking.ParkingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room adapter for [ParkingRepository] (docs/03_SYSTEM_ARCHITECTURE.md §5).
 *
 * The only job here is the mapping between the flat row and the domain model; no rule
 * about what a parking *means* belongs in this file.
 */
class RoomParkingRepository(private val dao: ParkingRecordDao) : ParkingRepository {

    override fun observeActive(): Flow<ParkingRecord?> =
        dao.observeActive().map { it?.toDomain() }

    override fun observeCompleted(limit: Int): Flow<List<ParkingRecord>> =
        dao.observeCompleted(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecord(id: String): Flow<ParkingRecord?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun findActive(): ParkingRecord? = dao.findActive()?.toDomain()

    override suspend fun find(id: String): ParkingRecord? = dao.findById(id)?.toDomain()

    override suspend fun insert(record: ParkingRecord) = dao.insert(record.toEntity())

    override suspend fun update(
        id: String,
        mutate: (ParkingRecord) -> ParkingRecord,
    ): ParkingRecord? = dao.readModifyWrite(id) { entity ->
        mutate(entity.toDomain()).toEntity()
    }?.toDomain()

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun photoPaths(): Set<String> = dao.photoPaths().toSet()


    override suspend fun deleteCompleted() = dao.deleteCompleted()
}

private fun ParkingRecordEntity.toDomain(): ParkingRecord = ParkingRecord(
    id = id,
    startedAtMillis = startedAt,
    endedAtMillis = endedAt,
    // An unreadable stored value degrades to MANUAL rather than throwing: a record the app
    // cannot classify is still a record of where the car was, and losing it is worse than
    // mislabelling how it was created.
    source = ParkingSource.entries.firstOrNull { it.name == source } ?: ParkingSource.MANUAL,
    confidenceBucket = ConfidenceBucket.entries.firstOrNull { it.name == confidenceBucket },
    location = toLocation(),
    floor = FloorParser.fromStoredFields(floorRaw, floorKind, floorNumber),
    zone = zone,
    spot = spot,
    memo = memo,
    photoRelativePath = photoRelativePath,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
    revision = revision,
)

/**
 * Latitude and longitude are written together or not at all, so a row missing either is
 * treated as having no location rather than as half of one.
 */
private fun ParkingRecordEntity.toLocation(): ParkingLocation? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return ParkingLocation(
        latitude = lat,
        longitude = lon,
        horizontalAccuracyM = horizontalAccuracy,
        capturedAtMillis = locationCapturedAt ?: startedAt,
    )
}

private fun ParkingRecord.toEntity(): ParkingRecordEntity = ParkingRecordEntity(
    id = id,
    startedAt = startedAtMillis,
    endedAt = endedAtMillis,
    source = source.name,
    confidenceBucket = confidenceBucket?.name,
    latitude = location?.latitude,
    longitude = location?.longitude,
    horizontalAccuracy = location?.horizontalAccuracyM,
    locationCapturedAt = location?.capturedAtMillis,
    floorRaw = floor?.raw,
    floorKind = floor?.kind?.name,
    floorNumber = floor?.number,
    zone = zone,
    spot = spot,
    memo = memo,
    photoRelativePath = photoRelativePath,
    createdAt = createdAtMillis,
    updatedAt = updatedAtMillis,
    revision = revision,
)

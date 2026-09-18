package com.parkingkok.app.domain.parking.usecase

import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingRepository
import com.parkingkok.app.domain.photo.ParkingPhotoStore
import com.parkingkok.app.domain.photo.PhotoSaveResult
import com.parkingkok.app.domain.photo.PhotoSource

/** Outcome of [AttachParkingPhotoUseCase]. */
sealed interface AttachParkingPhotoResult {

    data class Attached(val record: ParkingRecord) : AttachParkingPhotoResult

    /** The record was deleted while the picker was open. */
    data object RecordGone : AttachParkingPhotoResult

    /** Nothing was stored, and the record is unchanged. */
    data class Failed(val reason: PhotoSaveResult.Failed.Reason) : AttachParkingPhotoResult
}

/**
 * `사진 추가` — FR-007's one photo per record.
 *
 * The file is written before the row is updated, so a failure at any point leaves the
 * record exactly as it was. The cost is a possible orphan file, which
 * [CleanUpOrphanPhotosUseCase] exists to collect; the alternative ordering would leave a
 * record advertising a photo that was never written.
 *
 * A photo is optional and a parking record is not: every failure here returns and the
 * record survives untouched (CLAUDE.md — a denied permission is not an app failure).
 */
class AttachParkingPhotoUseCase(
    private val repository: ParkingRepository,
    private val photoStore: ParkingPhotoStore,
    private val clock: Clock,
) {

    suspend operator fun invoke(recordId: String, source: PhotoSource): AttachParkingPhotoResult {
        val existing = repository.find(recordId) ?: return AttachParkingPhotoResult.RecordGone

        val saved = when (val result = photoStore.save(recordId, source)) {
            is PhotoSaveResult.Saved -> result.relativePath
            is PhotoSaveResult.Failed -> return AttachParkingPhotoResult.Failed(result.reason)
        }

        val now = clock.nowEpochMillis()
        val updated = repository.update(recordId) { record ->
            record.copy(
                photoRelativePath = saved,
                updatedAtMillis = now,
                revision = record.revision + 1,
            )
        } ?: run {
            // Deleted between the read and the write: the file it would have belonged to
            // is now an orphan, so remove it rather than waiting for the sweep.
            photoStore.delete(saved)
            return AttachParkingPhotoResult.RecordGone
        }

        // A photo is named after its record, so a replacement normally overwrites in
        // place. This covers the case where an older record holds a differently named one.
        val previous = existing.photoRelativePath
        if (previous != null && previous != saved) photoStore.delete(previous)

        return AttachParkingPhotoResult.Attached(updated)
    }
}

/**
 * Removes a record's photo, keeping the record.
 *
 * The row is cleared first and the file second, for the reason [DeleteParkingRecordUseCase]
 * gives: a record that points at nothing is worse than a file nothing points at.
 */
class RemoveParkingPhotoUseCase(
    private val repository: ParkingRepository,
    private val photoStore: ParkingPhotoStore,
    private val clock: Clock,
) {

    /** The updated record, or null when there was no record or it had no photo. */
    suspend operator fun invoke(recordId: String): ParkingRecord? {
        var removed: String? = null
        val now = clock.nowEpochMillis()

        val updated = repository.update(recordId) { record ->
            val path = record.photoRelativePath
            if (path == null) {
                record
            } else {
                removed = path
                record.copy(
                    photoRelativePath = null,
                    updatedAtMillis = now,
                    revision = record.revision + 1,
                )
            }
        }

        photoStore.delete(removed)
        return if (removed == null) null else updated
    }
}

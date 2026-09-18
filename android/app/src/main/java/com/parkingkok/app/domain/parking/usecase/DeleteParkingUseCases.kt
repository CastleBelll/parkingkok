package com.parkingkok.app.domain.parking.usecase

import com.parkingkok.app.domain.parking.ParkingRepository
import com.parkingkok.app.domain.photo.ParkingPhotoStore

/**
 * Deleting a record deletes its photo.
 *
 * ## Order
 *
 * The row goes first, then the file. Either step can be the last thing this process does,
 * so the question is which leftover is survivable: a photo with no record is invisible to
 * the user and is picked up by [CleanUpOrphanPhotosUseCase] on the next start, while a
 * record pointing at a file that is gone is a parking whose photo silently vanished. The
 * first is a tidy-up; the second is a bug the user sees.
 */
class DeleteParkingRecordUseCase(
    private val repository: ParkingRepository,
    private val photoStore: ParkingPhotoStore,
) {

    suspend operator fun invoke(id: String) {
        val photoPath = repository.find(id)?.photoRelativePath
        repository.delete(id)
        photoStore.delete(photoPath)
    }
}

/**
 * Clears completed history and the photos those records held.
 *
 * The photos are removed by sweeping rather than by listing what was just deleted: after
 * the rows are gone, whatever the database still references is exactly what must survive,
 * and that set is the active parking's photo. Computing it from the database instead of
 * from the caller's memory means an interrupted delete cannot strand a file.
 */
class DeleteParkingHistoryUseCase(
    private val repository: ParkingRepository,
    private val cleanUpOrphanPhotos: CleanUpOrphanPhotosUseCase,
) {

    suspend operator fun invoke() {
        repository.deleteCompleted()
        cleanUpOrphanPhotos()
    }
}

/**
 * Deletes stored photos no record points at.
 *
 * FR-007 photos are sensitive local-only data (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §1),
 * and an orphan is one the user has no way to find and no way to delete. They happen: a
 * photo is written before the row that names it is updated, and the process can die in
 * between. Running this at start costs one indexed query and a directory listing.
 */
class CleanUpOrphanPhotosUseCase(
    private val repository: ParkingRepository,
    private val photoStore: ParkingPhotoStore,
) {

    suspend operator fun invoke() {
        photoStore.retainOnly(repository.photoPaths())
    }
}

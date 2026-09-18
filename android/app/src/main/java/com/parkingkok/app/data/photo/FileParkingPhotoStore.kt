package com.parkingkok.app.data.photo

import com.parkingkok.app.domain.photo.ParkingPhotoStore
import com.parkingkok.app.domain.photo.PhotoSaveResult
import com.parkingkok.app.domain.photo.PhotoScale
import com.parkingkok.app.domain.photo.PhotoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ParkingPhotoStore] over app-private files (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §4).
 *
 * The two collaborators split along the line that matters for testing: [encoder] is the
 * only part that needs Android, and [files] is the only part that touches the disk.
 *
 * Decode and disk both block, so everything here runs on [dispatcher] — a photo save
 * happens while the user is looking at the screen, and the main thread is where the
 * elapsed-time label is ticking.
 */
class FileParkingPhotoStore(
    private val files: ParkingPhotoFiles,
    private val encoder: PhotoEncoder,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ParkingPhotoStore {

    override suspend fun save(recordId: String, source: PhotoSource): PhotoSaveResult =
        withContext(dispatcher) {
            val name = fileNameFor(recordId)
                ?: return@withContext failed(PhotoSaveResult.Failed.Reason.STORAGE)

            val bytes = encoder.encode(source, PhotoScale.MAX_LONG_EDGE)
                ?: return@withContext failed(PhotoSaveResult.Failed.Reason.UNREADABLE)

            val path = files.write(name, bytes)
                ?: return@withContext failed(PhotoSaveResult.Failed.Reason.STORAGE)

            PhotoSaveResult.Saved(path)
        }

    override suspend fun delete(relativePath: String?) {
        withContext(dispatcher) { files.delete(relativePath) }
    }

    override suspend fun retainOnly(relativePaths: Set<String>) {
        withContext(dispatcher) { files.retainOnly(relativePaths) }
    }

    /**
     * One file per record (FR-007), named after the record so a second save replaces the
     * first instead of leaving the old one behind.
     *
     * The id is a generated UUID, but it is also the only caller-supplied part of a file
     * name, so it is filtered down to characters that cannot mean anything to a path.
     */
    private fun fileNameFor(recordId: String): String? {
        val safe = recordId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (safe.isEmpty()) null else "$safe$EXTENSION"
    }

    private fun failed(reason: PhotoSaveResult.Failed.Reason): PhotoSaveResult =
        PhotoSaveResult.Failed(reason)

    private companion object {
        const val EXTENSION = ".jpg"
    }
}

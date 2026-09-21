package com.parkingpin.app.data.photo

import android.graphics.Bitmap
import com.parkingpin.app.domain.photo.PhotoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException

/**
 * A stored photo, decoded, with the one fact the screen shows beside it.
 *
 * [savedAtMillis] is the file's own modification time rather than the record's
 * `updatedAt`, because they are not the same thing: stepping the floor updates the record
 * and leaves the photo alone, and a card that then claimed the photo was saved a minute
 * ago would be stating something false.
 */
data class ParkingPhotoImage(val bitmap: Bitmap, val savedAtMillis: Long)

/**
 * Reads a stored parking photo back for display.
 *
 * This is a presentation concern, not a product rule, which is why it is an adapter a
 * ViewModel holds rather than a use case: nothing about *what a parking means* is decided
 * here. It is an interface so the detail ViewModel can be exercised without a decoder.
 *
 * It returns a platform [Bitmap] and not a Compose `ImageBitmap` so that the decode has no
 * opinion about how it is drawn; the screen wraps it at the point of use.
 */
interface ParkingPhotoImageLoader {

    /** The photo at [relativePath], or null when there is none or it cannot be read. */
    suspend fun load(
        relativePath: String?,
        maxLongEdge: Int = DISPLAY_LONG_EDGE,
    ): ParkingPhotoImage?

    companion object {
        /**
         * Photos are stored with a 1600px long edge; showing them needs less.
         *
         * This is the retained allocation for a screen that is otherwise text — at this
         * size roughly 4MB, against the ~7.7MB a full-size decode would hold onto
         * (docs/11_QA_TEST_STRATEGY.md §12 photo decode).
         */
        const val DISPLAY_LONG_EDGE: Int = 1280
    }
}

/** Reads the file [ParkingPhotoFiles] resolves and decodes it through [BitmapPhotos]. */
class FileParkingPhotoImageLoader(
    private val files: ParkingPhotoFiles,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ParkingPhotoImageLoader {

    override suspend fun load(relativePath: String?, maxLongEdge: Int): ParkingPhotoImage? =
        withContext(dispatcher) {
            val file = files.resolve(relativePath) ?: return@withContext null
            if (!file.isFile) return@withContext null

            // A missing or unreadable file is an ordinary outcome, not an error: the user
            // may have cleared app storage, and the record is still a valid parking.
            val bitmap = try {
                BitmapPhotos.decodeScaled(PhotoSource { FileInputStream(file) }, maxLongEdge)
            } catch (_: IOException) {
                null
            }
            bitmap?.let { ParkingPhotoImage(it, file.lastModified()) }
        }
}

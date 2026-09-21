package com.parkingpin.app.data.photo

import android.graphics.Bitmap
import com.parkingpin.app.domain.photo.PhotoSource
import java.io.ByteArrayOutputStream

/**
 * Turns a picked or captured image into the bytes that get stored.
 *
 * An interface so [FileParkingPhotoStore] — which owns the *rules* about naming,
 * replacing and cleaning up — can be tested without `BitmapFactory`.
 */
fun interface PhotoEncoder {

    /** JPEG bytes with a long edge of at most [maxLongEdge], or null if undecodable. */
    fun encode(source: PhotoSource, maxLongEdge: Int): ByteArray?
}

/**
 * The real encoder: decode small (see [BitmapPhotos]), then JPEG.
 *
 * JPEG rather than the original bytes because FR-007's point is a bounded file — a modern
 * phone photo is 3-6MB and the record needs a thumbnail of a pillar. [QUALITY] is the
 * usual visual-transparency knee; above it the file grows faster than the picture improves.
 */
class JpegPhotoEncoder : PhotoEncoder {

    override fun encode(source: PhotoSource, maxLongEdge: Int): ByteArray? {
        val bitmap = BitmapPhotos.decodeScaled(source, maxLongEdge) ?: return null
        return try {
            ByteArrayOutputStream(INITIAL_BUFFER_BYTES).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val QUALITY = 85

        /** A 1600px-long-edge JPEG at quality 85 lands near this; it saves a few regrows. */
        const val INITIAL_BUFFER_BYTES = 512 * 1024
    }
}

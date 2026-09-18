package com.parkingkok.app.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.parkingkok.app.domain.photo.PhotoScale
import com.parkingkok.app.domain.photo.PhotoSize
import com.parkingkok.app.domain.photo.PhotoSource
import java.io.IOException

/**
 * The one place this app turns an image stream into a [Bitmap].
 *
 * Both callers — the encoder that stores a photo and the loader that shows one — want the
 * same three things: decode no more pixels than needed, end at a bounded long edge, and
 * come out the right way up. Sharing the routine is what keeps the stored photo and the
 * displayed photo from disagreeing about rotation.
 *
 * Decoding is two passes over [PhotoSource] because that is the only way to size it
 * (docs/11_QA_TEST_STRATEGY.md §12 lists photo decode as a measured budget): a bounds-only
 * pass allocates nothing, and the real pass is told an `inSampleSize` so a 12-megapixel
 * camera file never exists in memory at full size. The arithmetic behind both lives in
 * [PhotoScale], where it can be tested without a device.
 */
internal object BitmapPhotos {

    /**
     * [source], decoded and scaled so its long edge is at most [maxLongEdge], rotated per
     * its EXIF orientation. Null when the stream holds nothing decodable.
     */
    fun decodeScaled(source: PhotoSource, maxLongEdge: Int): Bitmap? {
        val bounds = decodeBounds(source) ?: return null
        if (!bounds.isUsable) return null

        val decoded = decodeSampled(source, PhotoScale.sampleSize(bounds, maxLongEdge))
            ?: return null
        return transform(decoded, maxLongEdge, rotationDegrees(source))
    }

    private fun decodeBounds(source: PhotoSource): PhotoSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            source.openStream().use { BitmapFactory.decodeStream(it, null, options) }
            PhotoSize(options.outWidth, options.outHeight)
        } catch (_: IOException) {
            null
        }
    }

    private fun decodeSampled(source: PhotoSource, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return try {
            source.openStream().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            // A photo is not worth taking the process down for. The caller reports a
            // failure and the parking record survives without one (FR-007 is optional).
            null
        }
    }

    /**
     * EXIF rotation in degrees.
     *
     * A camera writes the sensor's pixels and a tag saying which way was up; re-encoding
     * without applying the tag is what turns a photo of a pillar sideways. Mirrored
     * orientations are not handled — no camera produces them unprompted, and a flip the
     * user did not ask for is worse than leaving one alone.
     */
    private fun rotationDegrees(source: PhotoSource): Float = try {
        val orientation = source.openStream().use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: IOException) {
        0f
    }

    /**
     * Scales [decoded] to its target size and applies [rotation] in one pass.
     *
     * One `createBitmap` rather than a scale followed by a rotate: the second allocation
     * would be as large as the first and exist at the same time.
     */
    private fun transform(decoded: Bitmap, maxLongEdge: Int, rotation: Float): Bitmap {
        val target = PhotoScale.fit(PhotoSize(decoded.width, decoded.height), maxLongEdge)
        val matrix = Matrix().apply {
            postScale(
                target.width.toFloat() / decoded.width,
                target.height.toFloat() / decoded.height,
            )
            postRotate(rotation)
        }
        val transformed =
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        // An identity transform returns the source itself; recycling it would hand the
        // caller a dead bitmap.
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }
}

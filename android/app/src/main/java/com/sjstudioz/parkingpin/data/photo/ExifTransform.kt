package com.sjstudioz.parkingpin.data.photo

import android.media.ExifInterface

/**
 * What it takes to show a photo the way its EXIF orientation says: a rotation, then a
 * horizontal mirror.
 *
 * A camera writes the sensor's pixels and a tag saying which way is up; re-encoding without
 * applying the tag is what turns a photo of a pillar sideways. All eight tags, mirrored
 * ones included: they were skipped as "no camera produces them", but some front cameras
 * write `TRANSPOSE` and `TRANSVERSE`, and ignoring those left the photo on its side *and*
 * mirrored relative to the gallery (audit 2026-10-01). The tag is how the photo is meant to
 * look, not an edit the user did not ask for.
 *
 * Rotate first, then mirror — the order Android's own decoders use for these tags.
 */
internal data class ExifTransform(val rotationDegrees: Float, val mirrored: Boolean) {

    companion object {
        val NONE = ExifTransform(0f, false)

        fun of(orientation: Int): ExifTransform = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0f, true)
            ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180f, false)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180f, true)
            ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90f, true)
            ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90f, false)
            ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270f, true)
            ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270f, false)
            else -> NONE
        }
    }
}

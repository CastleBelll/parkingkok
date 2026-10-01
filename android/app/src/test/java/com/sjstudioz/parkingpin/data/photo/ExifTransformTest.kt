package com.sjstudioz.parkingpin.data.photo

import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

/** All eight EXIF orientations, mirrored ones included (audit 2026-10-01). */
class ExifTransformTest {

    @Test
    fun `every orientation maps to a rotation then an optional mirror`() {
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to ExifTransform(0f, false),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to ExifTransform(0f, true),
            ExifInterface.ORIENTATION_ROTATE_180 to ExifTransform(180f, false),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to ExifTransform(180f, true),
            ExifInterface.ORIENTATION_TRANSPOSE to ExifTransform(90f, true),
            ExifInterface.ORIENTATION_ROTATE_90 to ExifTransform(90f, false),
            ExifInterface.ORIENTATION_TRANSVERSE to ExifTransform(270f, true),
            ExifInterface.ORIENTATION_ROTATE_270 to ExifTransform(270f, false),
        )

        expected.forEach { (orientation, transform) ->
            assertEquals("orientation $orientation", transform, ExifTransform.of(orientation))
        }
    }

    @Test
    fun `an unknown or missing tag leaves the photo as it is`() {
        assertEquals(ExifTransform.NONE, ExifTransform.of(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(ExifTransform.NONE, ExifTransform.of(42))
    }
}

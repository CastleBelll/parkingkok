package com.parkingpin.app.domain.photo

import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-007's "long edge target ~1600px", and the edges where that arithmetic goes wrong. */
class PhotoScaleTest {

    @Test
    fun `a landscape camera photo lands on the long edge with its aspect ratio kept`() {
        assertEquals(PhotoSize(1600, 1200), PhotoScale.fit(PhotoSize(4000, 3000)))
    }

    @Test
    fun `a portrait photo scales its height, not its width`() {
        assertEquals(PhotoSize(1200, 1600), PhotoScale.fit(PhotoSize(3000, 4000)))
    }

    @Test
    fun `a square photo stays square`() {
        assertEquals(PhotoSize(1600, 1600), PhotoScale.fit(PhotoSize(4032, 4032)))
    }

    @Test
    fun `a photo already within the bound is left alone rather than upscaled`() {
        assertEquals(PhotoSize(800, 600), PhotoScale.fit(PhotoSize(800, 600)))
        assertEquals(PhotoSize(1600, 900), PhotoScale.fit(PhotoSize(1600, 900)))
    }

    @Test
    fun `an extreme panorama keeps at least one pixel on its short edge`() {
        assertEquals(PhotoSize(1600, 1), PhotoScale.fit(PhotoSize(16_000, 3)))
    }

    @Test
    fun `an unusable size is passed through for the caller to reject`() {
        assertEquals(PhotoSize(0, 0), PhotoScale.fit(PhotoSize(0, 0)))
        assertEquals(PhotoSize(-1, 10), PhotoScale.fit(PhotoSize(-1, 10)))
    }

    @Test
    fun `the decoder is told the largest power of two that stays at or above the target`() {
        // 4000 -> /2 = 2000 (>= 1600, keep going) -> /4 = 1000 (< 1600, stop at 2)
        assertEquals(2, PhotoScale.sampleSize(PhotoSize(4000, 3000)))
        assertEquals(4, PhotoScale.sampleSize(PhotoSize(8000, 6000)))
        assertEquals(1, PhotoScale.sampleSize(PhotoSize(1600, 1200)))
        assertEquals(1, PhotoScale.sampleSize(PhotoSize(640, 480)))
    }

    @Test
    fun `sampling never undershoots the target, so the exact scale never upscales`() {
        val source = PhotoSize(4000, 3000)

        val sampled = PhotoSize(
            source.width / PhotoScale.sampleSize(source),
            source.height / PhotoScale.sampleSize(source),
        )

        assertEquals(true, sampled.longEdge >= PhotoScale.MAX_LONG_EDGE)
    }

    @Test
    fun `an unusable size never asks for a zero sample`() {
        assertEquals(1, PhotoScale.sampleSize(PhotoSize(0, 0)))
        assertEquals(1, PhotoScale.sampleSize(PhotoSize(4000, 3000), maxLongEdge = 0))
    }
}

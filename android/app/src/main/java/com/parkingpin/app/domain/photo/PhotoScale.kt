package com.parkingpin.app.domain.photo

/** Pixel dimensions of an image. */
data class PhotoSize(val width: Int, val height: Int) {

    val longEdge: Int get() = maxOf(width, height)

    val isUsable: Boolean get() = width > 0 && height > 0
}

/**
 * The arithmetic of FR-007's "long edge target ~1600px", kept apart from `BitmapFactory`
 * so it can be tested without a device.
 *
 * Two numbers come out of here. [sampleSize] is what the decoder is told, and it can only
 * be a power of two, so it lands *at or above* the target; [fit] is the exact size the
 * decoded bitmap is then scaled to. Doing it in two steps is what keeps a 12-megapixel
 * camera photo from being fully decoded into memory first (docs/11_QA_TEST_STRATEGY.md
 * §12 photo decode).
 */
object PhotoScale {

    /** FR-007: one photo per record, long edge ~1600px. */
    const val MAX_LONG_EDGE: Int = 1600

    /**
     * The size [source] should end up at.
     *
     * Never larger than the source: upscaling a small photo would cost bytes and add no
     * detail. An unusable size passes through unchanged for the caller to reject.
     */
    fun fit(source: PhotoSize, maxLongEdge: Int = MAX_LONG_EDGE): PhotoSize {
        if (!source.isUsable || maxLongEdge <= 0) return source
        if (source.longEdge <= maxLongEdge) return source

        val ratio = maxLongEdge.toDouble() / source.longEdge
        return PhotoSize(
            width = scaleEdge(source.width, ratio),
            height = scaleEdge(source.height, ratio),
        )
    }

    /**
     * `BitmapFactory.Options.inSampleSize` for [source]: the largest power of two that
     * still leaves the long edge at or above [maxLongEdge].
     *
     * Staying at or above the target — rather than at or below — matters because the
     * exact scale in [fit] happens afterwards; undershooting here would upscale there and
     * show a soft photo of a pillar the user is trying to read a number off.
     */
    fun sampleSize(source: PhotoSize, maxLongEdge: Int = MAX_LONG_EDGE): Int {
        if (!source.isUsable || maxLongEdge <= 0) return 1

        var sample = 1
        while (source.longEdge / (sample * 2) >= maxLongEdge) {
            sample *= 2
        }
        return sample
    }

    /** At least one pixel: a 4000x1 panorama must not scale its short edge to zero. */
    private fun scaleEdge(edge: Int, ratio: Double): Int =
        maxOf(1, Math.round(edge * ratio).toInt())
}

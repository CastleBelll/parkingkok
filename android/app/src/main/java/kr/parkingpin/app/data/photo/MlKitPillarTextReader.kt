package kr.parkingpin.app.data.photo

import androidx.core.graphics.createBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kr.parkingpin.app.domain.photo.PhotoSource
import kr.parkingpin.app.domain.photo.PillarTextReader
import kotlinx.coroutines.tasks.await

/**
 * Reads the pillar with ML Kit's **bundled** Korean model
 * (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a).
 *
 * Bundled, not the Play-services variant: the model ships in the APK, so it is there in
 * an underground car park with no network — which is exactly where a photo of a pillar is
 * worth the most. Nothing here reaches the network and nothing leaves the device
 * (docs/09 §1).
 *
 * **Every failure is silence.** A stream that will not open, an image nothing can decode,
 * a recogniser that throws: all return an empty list, because §6a makes recognition
 * failing the normal case and the user sees the form they would have seen anyway. The
 * recognised strings are floor, zone and bay — docs/17 §3's forbidden list — so they are
 * returned to the caller and never logged.
 */
class MlKitPillarTextReader : PillarTextReader {

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /**
     * One recognition on a throwaway bitmap, to pay the model load somewhere the user is
     * not waiting. The result is discarded — only the loading matters.
     *
     * Silent like everything else here: if warming fails, the read that follows simply
     * pays the cost itself, which is what the deadline is sized for.
     */
    override suspend fun prepare() {
        val warmup = createBitmap(WARMUP_EDGE, WARMUP_EDGE)
        try {
            recognizer.process(InputImage.fromBitmap(warmup, ROTATION_APPLIED)).await()
        } catch (_: Exception) {
            // Nothing to do and nothing to say: the next read will load the model itself.
        } finally {
            warmup.recycle()
        }
    }

    override suspend fun read(source: PhotoSource): List<String> {
        // The same bounded decode the store uses, so a 12-megapixel camera file never
        // exists at full size (docs/11 §12). A pillar sign survives the downsample: it is
        // the largest thing in the frame.
        val bitmap = BitmapPhotos.decodeScaled(source, RECOGNITION_LONG_EDGE) ?: return emptyList()
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, ROTATION_APPLIED))
                .await()
                .textBlocks
                .flatMap { block -> block.lines.map { it.text } }
        } catch (_: Exception) {
            // Deliberately broad. ML Kit reports a missing model, a closed recogniser and
            // a device it cannot run on as different exceptions, and §6a gives all three
            // the same outcome; letting one through would crash a path whose contract is
            // that it stays quiet.
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /**
         * Large enough for signage, small enough to decode and recognise in the seconds
         * the user is standing there. Bigger than the stored photo's edge on purpose —
         * this one is read, not looked at.
         */
        const val RECOGNITION_LONG_EDGE = 1_600

        /** Big enough for ML Kit to accept, small enough to cost nothing. */
        const val WARMUP_EDGE = 32

        /** [BitmapPhotos.decodeScaled] has already applied the EXIF rotation. */
        const val ROTATION_APPLIED = 0
    }
}

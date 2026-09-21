package kr.parkingpin.app.ui.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import kr.parkingpin.app.domain.photo.PhotoSource
import kr.parkingpin.app.data.photo.MlKitPillarTextReader
import kr.parkingpin.app.domain.photo.PillarSuggestion
import kr.parkingpin.app.domain.photo.ReadPillarSuggestionUseCase
import kr.parkingpin.app.theme.ParkingpinTheme
import kr.parkingpin.app.ui.manual.ManualParkingScreen
import kr.parkingpin.app.ui.manual.ManualParkingUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The half of `사진으로 입력` only a device can answer
 * (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a).
 *
 * The JVM suite states what a *recognised line* becomes. What it cannot say is that the
 * **bundled** Korean model is really in the APK and really reads a Korean sign with no
 * network — which is the whole argument for paying its size (§6a "an underground car
 * park is where this feature is worth the most and where there is no network").
 *
 * The pillar is drawn here rather than shipped as a fixture image so the test carries no
 * binary and states exactly what it asked the recogniser to read.
 *
 * It also writes the two captures of the filled form, light and dark, into the app's
 * external files directory.
 */
@RunWith(AndroidJUnit4::class)
class PillarReadingDeviceTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun theBundledModelReadsAKoreanPillarAndFillsTheForm() {
        // Arrange — a sign the way a car park writes one.
        val photo = drawnPillar("지하 3층", "A구역 142")

        // Act — the real recogniser, on the device, with no network involved.
        val suggestion = runBlocking { ReadPillarSuggestionUseCase(MlKitPillarTextReader())(photo) }

        // Assert — parsed by the existing floor rules (docs/02 §6).
        assertEquals("지하 3층", suggestion.floorRaw)
        assertEquals("A구역", suggestion.zone)
        assertEquals("142", suggestion.spot)

        capture(suggestion, darkTheme = false, name = "android-confirm-photo-read-light.png")
    }

    @Test
    fun theFilledFormInTheDark() {
        val photo = drawnPillar("지하 3층", "A구역 142")
        val suggestion = runBlocking { ReadPillarSuggestionUseCase(MlKitPillarTextReader())(photo) }

        capture(suggestion, darkTheme = true, name = "android-confirm-photo-read-dark.png")
    }

    /** Renders the form as `사진으로 입력` leaves it, and writes it out as a PNG. */
    private fun capture(suggestion: PillarSuggestion, darkTheme: Boolean, name: String) {
        composeTestRule.setContent {
            ParkingpinTheme(darkTheme = darkTheme) {
                ManualParkingScreen(
                    state = ManualParkingUiState(
                        floorRaw = suggestion.floorRaw.orEmpty(),
                        zone = suggestion.zone.orEmpty(),
                        spot = suggestion.spot.orEmpty(),
                        pillarSuggestionOffered = !suggestion.isEmpty,
                    ),
                    onFloorChange = {},
                    onZoneChange = {},
                    onSpotChange = {},
                    onMemoChange = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val image = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val target = File(capturesDirectory(), name)
        FileOutputStream(target).use { image.compress(Bitmap.CompressFormat.PNG, QUALITY, it) }
    }

    private fun capturesDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "captures").apply { mkdirs() }
    }

    /** A pillar sign, drawn with the device's own Korean font. */
    private fun drawnPillar(vararg lines: String): PhotoSource {
        val bitmap = Bitmap.createBitmap(SIGN_WIDTH, SIGN_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, MARGIN, MARGIN + TEXT_SIZE * (index + 1), paint)
        }
        val file = File(capturesDirectory(), "pillar.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        return PhotoSource { file.inputStream() }
    }

    private companion object {
        const val SIGN_WIDTH = 900
        const val SIGN_HEIGHT = 500
        const val TEXT_SIZE = 120f
        const val MARGIN = 60f
        const val QUALITY = 95
    }
}

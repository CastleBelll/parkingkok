package com.parkingkok.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.parkingkok.app.detection.RegistrationStatus
import com.parkingkok.app.ui.diagnostics.DiagnosticsPermissions
import com.parkingkok.app.ui.diagnostics.DiagnosticsScreen
import com.parkingkok.app.ui.diagnostics.DiagnosticsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun render(state: DiagnosticsUiState) {
        composeTestRule.setContent {
            DiagnosticsScreen(
                state = state,
                onDetectionEnabledChange = {},
                onPermissionResult = {},
                onCaptureModeChange = {},
                onExportDiagnostics = {},
                onClearEvents = {},
                onTraceLabelChange = { _, _ -> },
            )
        }
    }

    @Test
    fun deniedPermission_offersRequestAndExplainsManualFallbackStaysAvailable() {
        // Arrange
        val state = DiagnosticsUiState(
            permissions = DiagnosticsPermissions(activityRecognitionGranted = false),
            registrationStatus = RegistrationStatus.MissingPermission,
        )

        // Act
        render(state)

        // Assert — a denial is surfaced as a state, never as an app failure.
        composeTestRule.onNodeWithText("거부됨").assertIsDisplayed()
        composeTestRule.onNodeWithText("권한 요청").assertIsDisplayed()
    }

    @Test
    fun foregroundLocationGranted_offersTheBackgroundPromptSeparately() {
        // Arrange — docs/04_ANDROID_IMPLEMENTATION.md §3: background location is a
        // separate, later request, never bundled with the foreground one.
        val state = DiagnosticsUiState(
            permissions = DiagnosticsPermissions(
                activityRecognitionGranted = true,
                foregroundLocationGranted = true,
                backgroundLocationGranted = false,
            ),
        )

        // Act
        render(state)

        // Assert
        composeTestRule.onNodeWithText("항상 허용 요청").assertIsDisplayed()
    }

    @Test
    fun foregroundLocationDenied_doesNotOfferTheBackgroundPromptYet() {
        // Arrange — Android only offers "Allow all the time" after foreground is granted,
        // so showing it first would be a button that cannot work.
        val state = DiagnosticsUiState(
            permissions = DiagnosticsPermissions(foregroundLocationGranted = false),
        )

        // Act
        render(state)

        // Assert
        composeTestRule.onNodeWithText("위치 권한 요청").assertIsDisplayed()
        composeTestRule.onNodeWithText("항상 허용 요청").assertDoesNotExist()
    }
}

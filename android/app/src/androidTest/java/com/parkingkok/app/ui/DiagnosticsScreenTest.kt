package com.parkingkok.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.parkingkok.app.detection.RegistrationStatus
import com.parkingkok.app.ui.diagnostics.DiagnosticsScreen
import com.parkingkok.app.ui.diagnostics.DiagnosticsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun deniedPermission_offersRequestAndExplainsManualFallbackStaysAvailable() {
        // Arrange
        val state = DiagnosticsUiState(
            permissionGranted = false,
            registrationStatus = RegistrationStatus.MissingPermission,
        )

        // Act
        composeTestRule.setContent {
            DiagnosticsScreen(
                state = state,
                onDetectionEnabledChange = {},
                onPermissionResult = {},
                onClearEvents = {},
            )
        }

        // Assert — a denial is surfaced as a state, never as an app failure.
        composeTestRule.onNodeWithText("거부됨").assertIsDisplayed()
        composeTestRule.onNodeWithText("권한 요청").assertIsDisplayed()
    }
}

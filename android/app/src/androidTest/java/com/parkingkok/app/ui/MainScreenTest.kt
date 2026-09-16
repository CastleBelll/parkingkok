package com.parkingkok.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun mainScreen_displaysAppName() {
        // Arrange
        composeTestRule.setContent { MainScreen() }

        // Act — static screen, nothing to interact with yet.

        // Assert
        composeTestRule.onNodeWithText("주차콕").assertIsDisplayed()
    }
}

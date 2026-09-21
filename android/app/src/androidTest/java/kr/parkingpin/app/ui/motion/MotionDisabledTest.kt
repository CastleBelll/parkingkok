package kr.parkingpin.app.ui.motion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The other half of `MotionPolicyTest`: that the Compose side actually obeys the answer.
 *
 * `MotionPolicyTest` proves the rule; this proves the wiring. The clock is held still so
 * "already finished" cannot be confused with "finished very quickly".
 */
class MotionDisabledTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenEntryStartsFinishedWhenMotionIsOff() {
        // Arrange: what the theme provides when the animator duration scale reads 0.
        composeRule.mainClock.autoAdvance = false
        var progress = -1f

        // Act
        composeRule.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                progress = rememberScreenEntry().value
            }
        }

        // Assert: the very first frame is the settled one — nothing fades or slides in.
        assertEquals(1f, progress, 0f)
    }

    @Test
    fun screenEntryStartsFromNothingWhenMotionIsOn() {
        // Arrange
        composeRule.mainClock.autoAdvance = false
        var progress = -1f

        // Act
        composeRule.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides true) {
                progress = rememberScreenEntry().value
            }
        }

        // Assert: the same code path does animate when the user has not asked it not to,
        // so the test above is measuring the setting and not a dead animation.
        assertEquals(0f, progress, 0f)
    }
}

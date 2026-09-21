package kr.parkingpin.app.ui.notifications

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.parkingpin.app.domain.detection.CandidateOutcome
import kr.parkingpin.app.theme.ParkingpinTheme
import kr.parkingpin.app.ui.components.BrandHeader
import kr.parkingpin.app.ui.components.NotificationsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the bell shows and what its rows do — docs/10_DESIGN_UX_SPEC.md §7b.
 */
@RunWith(AndroidJUnit4::class)
class NotificationHistoryScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun render(
        state: NotificationHistoryUiState,
        onOpenCandidate: (String) -> Unit = {},
        onOpenRecord: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            ParkingpinTheme {
                NotificationHistoryScreen(
                    state = state,
                    onOpenCandidate = onOpenCandidate,
                    onOpenRecord = onOpenRecord,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun emptyHistory_explainsWhatWillAppearHere() {
        // Arrange
        val state = NotificationHistoryUiState(loaded = true)

        // Act
        render(state)

        // Assert
        composeTestRule.onNodeWithText("아직 받은 알림이 없어요").assertIsDisplayed()
    }

    @Test
    fun theThreeOutcomes_eachReadAsItsOwnWord() {
        // Arrange — §7b: 저장됨 with the floor, 주차 아님, 응답 없음, and no others.
        val state = NotificationHistoryUiState(
            rows = listOf(
                NotificationRow("a", RAISED_AT, CandidateOutcome.CONFIRMED, "B3 · A구역 142", "r1"),
                NotificationRow("b", RAISED_AT, CandidateOutcome.REJECTED),
                NotificationRow("c", RAISED_AT, CandidateOutcome.EXPIRED),
            ),
            nowMillis = RAISED_AT,
            loaded = true,
        )

        // Act
        render(state)

        // Assert
        composeTestRule.onNodeWithText("B3 · A구역 142 로 저장됨").assertIsDisplayed()
        composeTestRule.onNodeWithText("주차 아님").assertIsDisplayed()
        composeTestRule.onNodeWithText("응답 없음").assertIsDisplayed()
    }

    @Test
    fun aRejectedRow_doesNothingAndIsNotClickable() {
        // Arrange — §7b: "a row that does nothing must not look tappable".
        val state = NotificationHistoryUiState(
            rows = listOf(NotificationRow("b", RAISED_AT, CandidateOutcome.REJECTED)),
            nowMillis = RAISED_AT,
            loaded = true,
        )

        // Act
        render(state)

        // Assert
        composeTestRule.onNodeWithText("주차 아님").assertHasNoClickAction()
    }

    @Test
    fun aPendingRow_opensTheConfirmationScreen() {
        // Arrange
        var opened: String? = null
        val state = NotificationHistoryUiState(
            rows = listOf(NotificationRow("a", RAISED_AT, outcome = null)),
            nowMillis = RAISED_AT,
            loaded = true,
        )

        // Act
        render(state, onOpenCandidate = { opened = it })
        composeTestRule.onNodeWithText("확인이 필요해요").assertHasClickAction().performClick()

        // Assert
        assertEquals("a", opened)
    }

    @Test
    fun aConfirmedRow_opensTheRecordItBecame() {
        // Arrange
        var opened: String? = null
        val state = NotificationHistoryUiState(
            rows = listOf(
                NotificationRow("a", RAISED_AT, CandidateOutcome.CONFIRMED, "B3", "r1"),
            ),
            nowMillis = RAISED_AT,
            loaded = true,
        )

        // Act
        render(state, onOpenRecord = { opened = it })
        composeTestRule.onNodeWithText("B3 로 저장됨").performClick()

        // Assert
        assertEquals("r1", opened)
    }

    // ── The dot (§7b "The dot") ──────────────────────────────────────────────────────

    @Test
    fun theBell_carriesTheDotOnlyWhileACandidateIsUnanswered() {
        // Arrange — the dot is state, so the label carries it too (docs/01 §8). Rendering
        // both bells at once is what proves the difference is the flag and nothing else.
        composeTestRule.setContent {
            ParkingpinTheme {
                BrandHeader(
                    actions = {
                        NotificationsAction(unanswered = false, onClick = {})
                        NotificationsAction(unanswered = true, onClick = {})
                        Text("")
                    },
                )
            }
        }

        // Assert
        composeTestRule.onNodeWithContentDescription("알림").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("알림, 확인하지 않은 알림이 있어요")
            .assertIsDisplayed()
    }

    private companion object {
        const val RAISED_AT = 1_700_000_000_000L
    }
}

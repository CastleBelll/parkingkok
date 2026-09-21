package com.parkingpin.app.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingpin.app.R
import com.parkingpin.app.domain.detection.CandidateOutcome
import com.parkingpin.app.domain.detection.ParkingCandidateNotice
import com.parkingpin.app.theme.ParkingpinTheme
import com.parkingpin.app.theme.spacing
import com.parkingpin.app.ui.components.DetailHeader
import com.parkingpin.app.ui.components.ParkingpinCard
import com.parkingpin.app.ui.components.ParkingpinRow
import com.parkingpin.app.ui.components.ParkingpinScreen
import com.parkingpin.app.ui.components.RowChevron
import com.parkingpin.app.ui.format.dayText
import com.parkingpin.app.ui.format.timeOfDayText

/**
 * The notification history — `docs/10_DESIGN_UX_SPEC.md` §7b.
 *
 * Every candidate the app raised, newest first, and what became of it. One surface with
 * dividers rather than a card per row: a stack of floating panels is the dashboard the
 * design harness in CLAUDE.md rules out, and the history list next door is built the
 * same way.
 *
 * **What is deliberately absent.** No coordinate, no address, no map — §7b repeats the
 * rule the notification itself follows, and the state this screen renders has no field
 * that could carry one. No count on the bell either, and no way back to the notification
 * switches: those stay in 설정 → 알림.
 */
@Composable
fun NotificationHistoryScreen(
    state: NotificationHistoryUiState,
    onOpenCandidate: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ParkingpinScreen(
        modifier = modifier,
        header = {
            DetailHeader(title = stringResource(R.string.notifications_title), onBack = onBack)
        },
    ) {
        if (!state.loaded) return@ParkingpinScreen

        if (state.rows.isEmpty()) {
            item("empty") { EmptyNotifications() }
            return@ParkingpinScreen
        }

        item("rows") {
            ParkingpinCard(contentPadding = 0.dp) {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = MaterialTheme.spacing.large),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    NotificationRowItem(
                        row = row,
                        nowMillis = state.nowMillis,
                        onOpenCandidate = onOpenCandidate,
                        onOpenRecord = onOpenRecord,
                    )
                }
            }
        }
    }
}

/**
 * One line: what was asked, what became of it, and when it was asked.
 *
 * The three destinations §7b fixes are decided here and nowhere else — pending opens the
 * confirmation screen, confirmed opens the record, and rejected or expired opens nothing
 * and carries no chevron, because "a row that does nothing must not look tappable".
 */
@Composable
private fun NotificationRowItem(
    row: NotificationRow,
    nowMillis: Long,
    onOpenCandidate: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    ParkingpinRow(
        title = ParkingCandidateNotice.TITLE,
        supporting = row.outcomeText(),
        iconRes = R.drawable.ic_car,
        onClick = when {
            row.outcome == null -> {
                { onOpenCandidate(row.candidateId) }
            }
            row.openRecordId != null -> {
                { onOpenRecord(row.openRecordId) }
            }
            else -> null
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = dayText(row.raisedAtMillis, nowMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = timeOfDayText(row.raisedAtMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // §7b "The pending row": the chevron belongs to the one row that is
                // asking for something. A confirmed row still opens its record, but it
                // is history first, and a column of chevrons would make the whole list
                // look like a list of things to do.
                if (row.outcome == null) {
                    Spacer(Modifier.width(MaterialTheme.spacing.tiny))
                    RowChevron()
                }
            }
        },
    )
}

/**
 * §7b's three outcomes, and the one line a still-pending candidate gets instead.
 *
 * 주차 아님 is read from [ParkingCandidateNotice] rather than from `strings.xml`: it is the
 * same word the notification action uses, and one constant is what stops the two drifting.
 */
@Composable
private fun NotificationRow.outcomeText(): String = when (outcome) {
    null -> stringResource(R.string.notifications_pending)
    CandidateOutcome.CONFIRMED -> place
        ?.let { stringResource(R.string.notifications_outcome_saved_at, it) }
        ?: stringResource(R.string.notifications_outcome_saved)
    CandidateOutcome.REJECTED -> ParkingCandidateNotice.ACTION_REJECT
    CandidateOutcome.EXPIRED -> stringResource(R.string.notifications_outcome_expired)
}

@Composable
private fun EmptyNotifications() {
    ParkingpinCard {
        Text(
            text = stringResource(R.string.notifications_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = stringResource(R.string.notifications_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Notifications", showBackground = true)
@Composable
private fun NotificationHistoryPreview() {
    ParkingpinTheme {
        NotificationHistoryScreen(
            state = NotificationHistoryUiState(
                rows = listOf(
                    NotificationRow("a", 1_700_000_000_000L, outcome = null),
                    NotificationRow(
                        candidateId = "b",
                        raisedAtMillis = 1_699_990_000_000L,
                        outcome = CandidateOutcome.CONFIRMED,
                        place = "B3 · A구역 142",
                        openRecordId = "r1",
                    ),
                    NotificationRow("c", 1_699_900_000_000L, CandidateOutcome.REJECTED),
                    NotificationRow("d", 1_699_800_000_000L, CandidateOutcome.EXPIRED),
                ),
                nowMillis = 1_700_000_600_000L,
                loaded = true,
            ),
            onOpenCandidate = {},
            onOpenRecord = {},
            onBack = {},
        )
    }
}

@Preview(name = "Notifications — empty", showBackground = true)
@Composable
private fun NotificationHistoryEmptyPreview() {
    ParkingpinTheme {
        NotificationHistoryScreen(
            state = NotificationHistoryUiState(loaded = true),
            onOpenCandidate = {},
            onOpenRecord = {},
            onBack = {},
        )
    }
}

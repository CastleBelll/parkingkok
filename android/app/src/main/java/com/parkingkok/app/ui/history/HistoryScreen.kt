package com.parkingkok.app.ui.history

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingSource
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.components.DetailHeader
import com.parkingkok.app.ui.components.ParkingkokCard
import com.parkingkok.app.ui.components.ParkingkokRow
import com.parkingkok.app.ui.components.ParkingkokScreen
import com.parkingkok.app.ui.components.RowChevron
import com.parkingkok.app.ui.components.StatusBadge
import com.parkingkok.app.ui.motion.pressScale
import com.parkingkok.app.ui.format.dayText
import com.parkingkok.app.ui.format.timeOfDayText

/**
 * `04-history-list.png`.
 *
 * The mockup's filter chips (`전체 / 자동 감지 / 수동 저장`) and the monthly summary card are not
 * here. Filtering a list this build cannot yet fill with detected records would show three
 * tabs, two of them always empty; docs/19 lists filtering as a 보조 기능, so it waits for
 * detection to land. What the mockup calls essential — scanning by date and place, and the
 * badge that separates an automatic record from a typed one — is here.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenDetail: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDeleteAll by remember { mutableStateOf(false) }

    ParkingkokScreen(
        modifier = modifier,
        header = { DetailHeader(title = stringResource(R.string.history_title), onBack = onBack) },
    ) {
        if (!state.loaded) return@ParkingkokScreen

        if (state.records.isEmpty()) {
            item("empty") { EmptyHistory() }
            return@ParkingkokScreen
        }

        item("count") {
            Text(
                text = stringResource(R.string.history_count, state.records.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // One surface for the whole list, divided. A card per record turned the screen
        // into a stack of floating panels — the AI dashboard CLAUDE.md's design harness
        // rules out, and the same thing home's preview was fixed for.
        item("records") {
            ParkingkokCard(contentPadding = 0.dp) {
                state.records.forEachIndexed { index, record ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = MaterialTheme.spacing.large),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    HistoryRow(
                        record = record,
                        nowMillis = state.nowMillis,
                        onClick = { onOpenDetail(record.id) },
                    )
                }
            }
        }

        item("delete-all") {
            val deleteInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = { confirmingDeleteAll = true },
                shape = MaterialTheme.shapes.small,
                interactionSource = deleteInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.small)
                    .pressScale(deleteInteraction),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.small))
                Text(stringResource(R.string.history_delete_all))
            }
        }
    }

    if (confirmingDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteAll = false },
            title = { Text(stringResource(R.string.history_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDeleteAll = false
                        onDeleteAll()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryRow(record: ParkingRecord, nowMillis: Long, onClick: () -> Unit) {
    val place = listOfNotNull(record.floor?.displayLabel, record.zone, record.spot)
        .joinToString(" · ")
        .ifEmpty { stringResource(R.string.home_no_floor) }
    val detected = record.source == ParkingSource.DETECTED

    // One badge for every record, detected or typed. `04-history-list.png` gives the list
    // a single repeated car chip, and docs/19 §4 asks for the automatic ones to be picked
    // out by a badge — so the chip is the list's rhythm and the badge carries the meaning.
    // Tinting the chip as well made every row look like a different kind of thing.
    ParkingkokRow(
        title = place,
        iconRes = R.drawable.ic_car,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = dayText(record.startedAtMillis, nowMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = timeOfDayText(record.startedAtMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(MaterialTheme.spacing.tiny))
                RowChevron()
            }
        },
    )
    // Only the automatically detected records are badged (docs/19 §4). A typed record is
    // the ordinary case and says so by carrying nothing — badging both turned the list
    // into two columns of chips. The badge is a word, never a colour: docs/01 §8.
    if (detected) {
        Row(
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.large + BADGE_INDENT,
                bottom = MaterialTheme.spacing.medium,
            ),
        ) {
            StatusBadge(
                text = stringResource(R.string.history_badge_detected),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    } else {
        Spacer(Modifier.height(MaterialTheme.spacing.tiny))
    }
}

@Composable
private fun EmptyHistory() {
    ParkingkokCard {
        Text(
            text = stringResource(R.string.history_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = stringResource(R.string.history_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Lines the badge up under the row title rather than under the icon chip. */
private val BADGE_INDENT = 56.dp

@Preview(name = "History", showBackground = true)
@Composable
private fun HistoryPreview() {
    ParkingkokTheme {
        HistoryScreen(
            state = HistoryUiState(
                records = listOf(
                    previewRecord("a", "B3", ParkingSource.DETECTED),
                    previewRecord("b", "B2", ParkingSource.MANUAL),
                ),
                nowMillis = 1_700_005_040_000L,
                loaded = true,
            ),
            onOpenDetail = {},
            onDeleteAll = {},
            onBack = {},
        )
    }
}

private fun previewRecord(id: String, floorRaw: String, source: ParkingSource) = ParkingRecord(
    id = id,
    startedAtMillis = 1_700_000_000_000L,
    endedAtMillis = 1_700_003_600_000L,
    source = source,
    confidenceBucket = null,
    location = null,
    floor = FloorParser.parse(floorRaw),
    zone = "A구역",
    spot = "142",
    memo = null,
    photoRelativePath = null,
    createdAtMillis = 1_700_000_000_000L,
    updatedAtMillis = 1_700_000_000_000L,
    revision = 1,
)

package com.parkingkok.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.domain.parking.ElapsedTime
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingLocation
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingSource
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.components.DetailHeader
import com.parkingkok.app.ui.components.IconChip
import com.parkingkok.app.ui.components.ParkingkokCard
import com.parkingkok.app.ui.components.ParkingkokRow
import com.parkingkok.app.ui.components.ParkingkokScreen
import com.parkingkok.app.ui.components.RowChevron
import com.parkingkok.app.ui.format.dayText
import com.parkingkok.app.ui.format.elapsedText
import com.parkingkok.app.ui.format.timeOfDayText

/**
 * `03-parking-detail.png`.
 *
 * Two of the mockup's blocks are absent because the features are: the map (FR-008) and the
 * photo (FR-007). `길찾기` and `사진 보기` appear as the disabled rows the home screen also
 * shows, so the shape of the screen survives without claiming anything untrue. What is
 * here is the mockup's middle card — floor, zone/spot, elapsed, then the facts about the
 * record: when it started, how it was saved, and how good the location was.
 */
@Composable
fun ParkingDetailScreen(
    state: ParkingDetailUiState,
    onEndParking: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    ParkingkokScreen(
        modifier = modifier,
        header = { DetailHeader(title = stringResource(R.string.detail_title), onBack = onBack) },
    ) {
        if (!state.loaded) return@ParkingkokScreen

        val record = state.record
        if (record == null) {
            item("gone") {
                ParkingkokCard {
                    Text(
                        text = stringResource(R.string.detail_gone),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@ParkingkokScreen
        }

        item("summary") { SummaryCard(record = record, nowMillis = state.nowMillis) }
        item("facts") { FactsCard(record = record, nowMillis = state.nowMillis) }
        item("unavailable") { UnavailableActions() }

        if (record.isActive) {
            item("end") {
                Button(
                    onClick = onEndParking,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_flag),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(MaterialTheme.spacing.small))
                            Text(
                                text = stringResource(R.string.home_end_parking),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            text = stringResource(R.string.home_end_parking_caption),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            item("delete") {
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.small))
                    Text(stringResource(R.string.detail_delete))
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.detail_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SummaryCard(record: ParkingRecord, nowMillis: Long) {
    ParkingkokCard {
        Text(
            text = stringResource(R.string.home_active_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(MaterialTheme.spacing.small))

        val label = record.floor?.displayLabel ?: stringResource(R.string.home_no_floor)
        val spoken = record.floor?.spokenLabel ?: label
        Text(
            text = label,
            style = if (label.length <= HERO_MAX_CHARS) {
                MaterialTheme.typography.displayLarge
            } else {
                MaterialTheme.typography.displayMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = spoken },
        )

        val supporting = listOfNotNull(record.zone, record.spot)
        if (supporting.isNotEmpty()) {
            Text(
                text = supporting.joinToString(" · "),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (record.isActive) {
            Text(
                text = elapsedText(ElapsedTime.since(record.startedAtMillis, nowMillis)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The mockup's fact list: time, how it was saved, how accurate the location was. */
@Composable
private fun FactsCard(record: ParkingRecord, nowMillis: Long) {
    ParkingkokCard(contentPadding = 0.dp) {
        FactRow(
            iconRes = R.drawable.ic_clock,
            label = stringResource(R.string.detail_parked_at),
            value = "${dayText(record.startedAtMillis, nowMillis)} " +
                timeOfDayText(record.startedAtMillis),
        )
        val endedAt = record.endedAtMillis
        if (endedAt != null) {
            FactDivider()
            FactRow(
                iconRes = R.drawable.ic_flag,
                label = stringResource(R.string.detail_ended_at),
                value = "${dayText(endedAt, nowMillis)} ${timeOfDayText(endedAt)}",
            )
        }
        FactDivider()
        FactRow(
            iconRes = if (record.source == ParkingSource.DETECTED) {
                R.drawable.ic_sparkle
            } else {
                R.drawable.ic_pencil
            },
            label = stringResource(R.string.detail_source),
            value = stringResource(
                if (record.source == ParkingSource.DETECTED) {
                    R.string.detail_source_detected
                } else {
                    R.string.detail_source_manual
                },
            ),
        )
        FactDivider()
        FactRow(
            iconRes = R.drawable.ic_place,
            label = stringResource(R.string.detail_accuracy),
            value = record.location.accuracyText(),
        )
        val memo = record.memo
        if (memo != null) {
            FactDivider()
            FactRow(
                iconRes = R.drawable.ic_pencil,
                label = stringResource(R.string.detail_memo),
                value = memo,
            )
        }
    }
}

@Composable
private fun ParkingLocation?.accuracyText(): String = when {
    this == null -> stringResource(R.string.location_none)
    horizontalAccuracyM != null ->
        stringResource(R.string.location_accuracy, horizontalAccuracyM.toInt())
    else -> stringResource(R.string.location_saved)
}

@Composable
private fun FactRow(iconRes: Int, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MaterialTheme.spacing.touchTarget)
            .padding(
                horizontal = MaterialTheme.spacing.large,
                vertical = MaterialTheme.spacing.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(
            iconRes = iconRes,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 32.dp,
        )
        Spacer(Modifier.width(MaterialTheme.spacing.medium))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FactDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = MaterialTheme.spacing.large),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** `길찾기` and `사진 보기` from the mockup, shown as what they are: not built yet. */
@Composable
private fun UnavailableActions() {
    ParkingkokCard(contentPadding = 0.dp) {
        ParkingkokRow(
            title = stringResource(R.string.coming_soon_map),
            supporting = stringResource(R.string.coming_soon_map_caption),
            iconRes = R.drawable.ic_map,
            enabled = false,
            trailing = { RowChevron(enabled = false) },
        )
        FactDivider()
        ParkingkokRow(
            title = stringResource(R.string.coming_soon_photo),
            supporting = stringResource(R.string.coming_soon_photo_caption),
            iconRes = R.drawable.ic_photo,
            enabled = false,
            trailing = { RowChevron(enabled = false) },
        )
    }
}

private const val HERO_MAX_CHARS = 4

@Preview(name = "Detail", showBackground = true)
@Composable
private fun ParkingDetailPreview() {
    ParkingkokTheme {
        ParkingDetailScreen(
            state = ParkingDetailUiState(
                record = ParkingRecord(
                    id = "a",
                    startedAtMillis = 1_700_000_000_000L,
                    endedAtMillis = null,
                    source = ParkingSource.MANUAL,
                    confidenceBucket = null,
                    location = ParkingLocation(37.5, 127.0, 18f, 1_700_000_000_000L),
                    floor = FloorParser.parse("B3"),
                    zone = "A구역",
                    spot = "142",
                    memo = "엘리베이터 옆 기둥",
                    photoRelativePath = null,
                    createdAtMillis = 1_700_000_000_000L,
                    updatedAtMillis = 1_700_000_000_000L,
                    revision = 1,
                ),
                nowMillis = 1_700_007_380_000L,
                loaded = true,
            ),
            onEndParking = {},
            onDelete = {},
            onBack = {},
        )
    }
}

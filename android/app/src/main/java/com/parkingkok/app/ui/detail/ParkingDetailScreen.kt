package com.parkingkok.app.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.parkingkok.app.R
import com.parkingkok.app.data.photo.ParkingPhotoImage
import com.parkingkok.app.domain.parking.ElapsedTime
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingLocation
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingSource
import com.parkingkok.app.domain.photo.PhotoSource
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.UiNotice
import com.parkingkok.app.ui.components.DetailHeader
import com.parkingkok.app.ui.components.IconChip
import com.parkingkok.app.ui.components.LocationPreviewCard
import com.parkingkok.app.ui.components.ParkingkokCard
import com.parkingkok.app.ui.components.ParkingkokScreen
import com.parkingkok.app.ui.components.PrimaryCtaButton
import com.parkingkok.app.ui.motion.pressScale
import com.parkingkok.app.ui.format.dayText
import com.parkingkok.app.ui.format.elapsedText
import com.parkingkok.app.ui.format.timeOfDayText
import com.parkingkok.app.ui.photo.ParkingPhotoPicker

/**
 * `03-parking-detail.png`, top to bottom: the map block, the record card, the two primary
 * actions, the photo, and the primary button.
 *
 * Two places differ from the mockup, both on purpose.
 *
 * The map block is drawn, not fetched — Android's FR-008 is an external maps intent and
 * nothing that would put the coordinate on the network (docs/04_ANDROID_IMPLEMENTATION.md
 * §12); see `StaticLocationArtwork`. Its caption is `마지막으로 확인된 위치`, which is the only
 * claim the app can make honestly when the car is three floors underground
 * (docs/04_IOS_IMPLEMENTATION.md §9).
 *
 * The mockup's share affordance in the header is absent. Sharing a parking would mean
 * handing a coordinate, a floor and a photo to another app, and docs/06 §1 classifies all
 * three as local-only.
 */
@Composable
fun ParkingDetailScreen(
    state: ParkingDetailUiState,
    onEndParking: () -> Unit,
    onDelete: () -> Unit,
    onDirections: () -> Unit,
    onPhotoSelected: (PhotoSource) -> Unit,
    onRemovePhoto: () -> Unit,
    onCameraUnavailable: () -> Unit,
    onNoticeShown: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingPhoto by remember { mutableStateOf(false) }
    var viewingPhoto by remember { mutableStateOf(false) }

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

        item("map") { LocationBlock(record) }
        item("summary") { SummaryCard(record = record, nowMillis = state.nowMillis) }
        item("actions") {
            PrimaryActions(
                canOpenMap = state.canOpenMap,
                hasPhoto = state.hasPhoto,
                photoBusy = state.photoBusy,
                onDirections = onDirections,
                onPhoto = { if (state.hasPhoto) viewingPhoto = true else pickingPhoto = true },
            )
        }

        val notice = state.notice
        if (notice != null) {
            item("notice") { NoticeCard(notice = notice, onDismiss = onNoticeShown) }
        }

        if (state.hasPhoto) {
            item("photo") {
                PhotoCard(
                    photo = state.photo,
                    onOpen = { viewingPhoto = true },
                    onReplace = { pickingPhoto = true },
                )
            }
        }

        if (record.isActive) {
            item("end") { EndParkingButton(onEndParking) }
        } else {
            item("delete") { DeleteRecordButton { confirmingDelete = true } }
        }
    }

    ParkingPhotoPicker(
        visible = pickingPhoto,
        onDismiss = { pickingPhoto = false },
        onPhotoSelected = onPhotoSelected,
        onCameraUnavailable = onCameraUnavailable,
    )

    if (viewingPhoto) {
        PhotoViewer(
            photo = state.photo,
            onRemove = {
                viewingPhoto = false
                onRemovePhoto()
            },
            onDismiss = { viewingPhoto = false },
        )
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

/**
 * The mockup's map block.
 *
 * With no stored coordinate the block becomes a sentence instead of a picture: a manual
 * save with location permission denied is a supported outcome (FR-001), and drawing a pin
 * for it would invent a place.
 */
@Composable
private fun LocationBlock(record: ParkingRecord) {
    val location = record.location
    if (location == null) {
        ParkingkokCard {
            Text(
                text = stringResource(R.string.detail_map_none_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(MaterialTheme.spacing.tiny))
            Text(
                text = stringResource(R.string.detail_map_none_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val accuracy = location.horizontalAccuracyM
    LocationPreviewCard(
        pinLabel = record.floor?.displayLabel,
        zoneLabel = record.zone,
        caption = if (accuracy != null) {
            stringResource(R.string.detail_map_caption_accuracy, accuracy.toInt())
        } else {
            stringResource(R.string.detail_map_caption)
        },
    )
}

/**
 * The mockup's single record card: the hero, then the facts under a divider.
 */
@Composable
private fun SummaryCard(record: ParkingRecord, nowMillis: Long) {
    ParkingkokCard(contentPadding = 0.dp) {
        Column(Modifier.padding(MaterialTheme.spacing.card)) {
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

            // Same rule as home: a spot with no zone needs the word, or it reads as an
            // unexplained numeral at hero weight.
            val zone = record.zone
            val spot = record.spot
            val supporting = listOfNotNull(
                when {
                    zone != null && spot != null -> "$zone · $spot"
                    zone != null -> zone
                    spot != null -> stringResource(R.string.home_spot_only, spot)
                    else -> null
                },
            )
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

        FactDivider()
        Facts(record = record, nowMillis = nowMillis)
    }
}

/** The mockup's fact list: time, how it was saved, how accurate the location was. */
@Composable
private fun Facts(record: ParkingRecord, nowMillis: Long) {
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
    // Only when there is a location. With none, the banner at the top of the screen and
    // the caption under the disabled 길찾기 button already say so — a third row reading
    // "위치 없음" was the same fact a third time.
    if (record.location != null) {
        FactDivider()
        FactRow(
            iconRes = R.drawable.ic_place,
            label = stringResource(R.string.detail_accuracy),
            value = record.location.accuracyText(),
        )
    }
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

/**
 * `길찾기` and `사진 보기` — docs/19 §3 fixes these as the detail screen's primary pair.
 *
 * `길찾기` is disabled, not hidden, when the record has no coordinate: the mockup's shape
 * survives, and the caption below says why rather than leaving the grey to be guessed at.
 */
@Composable
private fun PrimaryActions(
    canOpenMap: Boolean,
    hasPhoto: Boolean,
    photoBusy: Boolean,
    onDirections: () -> Unit,
    onPhoto: () -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
            ActionButton(
                iconRes = R.drawable.ic_place,
                label = stringResource(R.string.detail_directions),
                enabled = canOpenMap,
                onClick = onDirections,
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                iconRes = R.drawable.ic_photo,
                label = stringResource(
                    if (hasPhoto) R.string.detail_photo_view else R.string.detail_photo_add,
                ),
                enabled = !photoBusy,
                busy = photoBusy,
                onClick = onPhoto,
                modifier = Modifier.weight(1f),
            )
        }
        if (!canOpenMap) {
            Text(
                text = stringResource(R.string.detail_directions_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.spacing.small),
            )
        }
    }
}

@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = MaterialTheme.spacing.touchTarget + 8.dp)
            .pressScale(interactionSource),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(MaterialTheme.spacing.small))
        Text(text = label, style = MaterialTheme.typography.titleSmall)
    }
}

/** `주차 사진` — one photo per record (FR-007), with when it was stored. */
@Composable
private fun PhotoCard(
    photo: ParkingPhotoImage?,
    onOpen: () -> Unit,
    onReplace: () -> Unit,
) {
    ParkingkokCard(contentPadding = MaterialTheme.spacing.medium) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.spacing.small,
                    end = MaterialTheme.spacing.small,
                    bottom = MaterialTheme.spacing.small,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.detail_photo_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (photo != null) {
                Text(
                    text = stringResource(
                        R.string.detail_photo_saved_at,
                        timeOfDayText(photo.savedAtMillis),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (photo == null) {
            // The row still says there is a photo; the file behind it could not be read.
            Text(
                text = stringResource(R.string.detail_photo_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MaterialTheme.spacing.small),
            )
        } else {
            Image(
                bitmap = photo.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.detail_photo_section),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PHOTO_CARD_HEIGHT)
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClick = onOpen),
            )
        }

        TextButton(
            onClick = onReplace,
            modifier = Modifier.padding(top = MaterialTheme.spacing.tiny),
        ) {
            Text(stringResource(R.string.detail_photo_replace))
        }
    }
}

/** Full-bleed view of the stored photo, with the one destructive action it needs. */
@Composable
private fun PhotoViewer(
    photo: ParkingPhotoImage?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (photo == null) {
                Text(
                    text = stringResource(R.string.detail_photo_missing),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Image(
                    bitmap = photo.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.detail_photo_section),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large),
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacing.large))
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
                OutlinedButton(onClick = onRemove, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = stringResource(R.string.detail_photo_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.detail_photo_close))
                }
            }
        }
    }
}

/** One sentence about what just happened, dismissed by the user. */
@Composable
private fun NoticeCard(notice: UiNotice, onDismiss: () -> Unit) {
    ParkingkokCard(contentPadding = MaterialTheme.spacing.large) {
        Text(
            text = stringResource(
                when (notice) {
                    UiNotice.PHOTO_UNREADABLE -> R.string.notice_photo_unreadable
                    UiNotice.PHOTO_NOT_SAVED -> R.string.notice_photo_not_saved
                    UiNotice.CAMERA_UNAVAILABLE -> R.string.notice_camera_unavailable
                    UiNotice.NO_MAPS_APP -> R.string.notice_no_maps_app
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.action_confirm))
        }
    }
}

@Composable
private fun EndParkingButton(onEndParking: () -> Unit) {
    PrimaryCtaButton(
        iconRes = R.drawable.ic_flag,
        label = stringResource(R.string.home_end_parking),
        caption = stringResource(R.string.home_end_parking_caption),
        onClick = onEndParking,
    )
}

@Composable
private fun DeleteRecordButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
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

private const val HERO_MAX_CHARS = 4

private val PHOTO_CARD_HEIGHT = 180.dp

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
            onDirections = {},
            onPhotoSelected = {},
            onRemovePhoto = {},
            onCameraUnavailable = {},
            onNoticeShown = {},
            onBack = {},
        )
    }
}

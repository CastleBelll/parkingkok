package com.parkingkok.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.domain.parking.ElapsedTime
import com.parkingkok.app.domain.parking.Floor
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingLocation
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingSource
import com.parkingkok.app.domain.photo.PhotoSource
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.components.BrandFooter
import com.parkingkok.app.ui.components.BrandHeader
import com.parkingkok.app.ui.components.IconChip
import com.parkingkok.app.ui.components.ParkingkokCard
import com.parkingkok.app.ui.components.ParkingkokRow
import com.parkingkok.app.ui.components.ParkingkokScreen
import com.parkingkok.app.ui.components.StaticLocationArtwork
import com.parkingkok.app.ui.components.RowChevron
import com.parkingkok.app.ui.components.SettingsAction
import com.parkingkok.app.ui.UiNotice
import com.parkingkok.app.ui.photo.ParkingPhotoPicker
import com.parkingkok.app.ui.format.dayText
import com.parkingkok.app.ui.format.elapsedText
import com.parkingkok.app.ui.format.timeOfDayText

/**
 * `01-home-main.png`.
 *
 * The order on screen is docs/10_DESIGN_UX_SPEC.md §6 exactly: current floor, zone/spot,
 * elapsed, the `-`/`+` keys, location, end parking, history preview. Nothing is inserted
 * between the active parking card and the primary actions — §6 forbids it, and the ad
 * slot that rule is about does not exist in this build either.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onStepFloor: (Int) -> Unit,
    onEndParking: () -> Unit,
    onSaveParking: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onDirections: () -> Unit,
    onPhotoSelected: (PhotoSource) -> Unit,
    onCameraUnavailable: () -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingPhoto by remember { mutableStateOf(false) }

    ParkingkokScreen(
        modifier = modifier,
        header = { BrandHeader(action = { SettingsAction(onOpenSettings) }) },
        footer = { BrandFooter() },
    ) {
        if (!state.loaded) return@ParkingkokScreen

        val active = state.active
        if (active == null) {
            item("empty") { NotParkedCard(onSaveParking = onSaveParking) }
        } else {
            item("active") {
                ActiveParkingCard(
                    record = active,
                    nowMillis = state.nowMillis,
                    onStepFloor = onStepFloor,
                )
            }
            item("actions") {
                PrimaryActions(
                    canOpenMap = state.canOpenMap,
                    hasPhoto = state.hasPhoto,
                    photoBusy = state.photoBusy,
                    onDirections = onDirections,
                    onPhoto = {
                        // The viewer lives on the detail screen; adding one starts here.
                        if (state.hasPhoto) onOpenDetail(active.id) else pickingPhoto = true
                    },
                    onOpenDetail = { onOpenDetail(active.id) },
                    onEndParking = onEndParking,
                )
            }
            val notice = state.notice
            if (notice != null) {
                item("notice") { NoticeCard(notice = notice, onDismiss = onNoticeShown) }
            }
        }

        item("recent-title") {
            RecentHeader(onOpenHistory = onOpenHistory, hasHistory = state.recent.isNotEmpty())
        }
        if (state.recent.isEmpty()) {
            item("recent-empty") { RecentEmpty() }
        } else {
            item("recent") {
                ParkingkokCard(contentPadding = 0.dp) {
                    state.recent.forEachIndexed { index, record ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = MaterialTheme.spacing.large),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        RecentRow(
                            record = record,
                            nowMillis = state.nowMillis,
                            onClick = { onOpenDetail(record.id) },
                        )
                    }
                }
            }
        }
    }

    ParkingPhotoPicker(
        visible = pickingPhoto,
        onDismiss = { pickingPhoto = false },
        onPhotoSelected = onPhotoSelected,
        onCameraUnavailable = onCameraUnavailable,
    )
}

/**
 * The hero. Floor first and largest, then zone/spot, then elapsed — §6 items 1 to 3.
 *
 * The mockup puts a map thumbnail beside the hero. It is drawn, never fetched: Android's
 * FR-008 is an external maps intent and the 2026-09-18 decision in
 * docs/04_ANDROID_IMPLEMENTATION.md §12 replaces this preview with a static
 * representation, so that opening the app does not put the parked coordinate on the
 * network. See `StaticLocationArtwork`.
 */
@Composable
private fun ActiveParkingCard(
    record: ParkingRecord,
    nowMillis: Long,
    onStepFloor: (Int) -> Unit,
) {
    ParkingkokCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The live dot is paired with the words beside it; §8 of
            // docs/01_PRODUCT_REQUIREMENTS.md rules out signalling state by colour alone.
            Box(
                Modifier
                    .size(10.dp)
                    .clearAndSetSemantics { },
            ) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                    content = {},
                )
            }
            Spacer(Modifier.width(MaterialTheme.spacing.small))
            Text(
                text = stringResource(R.string.home_active_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
        }

        Spacer(Modifier.height(MaterialTheme.spacing.medium))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                FloorHero(
                    floor = record.floor,
                    spokenPrefix = stringResource(R.string.home_active_title),
                )

                val supporting = listOfNotNull(record.zone, record.spot)
                if (supporting.isNotEmpty()) {
                    Spacer(Modifier.height(MaterialTheme.spacing.tiny))
                    Text(
                        text = supporting.joinToString(" · "),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(MaterialTheme.spacing.tiny))
                Text(
                    text = elapsedText(ElapsedTime.since(record.startedAtMillis, nowMillis)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (record.location != null) {
                Spacer(Modifier.width(MaterialTheme.spacing.medium))
                StaticLocationArtwork(
                    modifier = Modifier.size(width = 132.dp, height = 112.dp),
                    pinLabel = record.floor?.displayLabel,
                    pinSize = 26.dp,
                )
            }
        }

        if (record.location == null) {
            // No coordinate to stand in for: say so instead of drawing a pin (FR-001).
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            LocationChip(location = null)
        }

        Spacer(Modifier.height(MaterialTheme.spacing.large))
        FloorStepper(floor = record.floor, onStepFloor = onStepFloor)
    }
}

/**
 * The floor, as big as it can be read.
 *
 * A free-text floor can be a whole phrase, so the hero drops to the smaller display style
 * rather than truncating — §4 allows the hero to scale down within a safe minimum, and a
 * floor the user cannot read is worse than one that is not enormous.
 */
@Composable
private fun FloorHero(floor: Floor?, spokenPrefix: String) {
    val label = floor?.displayLabel ?: stringResource(R.string.home_no_floor)
    val spoken = floor?.spokenLabel ?: label
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
        // TalkBack reads "B3" letter by letter; docs/10 §12 asks for "지하 3층".
        modifier = Modifier.semantics { contentDescription = "$spokenPrefix, $spoken" },
    )
}

/** The `-` / `+` keys of §6 item 4. Disabled, with a reason, when the floor is free text. */
@Composable
private fun FloorStepper(floor: Floor?, onStepFloor: (Int) -> Unit) {
    val steppable = floor?.isSteppable == true
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperKey(
                iconRes = R.drawable.ic_minus,
                contentDescription = stringResource(R.string.home_floor_decrease),
                enabled = steppable && FloorParser.step(floor, -1) != null,
                onClick = { onStepFloor(-1) },
            )
            StepperKey(
                iconRes = R.drawable.ic_plus,
                contentDescription = stringResource(R.string.home_floor_increase),
                enabled = steppable && FloorParser.step(floor, 1) != null,
                onClick = { onStepFloor(1) },
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = stringResource(
                if (steppable) R.string.home_floor_hint else R.string.home_floor_hint_fixed,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepperKey(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier
            .width(96.dp)
            .height(MaterialTheme.spacing.touchTarget + 8.dp),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = contentDescription)
    }
}

/**
 * Said in place of the map thumbnail when the record has no coordinate.
 *
 * A manual save made with location permission denied is a normal, supported outcome
 * (FR-001), not a degraded one, so it gets a plain sentence rather than an error.
 */
@Composable
private fun LocationChip(location: ParkingLocation?) {
    val accuracy = location?.horizontalAccuracyM
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconChip(
            iconRes = R.drawable.ic_place,
            containerColor = if (location != null) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (location != null) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            size = 32.dp,
        )
        Spacer(Modifier.width(MaterialTheme.spacing.medium))
        Column {
            Text(
                text = stringResource(
                    if (location != null) R.string.location_saved else R.string.location_none,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    accuracy != null -> stringResource(R.string.location_accuracy, accuracy.toInt())
                    location != null -> stringResource(R.string.location_saved)
                    else -> stringResource(R.string.location_none_caption)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * §6 items 5 and 6, and the mockup's first two rows: `주차 위치 보기` then `사진 추가`.
 *
 * Both work now. The map row hands the coordinate to an external maps app (FR-008 on
 * Android, docs/04_ANDROID_IMPLEMENTATION.md §12) and is disabled — with the reason in its
 * supporting line, never colour alone — for a record saved without one. `주차 상세 보기` is
 * this app's own addition below them, because the shell needs a way into the detail
 * screen that the mockup leaves to a tap on the card.
 */
@Composable
private fun PrimaryActions(
    canOpenMap: Boolean,
    hasPhoto: Boolean,
    photoBusy: Boolean,
    onDirections: () -> Unit,
    onPhoto: () -> Unit,
    onOpenDetail: () -> Unit,
    onEndParking: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        ParkingkokCard(contentPadding = 0.dp) {
            ParkingkokRow(
                title = stringResource(R.string.home_map),
                supporting = stringResource(
                    if (canOpenMap) R.string.home_map_caption else R.string.home_map_caption_none,
                ),
                iconRes = R.drawable.ic_map,
                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                enabled = canOpenMap,
                onClick = onDirections,
                trailing = { RowChevron(enabled = canOpenMap) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = MaterialTheme.spacing.large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ParkingkokRow(
                title = stringResource(
                    if (hasPhoto) R.string.home_photo_view else R.string.home_photo_add,
                ),
                supporting = stringResource(
                    if (hasPhoto) {
                        R.string.home_photo_view_caption
                    } else {
                        R.string.home_photo_add_caption
                    },
                ),
                iconRes = R.drawable.ic_photo,
                enabled = !photoBusy,
                onClick = onPhoto,
                trailing = { RowChevron(enabled = !photoBusy) },
            )
        }
        ParkingkokCard(contentPadding = 0.dp) {
            ParkingkokRow(
                title = stringResource(R.string.home_detail),
                supporting = stringResource(R.string.home_detail_caption),
                iconRes = R.drawable.ic_car,
                onClick = onOpenDetail,
                trailing = { RowChevron() },
            )
        }
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

/** Nothing is parked: one card, one job — start a record (FR-001). */
@Composable
private fun NotParkedCard(onSaveParking: () -> Unit) {
    ParkingkokCard {
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.large))
        Button(
            onClick = onSaveParking,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MaterialTheme.spacing.touchTarget + 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_place),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.small))
            Text(
                text = stringResource(R.string.home_save_parking),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun RecentHeader(onOpenHistory: () -> Unit, hasHistory: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_recent_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (hasHistory) {
            TextButton(onClick = onOpenHistory) {
                Text(
                    text = stringResource(R.string.home_recent_all),
                    style = MaterialTheme.typography.labelLarge,
                )
                RowChevron()
            }
        }
    }
}

@Composable
private fun RecentEmpty() {
    ParkingkokCard {
        Text(
            text = stringResource(R.string.home_recent_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentRow(record: ParkingRecord, nowMillis: Long, onClick: () -> Unit) {
    val title = listOfNotNull(record.floor?.displayLabel, record.zone).joinToString(" · ")
        .ifEmpty { stringResource(R.string.home_no_floor) }
    ParkingkokRow(
        title = title,
        supporting = dayText(record.startedAtMillis, nowMillis),
        iconRes = R.drawable.ic_car,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeOfDayText(record.startedAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(MaterialTheme.spacing.tiny))
                RowChevron()
            }
        },
    )
}

/** Longer than this and the 60sp hero stops fitting on a narrow phone. */
private const val HERO_MAX_CHARS = 4

@Preview(name = "Home - parked", showBackground = true)
@Composable
private fun HomeParkedPreview() {
    ParkingkokTheme {
        HomeScreen(
            state = HomeUiState(
                active = previewRecord(),
                recent = listOf(previewRecord(id = "b", floorRaw = "B2", endedAt = 1L)),
                nowMillis = PREVIEW_NOW,
                loaded = true,
            ),
            onStepFloor = {},
            onEndParking = {},
            onSaveParking = {},
            onOpenDetail = {},
            onOpenHistory = {},
            onOpenSettings = {},
            onDirections = {},
            onPhotoSelected = {},
            onCameraUnavailable = {},
            onNoticeShown = {},
        )
    }
}

@Preview(name = "Home - empty", showBackground = true)
@Composable
private fun HomeEmptyPreview() {
    ParkingkokTheme {
        HomeScreen(
            state = HomeUiState(loaded = true, nowMillis = PREVIEW_NOW),
            onStepFloor = {},
            onEndParking = {},
            onSaveParking = {},
            onOpenDetail = {},
            onOpenHistory = {},
            onOpenSettings = {},
            onDirections = {},
            onPhotoSelected = {},
            onCameraUnavailable = {},
            onNoticeShown = {},
        )
    }
}

private const val PREVIEW_NOW = 1_700_005_040_000L

private fun previewRecord(
    id: String = "a",
    floorRaw: String = "B3",
    endedAt: Long? = null,
) = ParkingRecord(
    id = id,
    startedAtMillis = 1_700_000_000_000L,
    endedAtMillis = endedAt,
    source = ParkingSource.MANUAL,
    confidenceBucket = null,
    location = ParkingLocation(37.5, 127.0, 18f, 1_700_000_000_000L),
    floor = FloorParser.parse(floorRaw),
    zone = "A구역",
    spot = "142",
    memo = null,
    photoRelativePath = null,
    createdAtMillis = 1_700_000_000_000L,
    updatedAtMillis = 1_700_000_000_000L,
    revision = 1,
)

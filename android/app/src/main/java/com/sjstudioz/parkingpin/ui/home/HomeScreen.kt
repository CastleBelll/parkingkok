package com.sjstudioz.parkingpin.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sjstudioz.parkingpin.R
import com.sjstudioz.parkingpin.domain.detection.ParkingCandidateNotice
import com.sjstudioz.parkingpin.domain.parking.ElapsedTime
import com.sjstudioz.parkingpin.domain.parking.Floor
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import com.sjstudioz.parkingpin.domain.photo.PhotoSource
import com.sjstudioz.parkingpin.theme.ParkingpinTheme
import com.sjstudioz.parkingpin.theme.spacing
import com.sjstudioz.parkingpin.ui.components.BrandHeader
import com.sjstudioz.parkingpin.ui.components.IconChip
import com.sjstudioz.parkingpin.ui.components.ParkingpinCard
import com.sjstudioz.parkingpin.ui.components.PrimaryCtaButton
import com.sjstudioz.parkingpin.ui.components.ParkingpinRow
import com.sjstudioz.parkingpin.ui.components.ParkingpinScreen
import com.sjstudioz.parkingpin.ui.components.StaticLocationArtwork
import com.sjstudioz.parkingpin.ui.components.RowChevron
import com.sjstudioz.parkingpin.ui.components.NotificationsAction
import com.sjstudioz.parkingpin.ui.components.SettingsAction
import com.sjstudioz.parkingpin.ui.motion.LocalMotionEnabled
import com.sjstudioz.parkingpin.ui.motion.MotionDurations
import com.sjstudioz.parkingpin.ui.motion.pressScale
import com.sjstudioz.parkingpin.ui.UiNotice
import com.sjstudioz.parkingpin.ui.photo.ParkingPhotoPicker
import com.sjstudioz.parkingpin.ui.photo.PillarSuggestionCard
import com.sjstudioz.parkingpin.ui.format.dayText
import com.sjstudioz.parkingpin.ui.format.elapsedText
import com.sjstudioz.parkingpin.ui.format.timeOfDayText

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
    onPhotoEntry: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCandidate: (String) -> Unit,
    onDirections: () -> Unit,
    onPhotoSelected: (PhotoSource) -> Unit,
    onCameraUnavailable: () -> Unit,
    onNoticeShown: () -> Unit,
    onApplyPillarSuggestion: () -> Unit,
    onDismissPillarSuggestion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingPhoto by remember { mutableStateOf(false) }

    ParkingpinScreen(
        modifier = modifier,
        header = {
            BrandHeader(
                actions = {
                    // §7b's dot: a candidate nobody has answered yet, and the one reason
                    // the bell's screen exists. It is the same value the row below the
                    // hero is drawn from, so the two can never disagree.
                    NotificationsAction(
                        unanswered = state.pendingCandidateId != null,
                        onClick = onOpenNotifications,
                    )
                    SettingsAction(onOpenSettings)
                },
            )
        },
    ) {
        if (!state.loaded) return@ParkingpinScreen

        val active = state.active
        item("hero") {
            HeroSlot(
                active = active,
                nowMillis = state.nowMillis,
                onStepFloor = onStepFloor,
                onSaveParking = onSaveParking,
                onPhotoEntry = onPhotoEntry,
            )
        }
        val candidateId = state.pendingCandidateId
        if (candidateId != null) {
            item("candidate") {
                PendingCandidateRow(
                    parkedAtMillis = state.pendingCandidateAtMillis,
                    onClick = { onOpenCandidate(candidateId) },
                )
            }
        }

        // docs/02 §6a. Above the actions, because the user has just tapped 사진 추가 and
        // this is the answer to it; below the hero, because the floor on screen still
        // leads (docs/10 §6). It is gone the moment it is applied or waved away.
        val suggestion = state.pillarSuggestion
        if (active != null && suggestion != null) {
            item("pillar-suggestion") {
                PillarSuggestionCard(
                    suggestion = suggestion,
                    onApply = onApplyPillarSuggestion,
                    onDismiss = onDismissPillarSuggestion,
                )
            }
        }

        if (active != null) {
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
                ParkingpinCard(contentPadding = 0.dp) {
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
 * The unanswered guess, on the screen the user actually opens.
 *
 * docs/05 §10a puts this here: notification permission is not a condition of correctness,
 * so a candidate has to be reachable without one. It sits under the hero rather than above
 * it because docs/10 §6 ranks the current parking first and this is only a question about
 * one — and it is a single bordered row, not a fifth card, because a stack of cards is the
 * dashboard the design harness rules out.
 *
 * It carries the copy the notification carries and the time, and nothing else: no floor, no
 * address, no coordinate (docs/09 §9).
 */
@Composable
private fun PendingCandidateRow(parkedAtMillis: Long?, onClick: () -> Unit) {
    ParkingpinCard(contentPadding = 0.dp) {
        ParkingpinRow(
            title = ParkingCandidateNotice.TITLE,
            supporting = if (parkedAtMillis != null) {
                stringResource(R.string.home_candidate_supporting, timeOfDayText(parkedAtMillis))
            } else {
                stringResource(R.string.candidate_confirm_body)
            },
            iconRes = R.drawable.ic_car,
            onClick = onClick,
            trailing = { RowChevron() },
        )
    }
}

/**
 * The one card at the top of the screen, whichever card that currently is.
 *
 * Saving or ending a parking swaps the whole hero. It crossfades and resizes rather than
 * appearing, because the two cards are the same object in two states — a pop would read as
 * a second card arriving, and would put a bounce on the most important moment in the app.
 */
@Composable
private fun HeroSlot(
    active: ParkingRecord?,
    nowMillis: Long,
    onStepFloor: (Int) -> Unit,
    onSaveParking: () -> Unit,
    onPhotoEntry: () -> Unit,
) {
    val motionEnabled = LocalMotionEnabled.current
    AnimatedContent(
        targetState = active,
        // Keyed on identity, so the ticking elapsed line updates the card in place instead
        // of crossfading it once a minute.
        contentKey = { it?.id },
        transitionSpec = {
            if (motionEnabled) {
                val spec = tween<Float>(MotionDurations.CARD_SWAP_MS)
                fadeIn(spec) togetherWith fadeOut(spec) using
                    SizeTransform(clip = false) { _, _ ->
                        tween(MotionDurations.CARD_SWAP_MS)
                    }
            } else {
                EnterTransition.None togetherWith ExitTransition.None using null
            }
        },
        label = "hero",
    ) { record ->
        if (record == null) {
            NotParkedCard(onSaveParking = onSaveParking, onPhotoEntry = onPhotoEntry)
        } else {
            ActiveParkingCard(
                record = record,
                nowMillis = nowMillis,
                onStepFloor = onStepFloor,
            )
        }
    }
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
    // One step nearer than the rows below it: this is the card the screen is about.
    ParkingpinCard {
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

                // `A구역 · 142`, or `142번` when there is no zone. A spot on its own used
                // to render as the bare number, so a record holding only `03` showed `03`
                // under the floor at hero weight — a large unexplained numeral.
                val zone = record.zone
                val spot = record.spot
                val supporting = when {
                    zone != null && spot != null -> "$zone · $spot"
                    zone != null -> zone
                    spot != null -> stringResource(R.string.home_spot_only, spot)
                    else -> null
                }
                if (supporting != null) {
                    Spacer(Modifier.height(MaterialTheme.spacing.tiny))
                    Text(
                        text = supporting,
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
                    modifier = Modifier.size(width = 136.dp, height = 116.dp),
                    pinLabel = record.floor?.displayLabel,
                    zoneLabel = record.zone,
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
    val motionEnabled = LocalMotionEnabled.current
    // The ladder position, so a `+` rolls the value upward and a `-` rolls it down.
    val rung = floor?.signedIndex ?: 0

    AnimatedContent(
        targetState = label to rung,
        transitionSpec = {
            if (!motionEnabled) {
                EnterTransition.None togetherWith ExitTransition.None using null
            } else {
                val rising = targetState.second > initialState.second
                val spec = tween<Float>(MotionDurations.FLOOR_SWAP_MS)
                val travel = tween<IntOffset>(MotionDurations.FLOOR_SWAP_MS)
                val enter = fadeIn(spec) + slideInVertically(travel) { height ->
                    if (rising) height / HERO_ROLL_DIVISOR else -height / HERO_ROLL_DIVISOR
                }
                val exit = fadeOut(spec) + slideOutVertically(travel) { height ->
                    if (rising) -height / HERO_ROLL_DIVISOR else height / HERO_ROLL_DIVISOR
                }
                // clip = false so a value rolling past the edge is not cut off mid-flight.
                enter togetherWith exit using SizeTransform(clip = false)
            }
        },
        // The whole hero is one TalkBack node, so the animation cannot make it stutter.
        modifier = Modifier.semantics { contentDescription = "$spokenPrefix, $spoken" },
        label = "floorHero",
    ) { (value, _) ->
        Text(
            text = value,
            style = if (value.length <= HERO_MAX_CHARS) {
                MaterialTheme.typography.displayLarge
            } else {
                MaterialTheme.typography.displayMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * The `-` / `+` keys of §6 item 4. Disabled, with a reason, when the floor is free text.
 *
 * Two keys sized to what they hold, with a hairline between them — `01-home-main.png`
 * gives them about half the card's width in total. They used to be a pair of full-bleed
 * slabs, which made nudging the floor look like the card's primary action; it is not, the
 * floor itself is. They stay [STEPPER_HEIGHT] tall so the touch target is never below the
 * minimum in docs/01_PRODUCT_REQUIREMENTS.md §8.
 */
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
            Box(
                Modifier
                    .width(1.dp)
                    .height(STEPPER_DIVIDER_HEIGHT)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            StepperKey(
                iconRes = R.drawable.ic_plus,
                contentDescription = stringResource(R.string.home_floor_increase),
                enabled = steppable && FloorParser.step(floor, 1) != null,
                onClick = { onStepFloor(1) },
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.medium))
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
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource,
        modifier = Modifier
            .width(STEPPER_WIDTH)
            .height(STEPPER_HEIGHT)
            .pressScale(interactionSource),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(STEPPER_GLYPH),
        )
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
        ParkingpinCard(contentPadding = 0.dp) {
            ParkingpinRow(
                title = stringResource(R.string.home_map),
                supporting = stringResource(
                    if (canOpenMap) R.string.home_map_caption else R.string.home_map_caption_none,
                ),
                iconRes = R.drawable.ic_map,
                enabled = canOpenMap,
                onClick = onDirections,
                trailing = { RowChevron(enabled = canOpenMap) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = MaterialTheme.spacing.large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ParkingpinRow(
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
            // Detail used to sit in a card of its own. Three actions in two cards is the
            // stack of panels the design harness calls the AI dashboard: the cards were
            // drawing boundaries where the content has none. One group, divided.
            HorizontalDivider(
                modifier = Modifier.padding(start = MaterialTheme.spacing.large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ParkingpinRow(
                title = stringResource(R.string.home_detail),
                supporting = stringResource(R.string.home_detail_caption),
                iconRes = R.drawable.ic_car,
                onClick = onOpenDetail,
                trailing = { RowChevron() },
            )
        }
        PrimaryCtaButton(
            iconRes = R.drawable.ic_flag,
            label = stringResource(R.string.home_end_parking),
            caption = stringResource(R.string.home_end_parking_caption),
            onClick = onEndParking,
        )
    }
}

/** One sentence about what just happened, dismissed by the user. */
@Composable
private fun NoticeCard(notice: UiNotice, onDismiss: () -> Unit) {
    ParkingpinCard(contentPadding = MaterialTheme.spacing.large) {
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
/**
 * The empty state, and the only place a manual save can start from.
 *
 * **`사진으로 입력` is here because otherwise the pillar reader is unreachable by hand**
 * (docs/02 §6a). It was wired to the auto-detection confirmation screen and nowhere else, so
 * a user who saves manually — which is everyone, before a drive has ever been detected —
 * typed the floor and attached a photo afterwards, and the OCR never saw it in time to help.
 *
 * Secondary, under the filled button: the design harness allows one emphasised CTA per
 * screen, and this is the same pairing the confirmation screen already uses.
 */
@Composable
private fun NotParkedCard(onSaveParking: () -> Unit, onPhotoEntry: () -> Unit) {
    ParkingpinCard {
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
        val saveInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onSaveParking,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(
                horizontal = MaterialTheme.spacing.large,
                vertical = MaterialTheme.spacing.medium,
            ),
            interactionSource = saveInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MaterialTheme.spacing.touchTarget + 8.dp)
                .pressScale(saveInteraction),
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
        TextButton(
            onClick = onPhotoEntry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MaterialTheme.spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_camera),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.small))
            Text(
                text = stringResource(R.string.home_photo_entry),
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
    ParkingpinCard {
        Text(
            text = stringResource(R.string.home_recent_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentRow(record: ParkingRecord, nowMillis: Long, onClick: () -> Unit) {
    // `01-home-main.png` draws this row on one line: floor and day in the title, the
    // clock alone on the right. The zone belongs to the full history screen, whose title
    // is not already carrying the date. Keeping the day on a second supporting line cost
    // the row a whole line and drifted from iOS, which reads `B2 · 어제  오후 2:32`.
    val floor = record.floor?.displayLabel ?: stringResource(R.string.home_no_floor)
    val title = "$floor · ${dayText(record.startedAtMillis, nowMillis)}"
    ParkingpinRow(
        title = title,
        supporting = null,
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

/** A floor rolls in from a third of its own height away — a nudge, not a slot machine. */
private const val HERO_ROLL_DIVISOR = 3

/** Wide enough for the glyph and a thumb, narrow enough not to look like the main action. */
private val STEPPER_WIDTH = 64.dp

/** The minimum touch target of docs/01_PRODUCT_REQUIREMENTS.md §8, exactly. */
private val STEPPER_HEIGHT = 48.dp

private val STEPPER_GLYPH = 22.dp

/** Shorter than the keys, so it reads as a separator and not as a third control. */
private val STEPPER_DIVIDER_HEIGHT = 26.dp

@Preview(name = "Home - parked", showBackground = true)
@Composable
private fun HomeParkedPreview() {
    ParkingpinTheme {
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
            onPhotoEntry = {},
            onOpenDetail = {},
            onOpenHistory = {},
            onOpenSettings = {},
            onOpenNotifications = {},
            onOpenCandidate = {},
            onDirections = {},
            onPhotoSelected = {},
            onApplyPillarSuggestion = {},
            onDismissPillarSuggestion = {},
            onCameraUnavailable = {},
            onNoticeShown = {},
        )
    }
}

@Preview(name = "Home - empty", showBackground = true)
@Composable
private fun HomeEmptyPreview() {
    ParkingpinTheme {
        HomeScreen(
            state = HomeUiState(loaded = true, nowMillis = PREVIEW_NOW),
            onStepFloor = {},
            onEndParking = {},
            onSaveParking = {},
            onPhotoEntry = {},
            onOpenDetail = {},
            onOpenHistory = {},
            onOpenSettings = {},
            onOpenNotifications = {},
            onOpenCandidate = {},
            onDirections = {},
            onPhotoSelected = {},
            onApplyPillarSuggestion = {},
            onDismissPillarSuggestion = {},
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

@Preview(name = "Home - candidate pending", showBackground = true)
@Composable
private fun HomeCandidatePreview() {
    ParkingpinTheme {
        HomeScreen(
            state = HomeUiState(
                loaded = true,
                nowMillis = PREVIEW_NOW,
                pendingCandidateId = "candidate-1",
                pendingCandidateAtMillis = PREVIEW_NOW,
            ),
            onStepFloor = {},
            onEndParking = {},
            onSaveParking = {},
            onPhotoEntry = {},
            onOpenDetail = {},
            onOpenHistory = {},
            onOpenSettings = {},
            onOpenNotifications = {},
            onOpenCandidate = {},
            onDirections = {},
            onPhotoSelected = {},
            onApplyPillarSuggestion = {},
            onDismissPillarSuggestion = {},
            onCameraUnavailable = {},
            onNoticeShown = {},
        )
    }
}

package com.parkingkok.app.ui.confirm

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.domain.detection.ParkingCandidateNotice
import com.parkingkok.app.domain.parking.Floor
import com.parkingkok.app.domain.parking.FloorKind
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.components.DetailHeader
import com.parkingkok.app.ui.components.ParkingkokScreen
import com.parkingkok.app.ui.format.timeOfDayText
import com.parkingkok.app.ui.motion.pressScale

/**
 * The confirmation screen — `docs/10_DESIGN_UX_SPEC.md` §7a, in the order §7a fixes:
 * the uncertainty, then when it happened, then the floor choice, then the way out.
 *
 * **What is deliberately absent.** No map, no address, no coordinate, no guessed floor.
 * §7a rules all four out and docs/09 §9 keeps location off this surface; the state this
 * screen renders has no field that could carry one, so the rule is structural rather than
 * a thing to remember.
 *
 * **The two Korean strings that are not in `strings.xml`.** 주차한 것 같아요 and 주차 아님 also
 * appear on the notification, where they are fixed verbatim by
 * `docs/02_PRODUCT_SCOPE_AND_FLOWS.md` §5 and unreadable from a JVM test if they live in
 * resources. Reading both from [ParkingCandidateNotice] is what makes it impossible for
 * the shade and the screen to drift apart — the copy is one constant with one test on it.
 */
@Composable
fun ConfirmCandidateScreen(
    state: ConfirmCandidateUiState,
    onPickFloor: (Floor) -> Unit,
    onManualEntry: () -> Unit,
    onPhotoEntry: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ParkingkokScreen(
        modifier = modifier,
        // §7a "Not a modal": a pushed screen with an ordinary back. Backing out leaves the
        // candidate pending until it expires. The header carries no title, because the
        // screen's first line is the title.
        header = { DetailHeader(title = "", onBack = onBack) },
        footer = {
            // §7a: full width, under the choices, reachable without a scroll on the
            // smallest supported screen. It sits outside the scrolling area precisely so
            // that stays true at any font scale — it is the honest answer to a guess, and
            // an honest answer the user has to hunt for is not one.
            RejectButton(enabled = !state.working, onReject = onReject)
        },
    ) {
        if (!state.loaded || state.gone) return@ParkingkokScreen

        item("question") {
            Text(
                text = ParkingCandidateNotice.TITLE,
                // The largest thing on the screen (§7a hierarchy). Nothing else on this
                // surface competes with it.
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }

        item("when") {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.tiny)) {
                val parkedAt = state.parkedAtMillis
                if (parkedAt != null) {
                    Text(
                        text = timeOfDayText(parkedAt),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = stringResource(R.string.candidate_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("floors") {
            FloorChoices(
                picks = state.floorPicks,
                enabled = !state.working,
                onPickFloor = onPickFloor,
                onManualEntry = onManualEntry,
                onPhotoEntry = onPhotoEntry,
            )
        }

        if (state.alreadyActive) {
            item("already-active") {
                Text(
                    text = stringResource(R.string.candidate_confirm_already_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * §7a "The floor choices": up to three picks from the user's own history, then the two
 * escapes.
 *
 * The picks are the screen's one emphasised action — choosing a floor *is* confirming —
 * so they carry the primary fill and everything else on the screen is secondary or text
 * (CLAUDE.md design harness). They share a row at equal width; the escapes take their own
 * line rather than squeezing into a fourth column, because Korean labels at a large font
 * scale stop being readable before they stop fitting. With no history the picks row is
 * empty and the escapes are the whole choice, which is exactly §7a's first-ever run.
 *
 * `사진으로 입력` comes first because it is the faster of the two and typing less is the
 * point (§7a). Both land in the same manual entry form; the photo one arrives with what
 * the pillar said already filled in.
 */
@Composable
private fun FloorChoices(
    picks: List<Floor>,
    enabled: Boolean,
    onPickFloor: (Floor) -> Unit,
    onManualEntry: () -> Unit,
    onPhotoEntry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        if (picks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                for (floor in picks) {
                    val interaction = remember(floor.displayLabel) { MutableInteractionSource() }
                    // Tonal, not filled. Three filled blue buttons are three primary CTAs
                    // shouting at once, which CLAUDE.md's harness allows one of — and they
                    // are peers, so none of them may look like the answer.
                    FilledTonalButton(
                        onClick = { onPickFloor(floor) },
                        enabled = enabled,
                        shape = MaterialTheme.shapes.small,
                        interactionSource = interaction,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = FLOOR_PICK_HEIGHT)
                            .pressScale(interaction)
                            // `B3` is read out letter by letter otherwise
                            // (docs/10 §12).
                            .semantics { contentDescription = floor.spokenLabel },
                    ) {
                        Text(
                            text = floor.displayLabel,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            Escape(
                label = stringResource(R.string.candidate_confirm_photo),
                enabled = enabled,
                onClick = onPhotoEntry,
                modifier = Modifier.weight(1f),
            )
            Escape(
                label = stringResource(R.string.candidate_confirm_manual),
                enabled = enabled,
                onClick = onManualEntry,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One of §7a's two ways into the form. Outlined, because neither is the answer. */
@Composable
private fun Escape(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.heightIn(min = FLOOR_PICK_HEIGHT),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

/** §7a 주차 아님: a text button, never a destructive-looking one. It is an ordinary answer. */
@Composable
private fun RejectButton(enabled: Boolean, onReject: () -> Unit) {
    TextButton(
        onClick = onReject,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MaterialTheme.spacing.touchTarget)
            .padding(
                start = MaterialTheme.spacing.gutter,
                end = MaterialTheme.spacing.gutter,
                bottom = MaterialTheme.spacing.large,
            ),
    ) {
        Text(
            text = ParkingCandidateNotice.ACTION_REJECT,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Room for a floor label at a large font scale without the row growing a second line. */
private val FLOOR_PICK_HEIGHT = 56.dp

@Preview(name = "Confirm — three picks", showBackground = true)
@Composable
private fun ConfirmCandidatePreview() {
    ParkingkokTheme {
        ConfirmCandidateScreen(
            state = ConfirmCandidateUiState(
                loaded = true,
                parkedAtMillis = 1_700_000_000_000L,
                floorPicks = listOf(
                    Floor("B3", FloorKind.BASEMENT, 3),
                    Floor("B1", FloorKind.BASEMENT, 1),
                    Floor("2F", FloorKind.GROUND, 2),
                ),
            ),
            onPickFloor = {},
            onManualEntry = {},
            onPhotoEntry = {},
            onReject = {},
            onBack = {},
        )
    }
}

@Preview(name = "Confirm — first run", showBackground = true)
@Composable
private fun ConfirmCandidateFirstRunPreview() {
    ParkingkokTheme {
        ConfirmCandidateScreen(
            state = ConfirmCandidateUiState(loaded = true, parkedAtMillis = 1_700_000_000_000L),
            onPickFloor = {},
            onManualEntry = {},
            onPhotoEntry = {},
            onReject = {},
            onBack = {},
        )
    }
}

package com.parkingpin.app.ui.confirm

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.parkingpin.app.R
import com.parkingpin.app.domain.detection.ParkingCandidateNotice
import com.parkingpin.app.theme.ParkingpinTheme
import com.parkingpin.app.theme.spacing
import com.parkingpin.app.ui.components.DetailHeader
import com.parkingpin.app.ui.components.ParkingpinScreen
import com.parkingpin.app.ui.components.StaticLocationArtwork
import com.parkingpin.app.ui.format.timeOfDayText

/**
 * The confirmation screen — `docs/10_DESIGN_UX_SPEC.md` §7a, in the order §7a fixes:
 * the uncertainty, then when it happened, then where, then the way in, then the way out.
 *
 * **What is deliberately absent.** No address and no guessed floor. §7a rules both out —
 * the engine does not know which floor you are on, and an address is a claim this app is
 * not entitled to make about a fix taken on the way into a garage.
 *
 * **Where, though, is present.** An earlier draft of this file said the screen shows no
 * location at all "because docs/09 §9 keeps it off this surface". That citation was wrong:
 * §9 is Google RTDN security and says nothing about location. The screen was left claiming
 * 마지막 위치를 저장했어요 while refusing to say which — so §7a now puts the fix under FR-008's
 * `마지막으로 확인된 위치`, and `위치 없음` in the same place when there is none.
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
    onManualEntry: () -> Unit,
    onPhotoEntry: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ParkingpinScreen(
        modifier = modifier,
        // §7a "Not a modal": a pushed screen with an ordinary back. Backing out leaves the
        // candidate pending until it expires. The header carries no title, because the
        // screen's first line is the title.
        header = { DetailHeader(title = "", onBack = onBack) },
    ) {
        if (!state.loaded || state.gone) return@ParkingpinScreen

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
                if (state.location != null) {
                    // Only where it is true. With no fix this line used to promise a saved
                    // location the screen then could not name.
                    Text(
                        text = stringResource(R.string.candidate_confirm_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item("where") {
            LastKnownLocation(location = state.location)
        }

        item("entry") {
            EntryChoices(
                enabled = !state.working,
                onManualEntry = onManualEntry,
                onPhotoEntry = onPhotoEntry,
            )
        }

        item("reject") {
            // §7a: directly under the choices, separated by a divider, and *not* pinned to
            // the bottom of the screen. It used to live in the scaffold's footer, which on
            // a short screen left it floating alone in empty space — where a text button
            // stops reading as a control at all. iOS draws the same two elements.
            RejectButton(enabled = !state.working, onReject = onReject)
        }
    }
}

/**
 * §7a "where". FR-008's wording above whatever the platform can honestly draw.
 *
 * The artwork carries no coordinate — it is the same block plan for every parking
 * (see [StaticLocationArtwork]) — so the radius beside it is the part that actually says
 * anything, and it is the part the user is standing there to check.
 */
@Composable
private fun LastKnownLocation(location: ConfirmLocation?) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(
            text = stringResource(R.string.candidate_confirm_where),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (location == null) {
            // §7a: the same place, not a hidden row. A drive that ended underground with no
            // fix is ordinary, and saying so is what keeps the line above it honest.
            Text(
                text = stringResource(R.string.location_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticLocationArtwork(
                modifier = Modifier.size(LOCATION_PREVIEW_SIDE),
                pinSize = 24.dp,
            )
            Text(
                text = location.accuracyM
                    ?.let { stringResource(R.string.location_accuracy, it) }
                    ?: stringResource(R.string.location_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The same square iOS gives the hero thumbnail, so the two screens read at one size. */
private val LOCATION_PREVIEW_SIDE = 112.dp

/**
 * §7a's two ways into the form.
 *
 * There used to be up to three one-tap floor picks above these, read from the user's own
 * history. The product owner removed them on 2026-09-20; §7a records the trade. What is
 * left is the two escapes, which share a row at equal width — a third and fourth column
 * were never possible, because Korean labels at a large font scale stop being readable
 * before they stop fitting.
 *
 * `사진으로 입력` comes first because it is the faster of the two and typing less is the
 * point (§7a). Both land in the same manual entry form; the photo one arrives with what
 * the pillar said already filled in.
 */
@Composable
private fun EntryChoices(
    enabled: Boolean,
    onManualEntry: () -> Unit,
    onPhotoEntry: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Escape(
            label = stringResource(R.string.candidate_confirm_photo),
            enabled = enabled,
            onClick = onPhotoEntry,
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_camera,
        )
        Escape(
            label = stringResource(R.string.candidate_confirm_manual),
            enabled = enabled,
            onClick = onManualEntry,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One of §7a's two ways into the form. Outlined, because neither is the answer. */
@Composable
private fun Escape(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.heightIn(min = CHOICE_HEIGHT),
    ) {
        if (iconRes != null) {
            // The one glyph on this screen, and it carries meaning: it says the button
            // opens a camera rather than a keyboard, which the two labels alone leave to
            // reading. iOS shows the same.
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(ESCAPE_ICON_SIZE),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.small))
        }
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Sized against the label beside it, not against a touch target. */
private val ESCAPE_ICON_SIZE = 20.dp

/**
 * §7a 주차 아님.
 *
 * Outlined, with the danger colour on the **label only** — never a red fill, which would
 * make the quietest answer on the screen the loudest thing on it. §7a records why the
 * colour is a product decision rather than a design one.
 *
 * It was a bare `TextButton` in the scaffold's footer, which on a short screen left a stray
 * line of blue floating in empty space and reading as nothing at all. The border is what
 * makes it a control. iOS draws the identical pair.
 */
@Composable
private fun RejectButton(enabled: Boolean, onReject: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedButton(
            onClick = onReject,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CHOICE_HEIGHT),
        ) {
            Text(
                text = ParkingCandidateNotice.ACTION_REJECT,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Room for a Korean label at a large font scale without the row growing a second line. */
private val CHOICE_HEIGHT = 56.dp

@Preview(name = "Confirm — with a fix", showBackground = true)
@Composable
private fun ConfirmCandidatePreview() {
    ParkingpinTheme {
        ConfirmCandidateScreen(
            state = ConfirmCandidateUiState(
                loaded = true,
                parkedAtMillis = 1_700_000_000_000L,
                location = ConfirmLocation(accuracyM = 24),
            ),
            onManualEntry = {},
            onPhotoEntry = {},
            onReject = {},
            onBack = {},
        )
    }
}

@Preview(name = "Confirm — no fix", showBackground = true)
@Composable
private fun ConfirmCandidateNoLocationPreview() {
    ParkingpinTheme {
        ConfirmCandidateScreen(
            state = ConfirmCandidateUiState(loaded = true, parkedAtMillis = 1_700_000_000_000L),
            onManualEntry = {},
            onPhotoEntry = {},
            onReject = {},
            onBack = {},
        )
    }
}

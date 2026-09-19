package com.parkingkok.app.ui.photo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.parkingkok.app.R
import com.parkingkok.app.domain.photo.PillarSuggestion
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.components.ParkingkokCard

/**
 * What the pillar photo read, offered for the blanks in the record
 * (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a).
 *
 * **It changes nothing until it is tapped.** That is the whole shape of the feature on
 * home and detail: §6a forbids a saved value, and a card showing what was read beside a
 * button that writes it is the plainest way for the user to see the guess before the
 * record does.
 *
 * It appears only when there is something to offer, and only for fields the record left
 * empty — [SuggestFromPillarPhotoUseCase] has already done that filtering, so this
 * renders what it is given.
 */
@Composable
fun PillarSuggestionCard(
    suggestion: PillarSuggestion,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ParkingkokCard(modifier = modifier, contentPadding = MaterialTheme.spacing.large) {
        Text(
            text = stringResource(R.string.pillar_suggestion_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.tiny))
        Text(
            text = suggestion.readLabel(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.medium))
        Row(
            modifier = Modifier.align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pillar_suggestion_dismiss))
            }
            // The screen's one emphasised action while the card is up: it is the only
            // thing on it that does anything, and ignoring it is free.
            Button(onClick = onApply, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.pillar_suggestion_apply))
            }
        }
    }
}

/** `B3 · A구역 142`, in the order the home hero ranks them, skipping what was not read. */
private fun PillarSuggestion.readLabel(): String {
    val within = listOfNotNull(zone, spot).joinToString(" ")
    return listOfNotNull(floorRaw, within.takeIf { it.isNotEmpty() }).joinToString(" · ")
}

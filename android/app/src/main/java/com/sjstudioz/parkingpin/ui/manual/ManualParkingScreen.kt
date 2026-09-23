package com.sjstudioz.parkingpin.ui.manual

import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sjstudioz.parkingpin.R
import com.sjstudioz.parkingpin.theme.ParkingpinTheme
import com.sjstudioz.parkingpin.theme.spacing
import com.sjstudioz.parkingpin.ui.components.DetailHeader
import com.sjstudioz.parkingpin.ui.components.ParkingpinCard
import com.sjstudioz.parkingpin.ui.components.ParkingpinScreen
import com.sjstudioz.parkingpin.ui.motion.pressScale

/**
 * The manual entry form — FR-001's `층/구역/spot/메모`.
 *
 * Every field is optional and there is no validation gate on the save button. The user is
 * standing in a car park with an armful of shopping; a form that refuses to submit is a
 * form that loses the record.
 */
@Composable
fun ManualParkingScreen(
    state: ManualParkingUiState,
    onFloorChange: (String) -> Unit,
    onZoneChange: (String) -> Unit,
    onSpotChange: (String) -> Unit,
    onMemoChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val floorFocus = remember { FocusRequester() }

    // docs/02 §6a: what the pillar said is "pre-filled into the fields the user was going
    // to fill anyway, focused and editable". The cursor goes to the floor because that is
    // the field a misread would matter most in, and the user is now checking a guess
    // rather than starting from nothing. Nothing happens when the read found nothing —
    // the form opens exactly as it does today.
    LaunchedEffect(state.pillarSuggestionOffered) {
        if (state.pillarSuggestionOffered) floorFocus.requestFocus()
    }

    ParkingpinScreen(
        modifier = modifier,
        header = { DetailHeader(title = stringResource(R.string.manual_title), onBack = onBack) },
    ) {
        item("intro") {
            Text(
                text = stringResource(R.string.manual_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item("form") {
            ParkingpinCard {
                Field(
                    value = state.floorRaw,
                    onValueChange = onFloorChange,
                    label = stringResource(R.string.manual_floor_label),
                    placeholder = stringResource(R.string.manual_floor_placeholder),
                    supporting = stringResource(R.string.manual_floor_help),
                    prominent = true,
                    focusRequester = floorFocus,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.medium))
                // Side by side: both hold two or three characters, and two full-width boxes
                // made the form longer than the keyboard leaves room for.
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
                    Field(
                        value = state.zone,
                        onValueChange = onZoneChange,
                        label = stringResource(R.string.manual_zone_label),
                        placeholder = stringResource(R.string.manual_zone_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    Field(
                        value = state.spot,
                        onValueChange = onSpotChange,
                        label = stringResource(R.string.manual_spot_label),
                        placeholder = stringResource(R.string.manual_spot_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.spacing.medium))
                Field(
                    value = state.memo,
                    onValueChange = onMemoChange,
                    label = stringResource(R.string.manual_memo_label),
                    placeholder = stringResource(R.string.manual_memo_placeholder),
                    imeAction = ImeAction.Done,
                )
            }
        }

        if (state.alreadyActive) {
            item("already-active") {
                ParkingpinCard {
                    Text(
                        text = stringResource(R.string.manual_already_active),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item("privacy") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.small))
                Text(
                    text = stringResource(R.string.manual_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("actions") {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                val saveInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = onSave,
                    enabled = !state.saving,
                    shape = MaterialTheme.shapes.small,
                    interactionSource = saveInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MaterialTheme.spacing.touchTarget + 8.dp)
                        .pressScale(saveInteraction),
                ) {
                    Text(
                        text = stringResource(R.string.manual_save),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.manual_cancel))
                }
            }
        }
    }
}

/**
 * One input, at the weight its value deserves.
 *
 * **The four fields used to be identical**, so the floor — the one value the whole app is
 * built around, and the largest thing on the home screen — was typed into a box the same
 * size as 메모. docs/10 §6's hierarchy rule applies to the screen that writes a value, not
 * only to the screen that shows it.
 *
 * The radius is 12dp and not `shapes.small`'s 16: §5 puts small controls at 10–14, and 16 on
 * a 56dp-tall box reads as a pill, which the harness rules out.
 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    prominent: Boolean = false,
    modifier: Modifier = Modifier,
    /** Set only on the field a pillar read puts the cursor in (docs/02 §6a). */
    focusRequester: FocusRequester? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        textStyle = if (prominent) {
            MaterialTheme.typography.headlineSmall
        } else {
            LocalTextStyle.current
        },
        shape = FIELD_SHAPE,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = imeAction),
        modifier = modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    )
}

/** docs/10 §5: 10–14 for a small control. */
private val FIELD_SHAPE = RoundedCornerShape(12.dp)

@Preview(name = "Manual entry", showBackground = true)
@Composable
private fun ManualParkingPreview() {
    ParkingpinTheme {
        ManualParkingScreen(
            state = ManualParkingUiState(floorRaw = "B3", zone = "A구역"),
            onFloorChange = {},
            onZoneChange = {},
            onSpotChange = {},
            onMemoChange = {},
            onSave = {},
            onBack = {},
        )
    }
}

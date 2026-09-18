package com.parkingkok.app.ui.manual

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.components.DetailHeader
import com.parkingkok.app.ui.components.ParkingkokCard
import com.parkingkok.app.ui.components.ParkingkokScreen

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
    ParkingkokScreen(
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
            ParkingkokCard {
                Field(
                    value = state.floorRaw,
                    onValueChange = onFloorChange,
                    label = stringResource(R.string.manual_floor_label),
                    placeholder = stringResource(R.string.manual_floor_placeholder),
                    supporting = stringResource(R.string.manual_floor_help),
                )
                Spacer(Modifier.height(MaterialTheme.spacing.medium))
                Field(
                    value = state.zone,
                    onValueChange = onZoneChange,
                    label = stringResource(R.string.manual_zone_label),
                    placeholder = stringResource(R.string.manual_zone_placeholder),
                )
                Spacer(Modifier.height(MaterialTheme.spacing.medium))
                Field(
                    value = state.spot,
                    onValueChange = onSpotChange,
                    label = stringResource(R.string.manual_spot_label),
                    placeholder = stringResource(R.string.manual_spot_placeholder),
                )
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
                ParkingkokCard {
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
                Button(
                    onClick = onSave,
                    enabled = !state.saving,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MaterialTheme.spacing.touchTarget + 8.dp),
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

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(name = "Manual entry", showBackground = true)
@Composable
private fun ManualParkingPreview() {
    ParkingkokTheme {
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

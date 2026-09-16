package com.parkingkok.app.ui.diagnostics

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.detection.RegistrationStatus
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.theme.ParkingkokTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P0 instrumentation screen, not product UI
 * (CLAUDE.md Development Order: UI is not completed first).
 *
 * Shows permission state, registration state, the persisted checkpoint, and the received
 * transition log so real-device behaviour — especially Samsung's delivery delays, see
 * docs/04_ANDROID_IMPLEMENTATION.md §20 — is observable without a debugger.
 *
 * It deliberately shows diagnostic state instead of nagging the user to disable battery
 * optimisation, which §20 rules out.
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onDetectionEnabledChange: (Boolean) -> Unit,
    onPermissionResult: () -> Unit,
    onClearEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        // Denial is not an app failure: manual parking stays available either way.
        onResult = { onPermissionResult() },
    )

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            DiagnosticsCard(title = stringResource(R.string.diagnostics_permission_title)) {
                LabelledValue(
                    stringResource(R.string.diagnostics_permission_activity_recognition),
                    stringResource(
                        if (state.permissionGranted) R.string.diagnostics_granted else R.string.diagnostics_denied,
                    ),
                )
                if (!state.permissionGranted) {
                    Text(
                        text = stringResource(R.string.diagnostics_permission_optional_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                    ) {
                        Text(stringResource(R.string.diagnostics_request_permission))
                    }
                }
            }

            DiagnosticsCard(title = stringResource(R.string.diagnostics_registration_title)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.diagnostics_smart_detection))
                    Switch(checked = state.detectionEnabled, onCheckedChange = onDetectionEnabledChange)
                }
                LabelledValue(
                    stringResource(R.string.diagnostics_registration_state),
                    state.registrationStatus.describe(),
                )
            }

            DiagnosticsCard(title = stringResource(R.string.diagnostics_checkpoint_title)) {
                val checkpoint = state.checkpoint
                if (checkpoint == null) {
                    Text(stringResource(R.string.diagnostics_checkpoint_absent))
                } else {
                    LabelledValue(
                        stringResource(R.string.diagnostics_checkpoint_state),
                        checkpoint.state.name,
                    )
                    LabelledValue(
                        stringResource(R.string.diagnostics_checkpoint_revision),
                        checkpoint.revision.toString(),
                    )
                    LabelledValue(
                        stringResource(R.string.diagnostics_checkpoint_last_automotive),
                        checkpoint.lastAutomotiveAtMillis.formatTime(),
                    )
                    // Coordinates are never rendered — presence only.
                    LabelledValue(
                        stringResource(R.string.diagnostics_checkpoint_reliable_location),
                        stringResource(
                            if (checkpoint.lastReliableLocation == null) {
                                R.string.diagnostics_absent
                            } else {
                                R.string.diagnostics_present
                            },
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_events_title, state.events.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = onClearEvents) {
                    Text(stringResource(R.string.diagnostics_clear))
                }
            }
            EventLog(events = state.events, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EventLog(events: List<MotionDomainEvent>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) {
        Text(stringResource(R.string.diagnostics_events_empty))
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(events) { event ->
            Column {
                Text(text = event.kind.wire, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(
                        R.string.diagnostics_event_detail,
                        event.atMillis.formatTime(),
                        event.receivedAtMillis - event.atMillis,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RegistrationStatus.describe(): String = when (this) {
    RegistrationStatus.Unknown -> stringResource(R.string.diagnostics_registration_unknown)
    RegistrationStatus.Disabled -> stringResource(R.string.diagnostics_registration_disabled)
    RegistrationStatus.MissingPermission ->
        stringResource(R.string.diagnostics_registration_missing_permission)
    is RegistrationStatus.Active ->
        stringResource(R.string.diagnostics_registration_active, specVersion, registeredAtMillis.formatTime())
    is RegistrationStatus.Failed -> stringResource(R.string.diagnostics_registration_failed, reason)
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

private fun Long?.formatTime(): String = if (this == null) "—" else timeFormat.format(Date(this))

@Preview(showBackground = true)
@Composable
private fun DiagnosticsScreenPreview() {
    ParkingkokTheme {
        DiagnosticsScreen(
            state = DiagnosticsUiState(
                permissionGranted = true,
                detectionEnabled = true,
                registrationStatus = RegistrationStatus.Active(1, 0L),
                checkpoint = DetectionCheckpoint.initial(0L),
            ),
            onDetectionEnabledChange = {},
            onPermissionResult = {},
            onClearEvents = {},
        )
    }
}

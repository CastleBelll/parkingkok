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
import com.parkingkok.app.domain.location.LocationSessionMode
import com.parkingkok.app.domain.location.LocationSessionState
import com.parkingkok.app.theme.ParkingkokTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P0 instrumentation screen, not product UI
 * (CLAUDE.md Development Order: UI is not completed first).
 *
 * Shows permission state, registration state, the bounded location session, the persisted
 * checkpoint, and the received transition log so real-device behaviour — especially
 * Samsung's delivery delays, see docs/04_ANDROID_IMPLEMENTATION.md §20 — is observable
 * without a debugger.
 *
 * It deliberately shows diagnostic state instead of nagging the user to disable battery
 * optimisation, which §20 rules out. No coordinate is rendered anywhere on it.
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onDetectionEnabledChange: (Boolean) -> Unit,
    onPermissionResult: () -> Unit,
    onCaptureModeChange: (LocationSessionMode) -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item { PermissionsCard(state.permissions, onPermissionResult) }
            item { RegistrationCard(state, onDetectionEnabledChange) }
            item { SessionCard(state.sessionState, onCaptureModeChange) }
            item { CheckpointCard(state.checkpoint) }
            item { ExportCard(state, onExportDiagnostics) }
            item { EventLogHeader(state.events.size, onClearEvents) }
            if (state.events.isEmpty()) {
                item { Text(stringResource(R.string.diagnostics_events_empty)) }
            } else {
                items(state.events) { event -> EventRow(event) }
            }
        }
    }
}

/**
 * The staged ladder from docs/04_ANDROID_IMPLEMENTATION.md §19: motion, then foreground
 * location, then background location — each only once the previous one is granted, and
 * never all at onboarding start. Denial is not an app failure at any rung.
 */
@Composable
private fun PermissionsCard(permissions: DiagnosticsPermissions, onPermissionResult: () -> Unit) {
    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { onPermissionResult() },
    )
    val multipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { onPermissionResult() },
    )

    DiagnosticsCard(title = stringResource(R.string.diagnostics_permission_title)) {
        LabelledValue(
            stringResource(R.string.diagnostics_permission_activity_recognition),
            grantedLabel(permissions.activityRecognitionGranted),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_permission_location_foreground),
            grantedLabel(permissions.foregroundLocationGranted),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_permission_location_background),
            grantedLabel(permissions.backgroundLocationGranted),
        )
        Text(
            text = stringResource(R.string.diagnostics_permission_optional_note),
            style = MaterialTheme.typography.bodySmall,
        )

        if (!permissions.activityRecognitionGranted) {
            OutlinedButton(onClick = { singleLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) {
                Text(stringResource(R.string.diagnostics_request_permission))
            }
        }
        if (!permissions.foregroundLocationGranted) {
            OutlinedButton(
                onClick = {
                    multipleLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.diagnostics_request_location_foreground))
            }
        } else if (!permissions.backgroundLocationGranted) {
            // Android only offers "Allow all the time" as a separate prompt, and only
            // after foreground location is already granted (§3).
            OutlinedButton(
                onClick = { singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            ) {
                Text(stringResource(R.string.diagnostics_request_location_background))
            }
            Text(
                text = stringResource(R.string.diagnostics_permission_background_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RegistrationCard(state: DiagnosticsUiState, onDetectionEnabledChange: (Boolean) -> Unit) {
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
}

/**
 * The bounded Fused Location session. The two numbers to watch on a real drive are the
 * deadline — proof the session is bounded — and the cached-fix drop count, which is how a
 * mis-set freshness threshold shows itself (docs/05_PARKING_DETECTION_ENGINE.md §5).
 */
@Composable
private fun SessionCard(session: LocationSessionState, onCaptureModeChange: (LocationSessionMode) -> Unit) {
    DiagnosticsCard(title = stringResource(R.string.diagnostics_session_title)) {
        LabelledValue(stringResource(R.string.diagnostics_session_mode), session.mode.name)
        LabelledValue(
            stringResource(R.string.diagnostics_session_deadline),
            session.record?.hardDeadlineAtMillis.formatTime(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_expires),
            session.record?.registrationExpiresAtMillis.formatTime(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_deliveries),
            "${session.counters.deliveryCount} / ${session.counters.sampleCount}",
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_admitted),
            session.counters.admittedCount.toString(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_cached_drops),
            "${session.counters.cachedFixDropCount} (${session.counters.lastCachedFixAgeMillis ?: "—"} ms)",
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_other_drops),
            "${session.counters.staleDropCount}/${session.counters.poorAccuracyDropCount}/" +
                "${session.counters.notNewerDropCount}",
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_driving_confirmed),
            if (session.drivingConfirmed) {
                session.drivingReasonCodes.joinToString(", ").ifEmpty { "true" }
            } else {
                stringResource(R.string.diagnostics_absent)
            },
        )
        session.lastStopReason?.let {
            LabelledValue(stringResource(R.string.diagnostics_session_stop_reason), it.name)
        }
        session.lastFailure?.let {
            LabelledValue(stringResource(R.string.diagnostics_session_failure), it)
        }

        // Manual capture, so the no-foreground-service path can be exercised on a desk.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCaptureModeChange(LocationSessionMode.DRIVING) }) {
                Text(stringResource(R.string.diagnostics_session_start))
            }
            OutlinedButton(onClick = { onCaptureModeChange(LocationSessionMode.IDLE) }) {
                Text(stringResource(R.string.diagnostics_session_stop))
            }
        }
    }
}

@Composable
private fun CheckpointCard(checkpoint: DetectionCheckpoint?) {
    DiagnosticsCard(title = stringResource(R.string.diagnostics_checkpoint_title)) {
        if (checkpoint == null) {
            Text(stringResource(R.string.diagnostics_checkpoint_absent))
            return@DiagnosticsCard
        }
        LabelledValue(stringResource(R.string.diagnostics_checkpoint_state), checkpoint.state.name)
        LabelledValue(
            stringResource(R.string.diagnostics_checkpoint_revision),
            checkpoint.revision.toString(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_checkpoint_last_automotive),
            checkpoint.lastAutomotiveAtMillis.formatTime(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_checkpoint_distance),
            "%.0f m".format(checkpoint.travelDistanceEstimateMeters),
        )
        // Coordinates are never rendered — presence, freshness, and accuracy only.
        LabelledValue(
            stringResource(R.string.diagnostics_checkpoint_reliable_location),
            checkpoint.lastReliableLocation?.let {
                "${it.capturedAtMillis.formatTime()} · ${it.horizontalAccuracyM} m"
            } ?: stringResource(R.string.diagnostics_absent),
        )
    }
}

@Composable
private fun ExportCard(state: DiagnosticsUiState, onExportDiagnostics: () -> Unit) {
    DiagnosticsCard(title = stringResource(R.string.diagnostics_export_title)) {
        Text(
            text = stringResource(R.string.diagnostics_export_path),
            style = MaterialTheme.typography.bodySmall,
        )
        val status = when {
            state.lastExportFailure != null ->
                stringResource(R.string.diagnostics_export_failed, state.lastExportFailure)
            state.lastExportSucceeded -> stringResource(R.string.diagnostics_export_ok)
            else -> stringResource(R.string.diagnostics_registration_unknown)
        }
        LabelledValue(stringResource(R.string.diagnostics_registration_state), status)
        OutlinedButton(onClick = onExportDiagnostics) {
            Text(stringResource(R.string.diagnostics_export_now))
        }
    }
}

@Composable
private fun EventLogHeader(count: Int, onClearEvents: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.diagnostics_events_title, count),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = onClearEvents) {
            Text(stringResource(R.string.diagnostics_clear))
        }
    }
}

@Composable
private fun EventRow(event: MotionDomainEvent) {
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
private fun grantedLabel(granted: Boolean): String =
    stringResource(if (granted) R.string.diagnostics_granted else R.string.diagnostics_denied)

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
                permissions = DiagnosticsPermissions(
                    activityRecognitionGranted = true,
                    foregroundLocationGranted = true,
                ),
                detectionEnabled = true,
                registrationStatus = RegistrationStatus.Active(1, 0L),
                checkpoint = DetectionCheckpoint.initial(0L),
            ),
            onDetectionEnabledChange = {},
            onPermissionResult = {},
            onCaptureModeChange = {},
            onExportDiagnostics = {},
            onClearEvents = {},
        )
    }
}

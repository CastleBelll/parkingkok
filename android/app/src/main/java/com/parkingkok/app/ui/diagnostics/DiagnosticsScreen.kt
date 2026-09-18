package com.parkingkok.app.ui.diagnostics

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.parkingkok.app.domain.trace.TraceLabel
import com.parkingkok.app.domain.trace.TraceMode
import com.parkingkok.app.domain.trace.TraceSession
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
    onTraceLabelChange: (String, TraceLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which recorded session has its label controls open. Purely presentational, so it
    // lives here rather than in the ViewModel.
    var expandedTraceId by remember { mutableStateOf<String?>(null) }

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
            item { TraceHeader(state.traceSessions.size) }
            if (state.traceSessions.isEmpty()) {
                item { Text(stringResource(R.string.diagnostics_trace_empty)) }
            } else {
                items(state.traceSessions, key = { it.sessionId }) { session ->
                    TraceSessionCard(
                        session = session,
                        expanded = expandedTraceId == session.sessionId,
                        onToggle = {
                            expandedTraceId =
                                if (expandedTraceId == session.sessionId) null else session.sessionId
                        },
                        onLabelChange = { onTraceLabelChange(session.sessionId, it) },
                    )
                }
            }
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
        // §7's movement clause, in the three numbers a field run is read from. "Never
        // confirmed" and "no fix ever carried a speed" look the same from outside; these
        // tell them apart without opening the export.
        val movement = session.evidence?.movement
        LabelledValue(
            stringResource(R.string.diagnostics_session_moving_samples),
            "${movement?.movingSampleCount ?: 0} / ${movement?.derivedMovingSampleCount ?: 0}",
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_speed_available),
            "${movement?.speedAvailableCount ?: 0} / ${movement?.speedMissingCount ?: 0}",
        )
        LabelledValue(
            stringResource(R.string.diagnostics_session_movement_reject),
            movement?.rejectReason?.wire ?: stringResource(R.string.diagnostics_absent),
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
private fun TraceHeader(count: Int) {
    Column {
        Text(
            text = stringResource(R.string.diagnostics_trace_title, count),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.diagnostics_trace_path),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One recorded session, with the label docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 leaves
 * to a person.
 *
 * Deliberately the smallest thing that makes a trace convertible: without a mode, the
 * converter cannot tell a bus ride from a drive, and the recording is evidence of nothing.
 * It is instrumentation, not product UI (CLAUDE.md Development Order).
 *
 * No coordinate is rendered here, because the session holds none to render.
 */
@Composable
private fun TraceSessionCard(
    session: TraceSession,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLabelChange: (TraceLabel) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.clickable(onClick = onToggle).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelledValue(
                label = "${session.startedAt.formatTime()} → ${session.endedAt.formatTime()}",
                value = stringResource(modeLabel(session.label.mode)),
            )
            Text(
                text = stringResource(
                    R.string.diagnostics_trace_detail,
                    session.events.size,
                    session.sessionId.take(SESSION_ID_PREFIX_LENGTH),
                    parkedSymbol(session.label.parked),
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            if (!expanded) return@Column

            ChipRow {
                TraceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = session.label.mode == mode,
                        onClick = { onLabelChange(session.label.copy(mode = mode)) },
                        label = { Text(stringResource(modeLabel(mode))) },
                    )
                }
            }
            ChipRow {
                ParkedChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = session.label.parked == choice.value,
                        onClick = { onLabelChange(session.label.copy(parked = choice.value)) },
                        label = { Text(stringResource(choice.label)) },
                    )
                }
            }
            NoteEditor(session, onLabelChange)
        }
    }
}

/**
 * The note is committed on an explicit press rather than on every keystroke: each commit
 * rewrites the session file, and doing that per character would turn labelling into a
 * write storm.
 */
@Composable
private fun NoteEditor(session: TraceSession, onLabelChange: (TraceLabel) -> Unit) {
    var note by remember(session.sessionId) { mutableStateOf(session.label.note.orEmpty()) }
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.diagnostics_trace_note)) },
        singleLine = true,
    )
    OutlinedButton(onClick = { onLabelChange(session.label.copy(note = note.ifBlank { null })) }) {
        Text(stringResource(R.string.diagnostics_trace_note_save))
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

/** `parked` is three-valued: §9 keeps "not yet known" distinct from "did not park". */
private enum class ParkedChoice(val value: Boolean?, val label: Int) {
    YES(true, R.string.diagnostics_trace_parked_yes),
    NO(false, R.string.diagnostics_trace_parked_no),
    UNKNOWN(null, R.string.diagnostics_trace_parked_unknown),
}

private fun parkedSymbol(parked: Boolean?): String = when (parked) {
    true -> "P"
    false -> "—"
    null -> "?"
}

private fun modeLabel(mode: TraceMode): Int = when (mode) {
    TraceMode.CAR -> R.string.diagnostics_trace_mode_car
    TraceMode.BUS -> R.string.diagnostics_trace_mode_bus
    TraceMode.SUBWAY -> R.string.diagnostics_trace_mode_subway
    TraceMode.TAXI -> R.string.diagnostics_trace_mode_taxi
    TraceMode.WALK -> R.string.diagnostics_trace_mode_walk
    TraceMode.STILL -> R.string.diagnostics_trace_mode_still
    TraceMode.UNKNOWN -> R.string.diagnostics_trace_mode_unknown
}

private const val SESSION_ID_PREFIX_LENGTH = 8

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
            onTraceLabelChange = { _, _ -> },
        )
    }
}

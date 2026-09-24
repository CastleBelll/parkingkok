package com.sjstudioz.parkingpin.ui.diagnostics

import android.Manifest
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.sjstudioz.parkingpin.R
import com.sjstudioz.parkingpin.detection.RegistrationStatus
import com.sjstudioz.parkingpin.domain.detection.DetectionCheckpoint
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.location.LocationSessionMode
import com.sjstudioz.parkingpin.domain.location.LocationSessionState
import com.sjstudioz.parkingpin.domain.trace.TraceEvent
import com.sjstudioz.parkingpin.domain.trace.TraceGapStats
import com.sjstudioz.parkingpin.domain.trace.TraceLabel
import com.sjstudioz.parkingpin.domain.trace.TraceMode
import com.sjstudioz.parkingpin.domain.trace.TraceSession
import com.sjstudioz.parkingpin.domain.trace.TraceSessionBoundaryPolicy
import com.sjstudioz.parkingpin.domain.trace.TraceSplitResult
import com.sjstudioz.parkingpin.domain.trace.TraceSummary
import com.sjstudioz.parkingpin.theme.ParkingpinTheme
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
    onTraceSplit: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which recorded session has its label controls open, and which has its event list
    // open for cutting. Purely presentational, so both live here rather than in the
    // ViewModel.
    var expandedTraceId by remember { mutableStateOf<String?>(null) }
    var splittingTraceId by remember { mutableStateOf<String?>(null) }

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
            item { TraceGapCard(state.traceSummary) }
            if (state.traceSessions.isEmpty()) {
                item { Text(stringResource(R.string.diagnostics_trace_empty)) }
            } else {
                items(state.traceSessions, key = { it.sessionId }) { session ->
                    TraceSessionCard(
                        session = session,
                        expanded = expandedTraceId == session.sessionId,
                        isOpen = state.openTraceSessionId == session.sessionId,
                        splitting = splittingTraceId == session.sessionId,
                        splitRefusal = state.traceSplitRefusal.takeIf {
                            splittingTraceId == session.sessionId
                        },
                        onToggle = {
                            expandedTraceId =
                                if (expandedTraceId == session.sessionId) null else session.sessionId
                        },
                        onToggleSplit = {
                            splittingTraceId =
                                if (splittingTraceId == session.sessionId) null else session.sessionId
                        },
                        onLabelChange = { onTraceLabelChange(session.sessionId, it) },
                        onSplitAt = { index ->
                            // The session this card shows is about to be replaced by two
                            // others, so the list it belonged to is the thing to land back
                            // on. A refused split leaves the list open, with the reason.
                            onTraceSplit(session.sessionId, index)
                        },
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
        // The label prompt is the only notification this build posts (docs/05 §9). Without
        // it every closed session is counted as suppressed instead of being asked about.
        LabelledValue(
            stringResource(R.string.diagnostics_permission_notifications),
            grantedLabel(permissions.notificationsGranted),
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
        // Below Android 13 there is no runtime permission to ask for: notifications are on
        // unless the user switched them off in Settings, which no prompt can undo.
        if (!permissions.notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(onClick = { singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.diagnostics_request_permission_notifications))
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
        // §7's distance clause and what its noise floor kept out of it. The pair matters:
        // a distance that never grows is only readable next to the count of legs refused.
        LabelledValue(
            stringResource(R.string.diagnostics_session_travel_distance),
            "%.0f m / %d".format(
                Locale.US,
                session.evidence?.travelDistanceMeters ?: 0.0,
                session.evidence?.distanceNoiseFloorRejectCount ?: 0,
            ),
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
 * The §9 gap aggregate, kept in its own card because it answers a different question from
 * the counters above it: not "is recording working" but "what should the 30-minute idle
 * gap actually be?".
 *
 * **Nothing acts on it.** The threshold came from a single day of observation and does not
 * move until these numbers have accumulated (§9 "이 값들이 모이기 전에는 30분을 바꾸지
 * 않는다"). The measured count is shown first so the rest reads as a sample size.
 */
@Composable
private fun TraceGapCard(summary: TraceSummary) {
    DiagnosticsCard(title = stringResource(R.string.diagnostics_trace_gap_title)) {
        LabelledValue(
            stringResource(R.string.diagnostics_trace_gap_measured),
            stringResource(
                R.string.diagnostics_trace_gap_measured_value,
                summary.measuredSessionCount,
                summary.sessionCount,
            ),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_trace_gap_max),
            minutesLabel(summary.maxGapMillis),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_trace_gap_over10),
            summary.sessionsOver10MinGapCount.toString(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_trace_gap_over20),
            summary.sessionsOver20MinGapCount.toString(),
        )
        // Both drop counters, never one total: the rolling cap climbing means the device
        // is recording more than it can hold, while the non-viable count climbing means
        // the boundary is manufacturing single-event sessions (§9 "조용히 버리지 마라").
        LabelledValue(
            stringResource(R.string.diagnostics_trace_drop_cap),
            summary.discardedSessionCount.toString(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_trace_drop_non_viable),
            summary.nonViableDropCount.toString(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_trace_label_prompt_suppressed),
            summary.labelPromptSuppressedCount.toString(),
        )
        LabelledValue(
            stringResource(R.string.diagnostics_trace_unlabelled),
            summary.unlabelledSessionCount.toString(),
        )
        Text(
            text = stringResource(
                R.string.diagnostics_trace_gap_note,
                TraceSessionBoundaryPolicy.IDLE_GAP_MILLIS / MILLIS_PER_MINUTE,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One recorded session, with the label docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 leaves
 * to a person, and the cut §9 also leaves to one.
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
    isOpen: Boolean,
    splitting: Boolean,
    splitRefusal: TraceSplitResult.Refusal?,
    onToggle: () -> Unit,
    onToggleSplit: () -> Unit,
    onLabelChange: (TraceLabel) -> Unit,
    onSplitAt: (Int) -> Unit,
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
            // The gap is on the row because it is the reason to open the split list at
            // all: a session holding a 28-minute silence is one the boundary nearly cut
            // and a person probably should.
            session.gapStats?.takeIf { it.maxGapMillis > 0L }?.let { stats ->
                Text(
                    text = stringResource(
                        R.string.diagnostics_trace_session_gap,
                        minutesLabel(stats.maxGapMillis),
                        stats.gapsOver10MinCount,
                        stats.gapsOver20MinCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TraceSessionMarkers(session, isOpen)

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

            // §9: an open session cannot be cut — more events may still join it, and
            // replacing the file would pull it out from under the recorder mid-append.
            if (isOpen) return@Column
            OutlinedButton(onClick = onToggleSplit) {
                Text(
                    stringResource(
                        if (splitting) R.string.diagnostics_trace_split_close else R.string.diagnostics_trace_split,
                    ),
                )
            }
            if (splitting) {
                TraceSplitList(session, splitRefusal, onSplitAt)
            }
        }
    }
}

/** What a person has to know about a session before labelling it, beyond its numbers. */
@Composable
private fun TraceSessionMarkers(session: TraceSession, isOpen: Boolean) {
    val markers = buildList {
        // A fragment is already the result of one human judgement, so the card says so
        // rather than presenting it as something the device recorded whole.
        if (session.splitFrom != null) add(stringResource(R.string.diagnostics_trace_fragment))
        if (isOpen) add(stringResource(R.string.diagnostics_trace_open))
    }
    if (markers.isEmpty()) return
    Text(text = markers.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

/**
 * Picks the cut point for §9's "사람이 세션을 나눈다".
 *
 * **The event list, not a time picker.** What the person is looking for is the boundary
 * between sitting still and travelling, and it is invisible in a clock: in the September
 * 2026 subway trace the office wait and the ride were separated by nothing but the meaning
 * of the events on either side — a `stationary_enter`, then 28.4 minutes of silence, then
 * a `walking_enter`, with the `vehicle_enter` that starts the actual ride another 18
 * minutes later. So every event is listed with its type, and the silence before it is
 * spelled out above it, because the long silences are what the eye is scanning for.
 *
 * The first event carries no action — a cut has to leave events on both sides — but it is
 * still shown, because hiding it would make the list disagree with the trace it shows.
 */
@Composable
private fun TraceSplitList(
    session: TraceSession,
    refusal: TraceSplitResult.Refusal?,
    onSplitAt: (Int) -> Unit,
) {
    var pendingIndex by remember(session.sessionId) { mutableStateOf<Int?>(null) }

    Text(
        text = stringResource(R.string.diagnostics_trace_split_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    refusal?.let {
        Text(
            text = it.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    session.events.forEachIndexed { index, event ->
        TraceSplitRow(
            event = event,
            previous = session.events.getOrNull(index - 1),
            onClick = if (index == 0) null else ({ pendingIndex = index }),
        )
    }

    // The parent is replaced and its label is not carried over, so the tap is worth one
    // confirmation even on a diagnostics screen.
    pendingIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { pendingIndex = null },
            title = { Text(stringResource(R.string.diagnostics_trace_split_confirm_title)) },
            text = { Text(stringResource(R.string.diagnostics_trace_split_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingIndex = null
                        onSplitAt(index)
                    },
                ) {
                    Text(stringResource(R.string.diagnostics_trace_split_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingIndex = null }) {
                    Text(stringResource(R.string.diagnostics_trace_split_cancel))
                }
            },
        )
    }
}

@Composable
private fun TraceSplitRow(event: TraceEvent, previous: TraceEvent?, onClick: (() -> Unit)?) {
    Column(
        modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        previous?.let {
            Text(
                text = stringResource(
                    R.string.diagnostics_trace_split_event_gap,
                    minutesLabel(event.atMillis - it.atMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(
                R.string.diagnostics_trace_split_event,
                event.atMillis.formatTime(),
                event.type.wire,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        // Whatever the event carries beyond its type, which is what tells a walk from a
        // ride when the type alone is ambiguous. Never a coordinate — there is none.
        event.detail()?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

private fun TraceEvent.detail(): String? = buildList {
    confidence?.let { add("확신도 ${it.name.lowercase(Locale.US)}") }
    accuracy?.let { add("정확도 %.0fm".format(Locale.US, it)) }
    speed?.let { add("%.1fm/s".format(Locale.US, it)) }
    distanceFromPreviousM?.let { add("이동 %.0fm".format(Locale.US, it)) }
    if (fromBucket != null && toBucket != null) {
        add("${fromBucket.name.lowercase(Locale.US)} → ${toBucket.name.lowercase(Locale.US)}")
    }
}.ifEmpty { null }?.joinToString(" · ")

/** §9's refusals, spelled out. A refused cut is a normal outcome, so it explains itself. */
@Composable
private fun TraceSplitResult.Refusal.describe(): String = when (this) {
    TraceSplitResult.SessionNotFound -> stringResource(R.string.diagnostics_trace_split_refused_not_found)
    TraceSplitResult.SessionIsOpen -> stringResource(R.string.diagnostics_trace_split_refused_open)
    TraceSplitResult.IndexOutOfRange -> stringResource(R.string.diagnostics_trace_split_refused_range)
    is TraceSplitResult.FragmentNotViable -> stringResource(
        R.string.diagnostics_trace_split_refused_not_viable,
        leadingEventCount,
        trailingEventCount,
        TraceSessionBoundaryPolicy.MINIMUM_VIABLE_EVENT_COUNT,
    )
    is TraceSplitResult.StoreFailure -> stringResource(R.string.diagnostics_trace_split_refused_store, reason)
}

/**
 * Minutes to one decimal, agreed in one place: the numbers a person compares against the
 * 30-minute threshold have to be the same on the aggregate card and in the split list.
 */
@Composable
private fun minutesLabel(millis: Long): String = stringResource(
    R.string.diagnostics_trace_minutes,
    "%.1f".format(Locale.US, millis.toDouble() / MILLIS_PER_MINUTE),
)

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

private const val MILLIS_PER_MINUTE = 60_000L
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
    ParkingpinTheme {
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
            onTraceSplit = { _, _ -> },
        )
    }
}

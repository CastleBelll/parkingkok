package com.parkingkok.app.diagnostics

import com.parkingkok.app.detection.RegistrationStatus
import com.parkingkok.app.domain.detection.DetectionCheckpoint
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.location.LocationDiagnosticsCounters
import com.parkingkok.app.domain.location.LocationQualityEntry
import com.parkingkok.app.domain.location.LocationSessionState
import com.parkingkok.app.domain.trace.TraceSummary
import kotlinx.serialization.Serializable

/**
 * The P0 diagnostics, flattened for export.
 *
 * Why it exists: the counters and session state that prove the detection path worked live
 * in DataStore, and reading them back meant scraping the preferences file with
 * `adb shell run-as ... | strings` — which shows whatever happens to be printable and
 * silently omits the rest. Written as a JSON file instead, the same evidence comes off the
 * device intact, still with no root. Same purpose as the iOS `DiagnosticsReport`.
 *
 * **A hand-written projection, never an encoding of the live types.** [DetectionCheckpoint]
 * is `@Serializable` and carries `lastReliableLocation`, which holds real coordinates from
 * M0B-2 onward. This file is copied off the device by design, so serializing the checkpoint
 * wholesale would walk parking coordinates straight past docs/00_CORE_RULES.md Privacy.
 * Every field below is listed by hand, the coordinate-bearing ones are deliberately absent,
 * and `DiagnosticsReportTest` enforces it against the encoded bytes rather than against the
 * field list — a redacted `toString` would not have caught it, because the encoder does not
 * call `toString`.
 */
@Serializable
data class DiagnosticsReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val generatedAtMillis: Long,

    // Permissions and wiring
    val activityRecognitionGranted: Boolean,
    val foregroundLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val smartDetectionEnabled: Boolean,
    val transitionRegistration: String,

    // Checkpoint, projected
    val state: String?,
    val revision: Long?,
    val stateEnteredAtMillis: Long?,
    val lastAutomotiveAtMillis: Long?,
    val lastLocationAtMillis: Long?,
    val travelDistanceEstimateMeters: Double?,
    val hasCandidate: Boolean,
    /** Presence and quality of the reliable fix — never where it was. */
    val hasReliableLocation: Boolean,
    val reliableLocationCapturedAtMillis: Long?,
    val reliableLocationAccuracyM: Float?,

    // Bounded location session
    val sessionMode: String,
    val sessionStartedAtMillis: Long?,
    val sessionHardDeadlineAtMillis: Long?,
    val sessionRegistrationExpiresAtMillis: Long?,
    val sessionDeliveredUpdateCount: Int?,
    val sessionProfileVersion: Int?,
    val lastSessionStopReason: String?,
    val lastSessionFailure: String?,

    // §7 driving guard
    val drivingConfirmed: Boolean,
    val drivingReasonCodes: List<String>,
    val vehicleFirstSeenAtMillis: Long?,
    val lastVehicleEvidenceAtMillis: Long?,
    val reliableSampleCount: Int?,
    val maxSpeedMps: Float?,

    /**
     * §7's movement clause, as counts rather than as a verdict.
     *
     * The pair that has to be readable off one file: "confirmation never fired" and "no fix
     * ever carried a speed" look identical from outside, and on the September 2026 field
     * device it was the second one. [speedMissingCount] high with
     * [derivedMovingSampleCount] at zero is the shape of that defect; the same two with
     * [movementEvidenceRejectReason] set says which gate declined and why.
     */
    val movingSampleCount: Int?,
    val speedAvailableCount: Int?,
    val speedMissingCount: Int?,
    val derivedMovingSampleCount: Int?,
    val movementEvidenceRejectReason: String?,
    /** Fixes discarded as implausible jumps before the movement clause saw them (§5). */
    val movementOutlierCount: Int?,

    // Location quality counters (docs/05 §5: exclusions are counted, not dropped quietly)
    val counters: LocationDiagnosticsCounters,
    /** Volatile ring buffer, oldest first. Coordinate-free by construction. */
    val recentFixes: List<FixQuality>,

    // Motion transitions
    val transitionEventCount: Int,
    val recentTransitions: List<TransitionSummary>,

    /**
     * What the trace recorder has on disk
     * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9).
     *
     * Here rather than only in the traces directory because this is the file a field run
     * is read from: "40 sessions, 12 discarded, 31 still unlabelled" is the difference
     * between a recording that is ready to convert and one that needs an evening of
     * labelling, and neither number is visible from a directory listing.
     */
    val trace: TraceSummary = TraceSummary(),
) {
    /** One ring-buffer entry, projected. */
    @Serializable
    data class FixQuality(
        val atMillis: Long,
        val accuracyM: Float,
        val speedMps: Float?,
        /** null when the fix was admitted. */
        val dropReason: String?,
    )

    /** One received transition, projected. */
    @Serializable
    data class TransitionSummary(
        val kind: String,
        val atMillis: Long,
        /** OEM delivery delay, the number docs/04_ANDROID_IMPLEMENTATION.md §20 asks for. */
        val deliveryDelayMillis: Long,
    )

    companion object {
        /** Bump whenever the shape changes, so an older payload is rejected, not half-read. */
        const val SCHEMA_VERSION: Int = 3

        @Suppress("LongParameterList")
        fun from(
            nowMillis: Long,
            checkpoint: DetectionCheckpoint?,
            sessionState: LocationSessionState,
            qualityHistory: List<LocationQualityEntry>,
            transitions: List<MotionDomainEvent>,
            activityRecognitionGranted: Boolean,
            foregroundLocationGranted: Boolean,
            backgroundLocationGranted: Boolean,
            smartDetectionEnabled: Boolean,
            transitionRegistration: RegistrationStatus,
            trace: TraceSummary,
        ): DiagnosticsReport {
            val record = sessionState.record
            val evidence = sessionState.evidence
            return DiagnosticsReport(
                generatedAtMillis = nowMillis,
                activityRecognitionGranted = activityRecognitionGranted,
                foregroundLocationGranted = foregroundLocationGranted,
                backgroundLocationGranted = backgroundLocationGranted,
                smartDetectionEnabled = smartDetectionEnabled,
                transitionRegistration = transitionRegistration.wire(),
                state = checkpoint?.state?.name,
                revision = checkpoint?.revision,
                stateEnteredAtMillis = checkpoint?.stateEnteredAtMillis,
                lastAutomotiveAtMillis = checkpoint?.lastAutomotiveAtMillis,
                lastLocationAtMillis = checkpoint?.lastLocationAtMillis,
                travelDistanceEstimateMeters = checkpoint?.travelDistanceEstimateMeters,
                hasCandidate = checkpoint?.candidateId != null,
                hasReliableLocation = checkpoint?.lastReliableLocation != null,
                reliableLocationCapturedAtMillis = checkpoint?.lastReliableLocation?.capturedAtMillis,
                reliableLocationAccuracyM = checkpoint?.lastReliableLocation?.horizontalAccuracyM,
                sessionMode = sessionState.mode.name,
                sessionStartedAtMillis = record?.startedAtMillis,
                sessionHardDeadlineAtMillis = record?.hardDeadlineAtMillis,
                sessionRegistrationExpiresAtMillis = record?.registrationExpiresAtMillis,
                sessionDeliveredUpdateCount = record?.deliveredUpdateCount,
                sessionProfileVersion = record?.profileVersion,
                lastSessionStopReason = sessionState.lastStopReason?.name,
                lastSessionFailure = sessionState.lastFailure,
                drivingConfirmed = sessionState.drivingConfirmed,
                drivingReasonCodes = sessionState.drivingReasonCodes,
                vehicleFirstSeenAtMillis = evidence?.vehicleFirstSeenAtMillis,
                lastVehicleEvidenceAtMillis = evidence?.lastVehicleEvidenceAtMillis,
                reliableSampleCount = evidence?.reliableSampleCount,
                maxSpeedMps = evidence?.maxSpeedMps,
                movingSampleCount = evidence?.movement?.movingSampleCount,
                speedAvailableCount = evidence?.movement?.speedAvailableCount,
                speedMissingCount = evidence?.movement?.speedMissingCount,
                derivedMovingSampleCount = evidence?.movement?.derivedMovingSampleCount,
                movementEvidenceRejectReason = evidence?.movement?.rejectReason?.wire,
                movementOutlierCount = evidence?.movement?.outlierCount,
                counters = sessionState.counters,
                recentFixes = qualityHistory.map { entry ->
                    FixQuality(
                        atMillis = entry.sample.atMillis,
                        accuracyM = entry.sample.horizontalAccuracyM,
                        speedMps = entry.sample.speedMps,
                        dropReason = entry.dropReason?.name,
                    )
                },
                transitionEventCount = transitions.size,
                recentTransitions = transitions.map { event ->
                    TransitionSummary(
                        kind = event.kind.wire,
                        atMillis = event.atMillis,
                        deliveryDelayMillis = event.receivedAtMillis - event.atMillis,
                    )
                },
                trace = trace,
            )
        }

        private fun RegistrationStatus.wire(): String = when (this) {
            RegistrationStatus.Unknown -> "unknown"
            RegistrationStatus.Disabled -> "disabled"
            RegistrationStatus.MissingPermission -> "missing_permission"
            is RegistrationStatus.Active -> "active(spec $specVersion)"
            is RegistrationStatus.Failed -> "failed($reason)"
        }
    }
}

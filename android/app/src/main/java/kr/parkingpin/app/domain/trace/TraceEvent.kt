package kr.parkingpin.app.domain.trace

import kr.parkingpin.app.domain.detection.MotionEventKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One recorded event inside a [TraceSession], exactly as
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 defines it.
 *
 * **No coordinate is representable here.** The schema's whole point is that a trace file
 * is copied off the device, so §9 restricts it to `type`/`atMillis`/`accuracy`/`speed`/
 * `distanceFromPreviousM`/`confidence`/buckets. There is no latitude or longitude field to
 * populate, by construction — the same structural choice
 * [kr.parkingpin.app.domain.location.LocationQualitySample] makes on the detection path.
 * `TraceSessionSerializationTest` enforces it against the encoded bytes.
 *
 * Flat rather than a sealed hierarchy: §9 specifies one JSON object shape with optional
 * fields, and a polymorphic encoding would have to reproduce that shape anyway. The
 * companion factories are the only intended way to build one, so a `location` event cannot
 * pick up a `fromBucket` by accident.
 */
@Serializable
data class TraceEvent(
    val type: TraceEventType,
    /**
     * When the event **happened**, not when this process heard about it.
     *
     * Activity Transition delivery on a Galaxy S21+ was measured at 8.772 s behind the
     * event (docs/04_ANDROID_IMPLEMENTATION.md §20). Recording the receipt time instead
     * would shift every motion event in the trace by the OEM's delivery lag, and the
     * fixture converted from it would describe a drive that never happened. The delivery
     * lag itself belongs in the diagnostics report, which already reports it.
     */
    val atMillis: Long,
    val confidence: TraceConfidence? = null,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val distanceFromPreviousM: Double? = null,
    val fromBucket: LocationQualityBucket? = null,
    val toBucket: LocationQualityBucket? = null,
) {
    companion object {

        fun motion(kind: MotionEventKind, atMillis: Long, confidence: TraceConfidence? = null): TraceEvent =
            TraceEvent(type = kind.traceEventType(), atMillis = atMillis, confidence = confidence)

        fun location(
            atMillis: Long,
            accuracyM: Float,
            speedMps: Float?,
            distanceFromPreviousM: Double?,
        ): TraceEvent = TraceEvent(
            type = TraceEventType.LOCATION,
            atMillis = atMillis,
            accuracy = accuracyM,
            speed = speedMps,
            distanceFromPreviousM = distanceFromPreviousM,
        )

        fun qualityDegraded(
            atMillis: Long,
            fromBucket: LocationQualityBucket,
            toBucket: LocationQualityBucket,
        ): TraceEvent = TraceEvent(
            type = TraceEventType.LOCATION_QUALITY_DEGRADED,
            atMillis = atMillis,
            fromBucket = fromBucket,
            toBucket = toBucket,
        )

        /**
         * The adapter boundary between Android's internal event identities and the
         * cross-platform wire vocabulary.
         *
         * [MotionEventKind.wire] stays `still_enter`/`still_exit` — it is this platform's
         * own naming and changing it would churn the checkpoint, the event log, and the
         * diagnostics report for nothing. The contract vocabulary is
         * `stationary_enter`/`stationary_exit`, and the mapping happens here so the two
         * representations can diverge without either one drifting.
         */
        private fun MotionEventKind.traceEventType(): TraceEventType = when (this) {
            MotionEventKind.ENTERED_VEHICLE -> TraceEventType.VEHICLE_ENTER
            MotionEventKind.EXITED_VEHICLE -> TraceEventType.VEHICLE_EXIT
            MotionEventKind.STARTED_WALKING -> TraceEventType.WALKING_ENTER
            MotionEventKind.BECAME_STATIONARY -> TraceEventType.STATIONARY_ENTER
            MotionEventKind.STOPPED_BEING_STATIONARY -> TraceEventType.STATIONARY_EXIT
        }
    }
}

/**
 * The trace event vocabulary, shared verbatim with iOS and with the fixture converter.
 *
 * The `@SerialName` values are the contract — they are what the converter reads and what
 * the §8 fixtures are written in. Renaming one silently makes the two platforms' traces
 * unusable against each other, which is why they are spelled out here rather than derived
 * from an enum name.
 *
 * Only the subset this platform can currently observe is listed. Car projection and user
 * confirmation events are part of the same vocabulary but belong to features that do not
 * exist yet (M3), and an event type nothing can emit is dead code.
 */
@Serializable
enum class TraceEventType {
    @SerialName("vehicle_enter")
    VEHICLE_ENTER,

    @SerialName("vehicle_exit")
    VEHICLE_EXIT,

    @SerialName("walking_enter")
    WALKING_ENTER,

    @SerialName("stationary_enter")
    STATIONARY_ENTER,

    @SerialName("stationary_exit")
    STATIONARY_EXIT,

    @SerialName("location")
    LOCATION,

    @SerialName("location_quality_degraded")
    LOCATION_QUALITY_DEGRADED,

    ;

    /**
     * The contract string for this type, read back from the `@SerialName` that *is* the
     * contract.
     *
     * The labelling screen shows it so a person cutting a session sees the same vocabulary
     * the fixture will be written in. Derived rather than repeated, because a second
     * hand-written table is a second thing to get out of step.
     */
    val wire: String get() = WIRE_NAMES[ordinal]
}

/** Computed once: [TraceEventType.wire] is read per event while the split list is open. */
private val WIRE_NAMES: List<String> = TraceEventType.entries.map { entry ->
    TraceEventType.serializer().descriptor.getElementName(entry.ordinal)
}

/**
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §5's confidence bucket.
 *
 * Always absent on Android today: `ActivityTransitionEvent` reports a transition without a
 * confidence, unlike iOS `CMMotionActivity`. The field exists so a trace recorded on
 * either platform decodes with the same reader, which is the entire reason §9 is one
 * schema and not two.
 */
@Serializable
enum class TraceConfidence {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
}

/**
 * Coarse accuracy bands for `location_quality_degraded`
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2 `fromBucket`/`toBucket`).
 *
 * **The thresholds are deliberately their own constants and must stay that way.**
 * [kr.parkingpin.app.domain.location.ReliableLocationSelector.MAX_ACCURACY_METERS] happens
 * to be 35 m today, but it is a remote-config-tunable detector threshold
 * (docs/03_SYSTEM_ARCHITECTURE.md §10 `detector.reliableAccuracyMeters`). Deriving the
 * bucket edge from it would mean that tuning the detector retroactively changes what an
 * already-recorded trace says — and a recording format has to stay comparable over time.
 * The coincidence is not a dependency.
 *
 * Both values are field-tuning starting points, in the same spirit as the evidence weights
 * in docs/05_PARKING_DETECTION_ENGINE.md §8.
 */
@Serializable
enum class LocationQualityBucket {
    @SerialName("good")
    GOOD,

    @SerialName("fair")
    FAIR,

    @SerialName("poor")
    POOR,

    ;

    /**
     * Declaration order is the quality order, and [isWorseThan] is the only place that
     * relies on it — a `location_quality_degraded` event is emitted on a drop, never on a
     * recovery, because §2 names the event for the direction it reports.
     */
    fun isWorseThan(other: LocationQualityBucket): Boolean = ordinal > other.ordinal

    companion object {
        const val GOOD_MAX_ACCURACY_METERS: Float = 20f
        const val FAIR_MAX_ACCURACY_METERS: Float = 35f

        /**
         * @return null when [accuracyM] is not a valid accuracy at all.
         *
         * A negative accuracy means Fused Location is telling us the fix is invalid
         * (docs/05_PARKING_DETECTION_ENGINE.md §5), and it is already filtered at the
         * adapter boundary. Giving it a bucket would let an adapter defect hide inside a
         * plausible-looking `poor`, so it gets no bucket and no degradation event; the raw
         * `location` event still carries the negative accuracy, where it is visible.
         */
        fun of(accuracyM: Float): LocationQualityBucket? = when {
            accuracyM < 0f -> null
            accuracyM <= GOOD_MAX_ACCURACY_METERS -> GOOD
            accuracyM <= FAIR_MAX_ACCURACY_METERS -> FAIR
            else -> POOR
        }
    }
}

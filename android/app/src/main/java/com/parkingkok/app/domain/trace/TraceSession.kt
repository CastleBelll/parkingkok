package com.parkingkok.app.domain.trace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One recorded trip, in the schema docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 fixed.
 *
 * **Why this exists.** Gate M0 needs field evidence, and repeating a real drive twenty
 * times is the bottleneck. A trace is recorded from ordinary movement instead — a bus, a
 * subway ride, a taxi, a walk are all valid sessions, and the negative cases
 * docs/05_PARKING_DETECTION_ENGINE.md §17 demands can be collected without a car at all.
 *
 * **Not a fixture.** §9 keeps the two formats apart on purpose: a trace is the device's raw
 * recording with absolute timestamps and a human label, and a fixture is the §8 parity
 * contract with relative times and an `expected` block. Traces convert into fixtures and
 * never the other way, so recording metadata cannot leak into the parity contract. The
 * converter is a separate concern and lives outside this module.
 *
 * **Coordinate-free by construction** — see [TraceEvent]. This file leaves the device by
 * design, so a latitude reaching it would turn the recording into a log of where the user
 * parks, which docs/00_CORE_RULES.md Privacy forbids outright.
 */
@Serializable
data class TraceSession(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val platform: String = PLATFORM,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val startedAt: Long,
    /** The newest event's time. Advances as the session is appended to. */
    val endedAt: Long,
    val label: TraceLabel = TraceLabel(),
    val events: List<TraceEvent> = emptyList(),
) {
    /** A session nobody has classified yet is the one the converter cannot use. */
    val isLabelled: Boolean get() = label.mode != TraceMode.UNKNOWN

    fun appending(event: TraceEvent): TraceSession = copy(
        endedAt = maxOf(endedAt, event.atMillis),
        events = events + event,
    )

    /** The most recent admitted-or-not location fix, which is what a bucket change is judged against. */
    fun lastLocationEvent(): TraceEvent? = events.lastOrNull { it.type == TraceEventType.LOCATION }

    companion object {
        /** Bump whenever the shape changes, so an older payload is rejected, not half-read. */
        const val SCHEMA_VERSION: Int = 1
        const val PLATFORM: String = "android"

        fun opening(
            sessionId: String,
            device: TraceDeviceInfo,
            startedAt: Long,
        ): TraceSession = TraceSession(
            sessionId = sessionId,
            deviceModel = device.model,
            osVersion = device.osVersion,
            appVersion = device.appVersion,
            startedAt = startedAt,
            endedAt = startedAt,
        )
    }
}

/**
 * What the device cannot know: what the user was actually doing.
 *
 * §9 is explicit that the label is applied by a person in the app, and defaults to
 * [TraceMode.UNKNOWN] until then. The recording is evidence, not an answer — deciding what
 * the engine *should* have done is a judgement the converter leaves as a TODO.
 */
@Serializable
data class TraceLabel(
    val mode: TraceMode = TraceMode.UNKNOWN,
    /** Whether the trip ended in a parked car. Null while unknown — a walk parks nothing. */
    val parked: Boolean? = null,
    val note: String? = null,
)

/** §9's label vocabulary. The `@SerialName` values are the contract. */
@Serializable
enum class TraceMode {
    @SerialName("car")
    CAR,

    @SerialName("bus")
    BUS,

    @SerialName("subway")
    SUBWAY,

    @SerialName("taxi")
    TAXI,

    @SerialName("walk")
    WALK,

    @SerialName("still")
    STILL,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * Device identity for the trace header.
 *
 * Model and OS version are what make a trace comparable across the reference devices
 * docs/05_PARKING_DETECTION_ENGINE.md §18 asks for; they identify hardware, not a person,
 * and carry none of the fields docs/09_SECURITY_PRIVACY_COMPLIANCE.md restricts.
 */
data class TraceDeviceInfo(
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

/**
 * What the diagnostics report says about the recorder.
 *
 * [discardedSessionCount] is the number the rolling cap has thrown away. §9 requires a cap
 * so an all-day recording cannot fill storage, and a cap that silently eats evidence is
 * worse than no cap: a field run that looks thin because forty sessions were dropped
 * should say so.
 */
@Serializable
data class TraceSummary(
    val sessionCount: Int = 0,
    val eventCount: Int = 0,
    val discardedSessionCount: Int = 0,
    val unlabelledSessionCount: Int = 0,
    /** Short, coordinate-free reason the last trace write failed, if it did. */
    val lastFailure: String? = null,
)

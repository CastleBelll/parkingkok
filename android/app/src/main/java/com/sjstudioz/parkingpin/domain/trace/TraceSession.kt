package com.sjstudioz.parkingpin.domain.trace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the silences inside one recorded session looked like
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "gap 계측").
 *
 * **This exists to retire a guess, not to act on one.** The 30-minute idle gap in
 * [TraceSessionBoundaryPolicy] was set from a single day of observation, and the same day
 * showed it landing badly: office waits of 20.7 / 28.9 / 28.4 minutes all stayed inside
 * one session — the largest missing the threshold by 66 seconds — while a 16.9-minute
 * silence sat in the middle of a real subway ride, so lowering the number would have cut a
 * genuine journey in two. Nothing here changes the threshold. It records what a threshold
 * would have had to decide, so the next change is made against a distribution rather than
 * against one commute.
 *
 * The two counts bracket the interesting region instead of describing it fully. A
 * histogram of every gap would say more and would also mean carrying an array per session
 * for a number nobody reads until the retune; the whole distribution is recoverable from
 * the events once a trace is copied off the device. These three numbers are what makes the
 * aggregate cheap enough to put on the diagnostics report.
 */
@Serializable
data class TraceGapStats(
    /**
     * Milliseconds. `0` for a session of fewer than two events — no gap was observed,
     * which is not the same as a short one, and the viability rule discards those anyway.
     */
    val maxGapMillis: Long = 0L,
    val gapsOver10MinCount: Int = 0,
    val gapsOver20MinCount: Int = 0,
) {
    /**
     * Folds in the silence before one appended event.
     *
     * Called once per appended event, which is what keeps measurement off the battery
     * budget: three integer comparisons on a path that was already writing the event.
     *
     * A negative delta should not arrive — the boundary policy rotates on a clock that
     * went backwards — but it is clamped rather than trusted, because a negative maximum
     * would read as "no gap observed" and quietly poison the aggregate.
     */
    fun recording(gapMillis: Long): TraceGapStats {
        val gap = maxOf(0L, gapMillis)
        return TraceGapStats(
            maxGapMillis = maxOf(maxGapMillis, gap),
            gapsOver10MinCount = gapsOver10MinCount + if (gap > TEN_MINUTES_MILLIS) 1 else 0,
            gapsOver20MinCount = gapsOver20MinCount + if (gap > TWENTY_MINUTES_MILLIS) 1 else 0,
        )
    }

    companion object {
        const val TEN_MINUTES_MILLIS: Long = 10L * 60L * 1_000L
        const val TWENTY_MINUTES_MILLIS: Long = 20L * 60L * 1_000L

        /**
         * Measures a finished event list in one pass.
         *
         * For the two places with no append to hang the increment off: a session recorded
         * before gap measurement landed and reopened by this build, and the fragments a
         * human cut out of a longer session. Both are rare and bounded by
         * [TraceSessionBoundaryPolicy.MAX_EVENTS_PER_SESSION].
         */
        fun of(events: List<TraceEvent>): TraceGapStats =
            events.zipWithNext().fold(TraceGapStats()) { stats, (previous, next) ->
                stats.recording(next.atMillis - previous.atMillis)
            }
    }
}

/**
 * Where a session came from when a human cut a longer one in two
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "사람이 세션을 나눈다").
 *
 * The device cannot tell "sitting in the office" from "travelling" — both emit motion
 * edges, and the September 2026 traces put the silence between them within a minute of the
 * boundary. A person can tell, so the cut is theirs to make; this records that they made
 * it. Both fragments carry the *same* value, so the pair stays recoverable from either
 * half long after the parent file has been evicted by the rolling cap.
 */
@Serializable
data class TraceSplitOrigin(
    val parentSessionId: String,
    /** The time of the first event of the second fragment — the cut itself, not a gap. */
    val atMillis: Long,
)

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
    /**
     * Null means *not measured*, which is a different claim from "no gap over 10 minutes"
     * — and the aggregate this feeds cannot afford to confuse them. A trace recorded
     * before gap measurement landed was never measured, and reading it as zero would
     * understate exactly the tail the retune is looking for. Every session opened by this
     * build carries one from its first event.
     */
    val gapStats: TraceGapStats? = null,
    /** Present only on a fragment a human cut out of a longer session. */
    val splitFrom: TraceSplitOrigin? = null,
) {
    /** A session nobody has classified yet is the one the converter cannot use. */
    val isLabelled: Boolean get() = label.mode != TraceMode.UNKNOWN

    /**
     * Appends one event and folds its gap into [gapStats] in the same step, so the
     * measurement costs the append path three integer comparisons and never a second pass
     * over the events.
     *
     * A session reopened from a file written before gap measurement landed is measured
     * once, here, rather than starting its tally from zero halfway through the trip.
     */
    fun appending(event: TraceEvent): TraceSession {
        val previous = events.lastOrNull()
        val measured = gapStats ?: TraceGapStats.of(events)
        return copy(
            endedAt = maxOf(endedAt, event.atMillis),
            events = events + event,
            gapStats = if (previous == null) measured else measured.recording(event.atMillis - previous.atMillis),
        )
    }

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
            gapStats = TraceGapStats(),
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
 * What the diagnostics report says about the recorder — and, now, what a different idle
 * gap would have done (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9).
 *
 * **The two drop counters stay apart on purpose.** They fail in opposite directions and a
 * single total would hide which is happening: [discardedSessionCount] climbing means the
 * rolling cap is evicting trips before anyone labelled them, while [nonViableDropCount]
 * climbing means the boundary is manufacturing single-event sessions and the threshold is
 * wrong. §9: "조용히 버리지 마라."
 *
 * The gap aggregate describes the sessions **still on disk**. Eviction takes a session's
 * gaps with it, which is the right reading for a report answering "what is currently
 * recorded" — the durable copy of the distribution is the trace files themselves, each
 * carrying its own [TraceGapStats], retrieved over the same `run-as` path.
 */
@Serializable
data class TraceSummary(
    val sessionCount: Int = 0,
    val eventCount: Int = 0,
    val discardedSessionCount: Int = 0,
    /**
     * Sessions discarded at rotation for holding one event or none (§9 "비생존 세션은
     * 버린다"). Cumulative since install, and never folded into [discardedSessionCount].
     */
    val nonViableDropCount: Int = 0,
    val unlabelledSessionCount: Int = 0,
    /**
     * Closed sessions that were worth a label prompt and did not get one, because
     * notifications are not permitted. The number that tells a field run which collected no
     * labels apart from a field run where nothing moved. Same name on iOS.
     */
    val labelPromptSuppressedCount: Int = 0,
    /**
     * Sessions on disk carrying a measurement, so the three numbers below read as a sample
     * size rather than as a claim about every trace. Below [sessionCount] only while
     * traces recorded before gap measurement landed are still retained.
     */
    val measuredSessionCount: Int = 0,
    /** The largest silence inside any retained session, in milliseconds. */
    val maxGapMillis: Long = 0L,
    val sessionsOver10MinGapCount: Int = 0,
    val sessionsOver20MinGapCount: Int = 0,
    /** Short, coordinate-free reason the last trace write failed, if it did. */
    val lastFailure: String? = null,
)

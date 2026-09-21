package kr.parkingpin.app.domain.trace

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The question a just-closed session puts to the user, reduced to what a notification can
 * carry (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 labelling).
 *
 * ### Why this exists
 * The in-app labelling screen shipped on both platforms and was never used once: eight
 * sessions on the reference device, every one of them `unknown`. The user carries the phone
 * and does not open the app, so the label has to be asked for where they already are — the
 * lock screen — at the one moment they still remember what the trip was. An unlabelled
 * trace cannot become a §8 fixture: the converter derives `expected` from `label.mode`, and
 * `unknown` leaves a human guessing from raw events weeks later.
 *
 * ### P0 instrumentation, not product UI
 * This is **not** the M3 candidate notification and must never be mistaken for it. It asks
 * what a *past* trip was, it is posted on its own diagnostics channel — never
 * docs/04_ANDROID_IMPLEMENTATION.md §9's `parking_detection`/`parking_status` — and it is
 * confined to these few types so the whole thing can be deleted in one commit once enough
 * field data exists.
 *
 * ### A pure value, mirrored on iOS
 * `TraceLabelPrompt.swift` computes the same fields from the same events, so a test can
 * state that both platforms ask the same question about the same recording — the same
 * reason [TraceSessionBoundaryPolicy] is a domain object rather than platform code.
 *
 * **The Korean strings live here rather than in `strings.xml`.** A deliberate deviation:
 * the rule that the prompt text carries no coordinate has to be *enforced by a test*, and
 * `unitTests.isReturnDefaultValues = true` makes a resource-backed string unreadable from a
 * JVM test. The diagnostics screen's own rows stay in `strings.xml`, where they are
 * testable by the instrumentation suite that renders them.
 *
 * **No coordinate is representable here.** It is built from [TraceSession], which has no
 * field to put one in, and the body is assembled from times, minutes and counts only.
 */
data class TraceLabelPrompt(
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val eventCount: Int,
    /**
     * Milliseconds inside `vehicle_enter` → `vehicle_exit` spans. `0` with [hasVehicle]
     * true means a vehicle edge was seen but never bracketed, which the Activity Transition
     * API routinely does.
     */
    val vehicleMillis: Long,
    val hasVehicle: Boolean,
    val hasWalking: Boolean,
    val hasStationary: Boolean,
) {

    /** The events in the words a person would use. Never empty — see [of]. */
    val movementSummary: String
        get() = buildList {
            if (hasVehicle) {
                val minutes = minutesOf(vehicleMillis)
                add(if (minutes > 0) "차량 ${minutes}분" else "차량")
            }
            if (hasWalking) add("도보")
            if (hasStationary) add("정지")
        }.joinToString(" + ")

    /**
     * What the user has to recognise the trip by: when it ran, how long, and what the
     * device saw. Never where.
     *
     * [zone] is a parameter rather than [ZoneId.systemDefault] at the call site so the text
     * is checkable by a test (docs/16_CODING_STANDARDS.md §8: inject, never read ambient
     * state in logic worth asserting on).
     */
    fun body(zone: ZoneId = ZoneId.systemDefault()): String {
        val range = "${clockTime(startedAtMillis, zone)}–${clockTime(endedAtMillis, zone)}"
        val total = "${minutesOf(endedAtMillis - startedAtMillis)}분"
        return "$range · $total · $movementSummary · 이벤트 ${eventCount}개"
    }

    companion object {
        /** Deliberately a question, so it cannot read as the M3 "parked here" notification. */
        const val TITLE: String = "이 이동, 무엇이었나요?"

        /**
         * The modes offered as one-tap actions, most useful first.
         *
         * [TraceMode.CAR], [TraceMode.BUS] and [TraceMode.SUBWAY] are indistinguishable
         * from the recorded events — all three are a `vehicle_enter` followed by movement —
         * so they are exactly what only a person can supply, and the two negative ones are
         * the fixtures docs/05_PARKING_DETECTION_ENGINE.md §17 asks for and that no amount
         * of driving produces. [TraceMode.WALK] comes last because a walk-only trace is
         * still readable from its events afterwards, which is why it is the one that drops
         * off when the platform allows fewer actions than this list holds.
         *
         * `TAXI` and `STILL` are deliberately absent; both remain in the in-app labelling
         * screen, which this never replaces.
         */
        val OFFERED_MODES: List<TraceMode> = listOf(TraceMode.CAR, TraceMode.BUS, TraceMode.SUBWAY, TraceMode.WALK)

        /**
         * `null` when there is nothing worth asking about.
         *
         * Two refusals, both deliberate:
         *
         * 1. **No motion event.** A session of three `location` events is a question the
         *    user cannot answer — nothing in it distinguishes a bus from a desk, and they
         *    were not told anything was being recorded at the time. Ask only what can be
         *    answered.
         * 2. **Not viable.** §9 discards a session holding fewer than
         *    [TraceSessionBoundaryPolicy.MINIMUM_VIABLE_EVENT_COUNT] events, so prompting
         *    for one would ask about a file that is about to be deleted.
         */
        fun of(session: TraceSession): TraceLabelPrompt? {
            if (!TraceSessionBoundaryPolicy.isViable(session.events.size)) return null
            val types = session.events.mapTo(mutableSetOf()) { it.type }
            if (types.none { it.isMotion }) return null

            var vehicleMillis = 0L
            var enteredAt: Long? = null
            for (event in session.events) {
                when (event.type) {
                    // The first enter of an unclosed span wins; a repeated enter is the OS
                    // restating a drive, not a second boarding.
                    TraceEventType.VEHICLE_ENTER -> if (enteredAt == null) enteredAt = event.atMillis
                    TraceEventType.VEHICLE_EXIT -> enteredAt?.let { start ->
                        vehicleMillis += event.atMillis - start
                        enteredAt = null
                    }
                    else -> Unit
                }
            }
            // A ride whose exit never arrived ran to the end of the recording, which is what
            // the session's own endedAt says.
            enteredAt?.let { vehicleMillis += max(0L, session.endedAt - it) }

            return TraceLabelPrompt(
                sessionId = session.sessionId,
                startedAtMillis = session.startedAt,
                endedAtMillis = session.endedAt,
                eventCount = session.events.size,
                vehicleMillis = vehicleMillis,
                hasVehicle = TraceEventType.VEHICLE_ENTER in types || TraceEventType.VEHICLE_EXIT in types,
                hasWalking = TraceEventType.WALKING_ENTER in types,
                hasStationary = TraceEventType.STATIONARY_ENTER in types || TraceEventType.STATIONARY_EXIT in types,
            )
        }

        /**
         * What one tap means (§9 `label`).
         *
         * `parked` is inferred rather than asked, because a second tap is the thing that
         * stopped the in-app screen from being used: a walk, a bus and a subway park
         * nothing, and only a car leaves the question genuinely open — so a car stays null
         * and is answered in the app by whoever needs it. §9 makes null a first-class
         * value, so this claims nothing it does not know.
         */
        fun labelFor(mode: TraceMode): TraceLabel = when (mode) {
            TraceMode.CAR, TraceMode.TAXI -> TraceLabel(mode = mode, parked = null)
            TraceMode.BUS, TraceMode.SUBWAY, TraceMode.WALK, TraceMode.STILL ->
                TraceLabel(mode = mode, parked = false)
            TraceMode.UNKNOWN -> TraceLabel()
        }

        /** The word on the notification button, and the same word the labelling screen uses. */
        fun actionTitle(mode: TraceMode): String = when (mode) {
            TraceMode.CAR -> "자동차"
            TraceMode.BUS -> "버스"
            TraceMode.SUBWAY -> "지하철"
            TraceMode.TAXI -> "택시"
            TraceMode.WALK -> "도보"
            TraceMode.STILL -> "정지"
            TraceMode.UNKNOWN -> "모름"
        }

        /**
         * Fixed 24-hour, not a localized time: the body sits beside a duration and an event
         * count, and "오후 2:03" is longer and harder to compare against the next line. The
         * same format iOS writes.
         */
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private fun clockTime(atMillis: Long, zone: ZoneId): String =
            CLOCK.format(Instant.ofEpochMilli(atMillis).atZone(zone))

        private fun minutesOf(millis: Long): Int = (millis / 60_000.0).roundToInt()
    }
}

/**
 * Whether the event came from the motion signal rather than from location.
 *
 * The prompt turns on this distinction: a session of location fixes alone is one nobody
 * could label, so it is never asked about.
 */
val TraceEventType.isMotion: Boolean
    get() = when (this) {
        TraceEventType.VEHICLE_ENTER,
        TraceEventType.VEHICLE_EXIT,
        TraceEventType.WALKING_ENTER,
        TraceEventType.STATIONARY_ENTER,
        TraceEventType.STATIONARY_EXIT,
        -> true

        TraceEventType.LOCATION,
        TraceEventType.LOCATION_QUALITY_DEGRADED,
        -> false
    }

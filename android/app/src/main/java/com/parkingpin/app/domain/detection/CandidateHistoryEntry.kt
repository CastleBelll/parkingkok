package com.parkingpin.app.domain.detection

import kotlinx.serialization.Serializable

/**
 * What became of a candidate (docs/10_DESIGN_UX_SPEC.md §7b "What is in it").
 *
 * Three outcomes and no others. A candidate the user never answered reads the same
 * whether it ran out its forty-five minutes, was superseded by the next trip, or was
 * retired when the car link came back: nobody answered it, and §7b gives that one word.
 */
@Serializable
enum class CandidateOutcome {
    /** Became a parking record. The row names the floor it became. */
    CONFIRMED,

    /** The user said `주차 아님`. */
    REJECTED,

    /** Nobody answered before it went away. */
    EXPIRED,
}

/**
 * One line of the notification history the bell opens
 * (docs/10_DESIGN_UX_SPEC.md §7b, docs/05_PARKING_DETECTION_ENGINE.md §10a "History").
 *
 * ### Why it is a separate list
 * §10a is explicit that the live candidate slot keeps holding at most one and that this
 * is an append-only list beside it, "because the two answer different questions and
 * giving the slot a second job is how it would end up holding two live candidates by
 * accident".
 *
 * ### What it cannot hold
 * The raised-at time, the outcome, and for a confirmed one the id of the record it
 * became. **No coordinate, no address.** §10a keeps location off this surface and §7b
 * repeats it, so there is deliberately no field here that could carry one — the rule is
 * structural rather than something a future caller has to remember. The floor a
 * confirmed row shows is read from the record at display time, for the same reason.
 */
@Serializable
data class CandidateHistoryEntry(
    /** The candidate this line is about. Unique, so it doubles as the list key. */
    val candidateId: String,
    /** When the candidate was raised — [ParkingCandidate.detectedAtMillis]. */
    val raisedAtMillis: Long,
    val outcome: CandidateOutcome,
    /** The record a [CandidateOutcome.CONFIRMED] entry became. Null for the other two. */
    val recordId: String? = null,
) {
    companion object {
        /**
         * §7b Retention: the last 30, and older ones fall off.
         *
         * "A user looking further back wants the parking history, which is a different
         * screen and already keeps everything."
         */
        const val MAX_ENTRIES: Int = 30
    }
}

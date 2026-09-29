package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.parking.ParkingPlaceText
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord

/**
 * A departure the engine confirmed and the user has not answered yet
 * (docs/05_PARKING_DETECTION_ENGINE.md §11a, DECIDED 2026-09-29).
 *
 * The engine never ends a parking by itself: it emits [DetectionEffect.ProposeParkingEnd] and
 * the adapter keeps one of these until the user says `주차 종료` or `아직 주차 중`, the next
 * parking is saved, or the parking is ended by hand. One at a time — a later departure
 * replaces it.
 *
 * [recordId] is the parking it asks about. A proposal whose record is no longer the open one
 * asks about nothing and is never shown or acted on.
 */
data class ParkingEndProposal(
    val recordId: String,
    /** When the car pulled away — the end time the record gets if the user accepts. */
    val departedAtMillis: Long,
)

/**
 * The copy the proposal is asked with, on the notification and on the home card alike.
 *
 * docs/10 §7: a guess reads as a guess. The departure is never stated as fact — "출발한 것
 * 같아요", then a question.
 */
object ParkingEndProposalNotice {

    const val TITLE: String = "출발한 것 같아요"

    /** The primary answer: close the record at the departure time. */
    const val ACTION_END: String = "주차 종료"

    /** The secondary answer: the car is still parked. */
    const val ACTION_KEEP: String = "아직 주차 중"

    private const val QUESTION = "주차를 종료할까요?"

    private const val SEPARATOR = " · "

    /** `B3 · A구역 · 142 주차를 종료할까요?`, leaving out whatever the record does not hold. */
    fun body(record: ParkingRecord?): String {
        val place = record?.let(::placeText) ?: return QUESTION
        return "$place $QUESTION"
    }

    /**
     * The floor, then the zone/spot line the home hero shows ([ParkingPlaceText]), joined
     * with a middle dot — or null when the record holds none. Identical to iOS
     * `ParkingEndProposalCopy.placeText`: `B3 · 142번` for a floor and a spot alone.
     */
    fun placeText(record: ParkingRecord): String? =
        listOfNotNull(record.floor?.displayLabel, ParkingPlaceText.zoneAndSpot(record.zone, record.spot))
            .takeIf { it.isNotEmpty() }
            ?.joinToString(SEPARATOR)
}

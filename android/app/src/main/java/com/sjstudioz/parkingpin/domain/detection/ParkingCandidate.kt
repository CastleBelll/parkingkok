package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.analytics.DetectionProperties
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import kotlinx.serialization.Serializable

/**
 * A guess that the car was parked, waiting for the user to answer it
 * (docs/05_PARKING_DETECTION_ENGINE.md §10a).
 *
 * At most one exists at a time — §12 allows one candidate per travel session — which is
 * why it is stored as a single value beside the detection checkpoint rather than in a
 * table. It is detection state: short-lived, superseded by the next trip, and read by
 * processes a broadcast started, none of which should have to open Room.
 *
 * **It is not a parking record.** Nothing here is history until the user confirms, and
 * [ParkingCandidateCoordinator] is the only thing allowed to turn one into the other.
 *
 * [toString] is redacted for the same reason [ReliableLocation]'s is: a candidate carries
 * a coordinate, and an interpolation into a log must not leak it.
 */
@Serializable
data class ParkingCandidate(
    /**
     * §10a "Identity and deduplication": the notification is posted under this id, so
     * re-posting the same candidate replaces its notification instead of stacking a
     * second one.
     */
    val id: String,
    /** When [DetectionState.CANDIDATE_PENDING] was entered. */
    val detectedAtMillis: Long,
    /**
     * When the car is believed to have been left — the reliable fix's capture time when
     * there is one, otherwise [detectedAtMillis].
     *
     * It is the time the confirmation screen shows, and the time a confirmed record
     * starts from: the user is being asked about the moment the car stopped, not the
     * moment the device finished deciding.
     */
    val parkedAtMillis: Long,
    /** §10 default lifetime, resolved at creation so a clock read cannot move it later. */
    val expiresAtMillis: Long,
    /** §6. Null is ordinary — a drive that ended underground has no reliable fix. */
    val lastReliableLocation: ReliableLocation? = null,
    /**
     * Everything the three `parking_candidate_*` events are allowed to say (docs/17 §3).
     *
     * Stored rather than recomputed so `_confirmed` and `_rejected` describe the drive
     * that produced the candidate rather than whatever the device is doing when the user
     * finally answers. Storing *this* type rather than loose fields is also what keeps a
     * coordinate out of the payload: [DetectionProperties] has nowhere to put one.
     */
    val evidence: DetectionProperties,
) {
    val confidenceBucket: ConfidenceBucket get() = evidence.confidenceBucket

    /**
     * §9: `low` records the candidate but posts nothing. The evidence is still kept, and
     * the app still shows it when opened.
     */
    val isNotifiable: Boolean get() = confidenceBucket != ConfidenceBucket.LOW

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    override fun toString(): String =
        "ParkingCandidate(id=$id, redacted, confidence=$confidenceBucket, expiresAtMillis=$expiresAtMillis)"

    companion object {
        /** docs/05 §10: 45 minutes, the same number on both platforms. */
        const val LIFETIME_MILLIS: Long = 45 * 60 * 1_000L

        fun of(
            id: String,
            detectedAtMillis: Long,
            lastReliableLocation: ReliableLocation?,
            evidence: DetectionProperties,
        ): ParkingCandidate = ParkingCandidate(
            id = id,
            detectedAtMillis = detectedAtMillis,
            parkedAtMillis = lastReliableLocation?.capturedAtMillis ?: detectedAtMillis,
            expiresAtMillis = detectedAtMillis + LIFETIME_MILLIS,
            lastReliableLocation = lastReliableLocation,
            evidence = evidence,
        )
    }
}

/**
 * The words the candidate notification uses, fixed by
 * `docs/02_PRODUCT_SCOPE_AND_FLOWS.md` §5 and quoted again in docs/05 §10a.
 *
 * **The Korean lives here rather than in `strings.xml`**, the same deliberate deviation
 * [com.sjstudioz.parkingpin.domain.trace.TraceLabelPrompt] documents: the rules that this copy
 * is never reworded and that it never names a floor, an address or a coordinate have to
 * be *enforced by a test*, and `unitTests.isReturnDefaultValues = true` makes a
 * resource-backed string unreadable from a JVM test.
 *
 * Nothing here is formatted from a candidate. There is no interpolation point, so there
 * is nowhere for a location to appear (docs/09 §9).
 */
object ParkingCandidateNotice {

    /** docs/10 §7: the copy communicates a guess. Never `주차 완료`. */
    const val TITLE: String = "주차한 것 같아요"

    const val BODY: String = "마지막으로 확인된 위치와 시간을 저장해뒀어요."

    /** docs/02 §5 "confirm/open floor entry". Opens the confirmation screen. */
    const val ACTION_OPEN: String = "층 입력"

    /** docs/02 §5, docs/10 §7 secondary. Answered without opening the app. */
    const val ACTION_REJECT: String = "주차 아님"
}

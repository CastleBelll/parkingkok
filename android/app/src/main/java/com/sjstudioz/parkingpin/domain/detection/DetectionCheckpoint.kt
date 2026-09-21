package com.sjstudioz.parkingpin.domain.detection

import kotlinx.serialization.Serializable

/**
 * Local-only coordinates of the last location sample judged reliable.
 *
 * docs/00_CORE_RULES.md Privacy: these coordinates never reach logs, analytics, or the
 * backend. [toString] is redacted on purpose so an accidental `Log.d("$checkpoint")`
 * cannot leak a position.
 *
 * M0B-1 defines the shape only — the selection algorithm that populates it lands in M0B-2
 * (docs/04_ANDROID_IMPLEMENTATION.md §8).
 */
@Serializable
data class ReliableLocation(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float,
    val capturedAtMillis: Long,
) {
    override fun toString(): String =
        "ReliableLocation(redacted, accuracyM=$horizontalAccuracyM, capturedAtMillis=$capturedAtMillis)"
}

/**
 * Rehydration checkpoint written after every meaningful transition
 * (docs/05_PARKING_DETECTION_ENGINE.md §14). Field-for-field parity with the iOS
 * checkpoint in docs/04_IOS_IMPLEMENTATION.md §6 is required.
 *
 * [revision] increments on every persisted write so concurrent writers and widget
 * callbacks can detect a stale snapshot.
 */
@Serializable
data class DetectionCheckpoint(
    val state: DetectionState,
    val stateEnteredAtMillis: Long,
    val lastAutomotiveAtMillis: Long? = null,
    val lastReliableLocation: ReliableLocation? = null,
    val lastLocationAtMillis: Long? = null,
    val travelDistanceEstimateMeters: Double = 0.0,
    val candidateId: String? = null,
    val revision: Long = 0,
) {
    companion object {
        fun initial(nowMillis: Long): DetectionCheckpoint =
            DetectionCheckpoint(state = DetectionState.IDLE, stateEnteredAtMillis = nowMillis)
    }
}

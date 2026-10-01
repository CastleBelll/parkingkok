package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent

/**
 * Confirming a candidate, the one way both answers take: the confirmation screen and the
 * notification's inline `층 입력` (docs/02 §5, iOS `CandidateNotificationResponder`).
 *
 * Three steps that must stay together, which is why they live here once: the record is
 * written (closing a parking a pending departure asked about, docs/05 §11a), that departure
 * question is retired, and the engine is told the car is parked so it leaves
 * `CANDIDATE_PENDING`. Only a write that happened moves the machine — `Gone` and
 * `AlreadyActive` left the candidate exactly where it was.
 */
class CandidateConfirmation(
    private val coordinator: ParkingCandidateCoordinator,
    private val endProposals: ParkingEndProposalCoordinator?,
    private val detectionRuntime: ParkingDetectionRuntime?,
    private val clock: Clock,
) {
    suspend fun confirm(candidateId: String, details: ConfirmedCandidateDetails): ConfirmCandidateResult {
        val result = coordinator.confirm(
            candidateId,
            details,
            // Asked only after the candidate proved live (docs/05 §11a): an expired or
            // superseded candidate writes nothing and must leave the asked-about parking open.
            pendingEnd = { endProposals?.pendingEnd() },
        )
        if (result is ConfirmCandidateResult.Confirmed) {
            result.endedPrevious?.let { endProposals?.retire(it.id) }
            detectionRuntime?.handleUserAnswer(DetectionEvent.UserConfirmedParking(clock.nowEpochMillis()))
        }
        return result
    }
}

package kr.parkingpin.app.domain.detection

/**
 * Shared detection state machine states.
 * Semantics are fixed by docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §3 and must stay
 * identical to the iOS enum. Transition logic itself is out of M0B-1 scope.
 */
enum class DetectionState {
    IDLE,
    DRIVING_CANDIDATE,
    DRIVING,

    /** Vehicle activity ended; waiting for a confirmation signal. Nothing shown yet. */
    PARKING_TRANSITION,

    /** Candidate persisted and notified; counting down its 45-minute expiry. */
    CANDIDATE_PENDING,

    PARKED,
    DEPARTURE_CANDIDATE,
}

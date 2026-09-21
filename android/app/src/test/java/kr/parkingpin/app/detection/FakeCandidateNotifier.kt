package kr.parkingpin.app.detection

import kr.parkingpin.app.domain.detection.ParkingCandidate

/**
 * A notification shade, modelled the one way that matters.
 *
 * Entries are keyed by [ParkingCandidateChannel.notificationId], exactly as the platform
 * keys them — so "posting the same candidate twice replaces rather than stacks"
 * (docs/05_PARKING_DETECTION_ENGINE.md §10a) is a property of this fake for the same
 * reason it is a property of Android, and a test can assert it without a device.
 */
class FakeCandidateNotifier(var authorized: Boolean = true) : CandidateNotifying {

    private val shade = LinkedHashMap<Int, ParkingCandidate>()

    /** Every post that was attempted, including ones that replaced an earlier entry. */
    val posted = mutableListOf<ParkingCandidate>()

    val withdrawn = mutableListOf<String>()

    /** What is on screen now. One entry per candidate id, newest content. */
    val showing: List<ParkingCandidate> get() = shade.values.toList()

    override fun isAuthorized(): Boolean = authorized

    override fun post(candidate: ParkingCandidate) {
        posted += candidate
        shade[ParkingCandidateChannel.notificationId(candidate.id)] = candidate
    }

    override fun withdraw(candidateId: String) {
        withdrawn += candidateId
        shade.remove(ParkingCandidateChannel.notificationId(candidateId))
    }
}

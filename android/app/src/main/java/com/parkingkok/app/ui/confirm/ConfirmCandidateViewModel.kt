package com.parkingkok.app.ui.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.detection.ConfirmCandidateResult
import com.parkingkok.app.detection.ConfirmedCandidateDetails
import com.parkingkok.app.core.Clock
import com.parkingkok.app.core.SystemClock
import com.parkingkok.app.detection.ParkingCandidateCoordinator
import com.parkingkok.app.detection.ParkingDetectionRuntime
import com.parkingkok.app.domain.detection.DetectionEvent
import com.parkingkok.app.domain.parking.Floor
import com.parkingkok.app.domain.parking.usecase.RecentFloorPicksUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What `docs/10_DESIGN_UX_SPEC.md` §7a renders. */
data class ConfirmCandidateUiState(
    /**
     * When the car is believed to have been left. `오후 8:14` on the screen.
     *
     * The only number on this surface. There is no coordinate, no address and no floor
     * estimate here — §7a and docs/09 §9 both keep location off this screen, and the
     * state has no field one could be put in.
     */
    val parkedAtMillis: Long? = null,
    /** §7a: from the user's own history, newest first. Empty on a first-ever run. */
    val floorPicks: List<Floor> = emptyList(),
    /** False until the candidate has been looked up; the screen shows nothing before then. */
    val loaded: Boolean = false,
    /**
     * The candidate expired, was superseded, or was already answered. The shell sends the
     * user home — §10a: no dialog, no apology.
     */
    val gone: Boolean = false,
    /**
     * Set when the candidate had already been confirmed: §10a says the tap then "opens on
     * the record it became" rather than on home.
     */
    val openRecordId: String? = null,
    /** A parking session was already open, so the confirmation could not be written. */
    val alreadyActive: Boolean = false,
    /** Set once the record exists; the shell navigates on it. */
    val confirmedRecordId: String? = null,
    /** Set once the candidate has been discarded; the shell goes back. */
    val rejected: Boolean = false,
    val working: Boolean = false,
)

/**
 * Drives the confirmation screen.
 *
 * It owns no rule about what a candidate is — [ParkingCandidateCoordinator] does — and it
 * deliberately never confirms by itself. docs/05 §9 forbids auto-confirmation, so every
 * path out of this ViewModel starts with something the user pressed.
 */
class ConfirmCandidateViewModel(
    private val candidateId: String,
    private val coordinator: ParkingCandidateCoordinator,
    private val recentFloorPicks: RecentFloorPicksUseCase,
    /**
     * Where the answer goes back to the §3a state machine.
     *
     * Nullable so a test can drive the screen without a DataStore. The answer has to reach
     * the engine or it never leaves `CANDIDATE_PENDING`: the record would exist and the
     * detector would still be waiting to hear about the trip that produced it.
     */
    private val detectionRuntime: ParkingDetectionRuntime? = null,
    private val clock: Clock = SystemClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfirmCandidateUiState())
    val uiState: StateFlow<ConfirmCandidateUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val candidate = coordinator.pending(candidateId)
            if (candidate == null) {
                val became = coordinator.confirmedRecordId(candidateId)
                _uiState.update { it.copy(loaded = true, gone = true, openRecordId = became) }
                return@launch
            }
            val picks = recentFloorPicks()
            _uiState.update {
                it.copy(
                    loaded = true,
                    parkedAtMillis = candidate.parkedAtMillis,
                    floorPicks = picks,
                )
            }
        }
    }

    /** §7a: "choosing a floor confirms in one tap". */
    fun onPickFloor(floor: Floor) {
        confirm(ConfirmedCandidateDetails(floor = floor))
    }

    fun onReject() {
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            coordinator.reject(candidateId)
            detectionRuntime?.handleUserAnswer(
                DetectionEvent.UserRejectedParking(clock.nowEpochMillis()),
            )
            // True whether or not a stored candidate was found: the user has answered, and
            // the screen's job is done either way.
            _uiState.update { it.copy(working = false, rejected = true) }
        }
    }

    private fun confirm(details: ConfirmedCandidateDetails) {
        // Guarding on the flag rather than on the button's enabled state: two taps can land
        // before a recomposition, and each would try to open a session.
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true, alreadyActive = false) }
        viewModelScope.launch {
            val result = coordinator.confirm(candidateId, details)
            // Only a write that happened moves the machine: `Gone` and `AlreadyActive`
            // left the candidate exactly where it was.
            if (result is ConfirmCandidateResult.Confirmed) {
                detectionRuntime?.handleUserAnswer(
                    DetectionEvent.UserConfirmedParking(clock.nowEpochMillis()),
                )
            }
            _uiState.update {
                when (result) {
                    is ConfirmCandidateResult.Confirmed ->
                        it.copy(working = false, confirmedRecordId = result.record.id)
                    is ConfirmCandidateResult.Gone ->
                        it.copy(working = false, gone = true, openRecordId = result.alreadyBecame)
                    is ConfirmCandidateResult.AlreadyActive ->
                        it.copy(working = false, alreadyActive = true)
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, candidateId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConfirmCandidateViewModel(
                        candidateId = candidateId,
                        coordinator = container.parkingCandidateCoordinator,
                        recentFloorPicks = RecentFloorPicksUseCase(container.parkingRepository),
                        detectionRuntime = container.parkingDetectionRuntime,
                        clock = container.clock,
                    ) as T
            }
    }
}

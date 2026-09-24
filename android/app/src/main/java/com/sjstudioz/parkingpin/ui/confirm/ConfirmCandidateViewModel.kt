package com.sjstudioz.parkingpin.ui.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjstudioz.parkingpin.AppContainer
import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.core.SystemClock
import com.sjstudioz.parkingpin.detection.ParkingCandidateCoordinator
import com.sjstudioz.parkingpin.detection.ParkingDetectionRuntime
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.sjstudioz.parkingpin.ui.components.MapPoint
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The fix behind §7a's `마지막으로 확인된 위치`.
 *
 * [point] is the coordinate, for the map and nothing else: until 2026-09-24 Android drew
 * no tiles and this held the radius only (docs/04_ANDROID_IMPLEMENTATION.md §12). It is a
 * [MapPoint], whose `toString` is redacted, so carrying it in UI state cannot leak it.
 * [accuracyM] is null when the fix carried no usable accuracy, which reads as
 * `마지막으로 확인된 위치` with no radius beside it rather than as no location at all.
 */
data class ConfirmLocation(val accuracyM: Int?, val point: MapPoint? = null)

/** What `docs/10_DESIGN_UX_SPEC.md` §7a renders. */
data class ConfirmCandidateUiState(
    /**
     * When the car is believed to have been left. `오후 8:14` on the screen.
     *
     * There is no floor estimate here: the engine does not know which floor you are on,
     * and §7a rules a guess out.
     */
    val parkedAtMillis: Long? = null,
    /**
     * §7a "where": what the screen says under `마지막으로 확인된 위치`. Null is the ordinary
     * underground outcome — the drive produced no fix worth keeping — and the screen then
     * says `위치 없음` in that same place rather than dropping the row.
     */
    val location: ConfirmLocation? = null,
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
 *
 * **It no longer confirms at all.** The one-tap floor picks were removed on 2026-09-20
 * (docs/10 §7a), and with them the only confirmation this screen could perform. Both ways
 * forward now open the manual entry form, and `ManualParkingViewModel` writes the record
 * and reports the answer to the §3a machine. What is left here is: load the candidate,
 * notice it is gone, and reject.
 */
class ConfirmCandidateViewModel(
    private val candidateId: String,
    private val coordinator: ParkingCandidateCoordinator,
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
            _uiState.update {
                it.copy(
                    loaded = true,
                    parkedAtMillis = candidate.parkedAtMillis,
                    location = candidate.lastReliableLocation?.let { fix ->
                        ConfirmLocation(
                            accuracyM = fix.horizontalAccuracyM.takeIf { m -> m > 0f }?.toInt(),
                            point = MapPoint(fix.latitude, fix.longitude),
                        )
                    },
                )
            }
        }
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

    companion object {
        fun factory(container: AppContainer, candidateId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConfirmCandidateViewModel(
                        candidateId = candidateId,
                        coordinator = container.parkingCandidateCoordinator,
                        detectionRuntime = container.parkingDetectionRuntime,
                        clock = container.clock,
                    ) as T
            }
    }
}

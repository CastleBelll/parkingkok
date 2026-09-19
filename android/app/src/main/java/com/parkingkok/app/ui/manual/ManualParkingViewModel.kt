package com.parkingkok.app.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.detection.ConfirmCandidateResult
import com.parkingkok.app.detection.ConfirmedCandidateDetails
import com.parkingkok.app.detection.ParkingCandidateCoordinator
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.usecase.ManualParkingInput
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingResult
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The manual entry form (FR-001). */
data class ManualParkingUiState(
    val floorRaw: String = "",
    val zone: String = "",
    val spot: String = "",
    val memo: String = "",
    val saving: Boolean = false,
    /** Set when a session was already open, so the screen can explain instead of silently failing. */
    val alreadyActive: Boolean = false,
    /** Set once the record is written; the screen navigates home on it. */
    val savedRecordId: String? = null,
    /**
     * Set when the candidate this form was confirming expired or was superseded while it
     * was open. The screen leaves without writing anything (docs/05 §10a).
     */
    val candidateGone: Boolean = false,
)

/**
 * Drives the manual entry form.
 *
 * This screen asks for no permission and reads no sensor. That is the point: FR-001 says
 * a manual save must work with everything denied, and the way to guarantee it is for this
 * path not to depend on any of it.
 *
 * It serves two arrivals. With no [candidateId] it is FR-001's manual save. With one it is
 * docs/10_DESIGN_UX_SPEC.md §7a's `직접 입력`, and saving confirms that candidate instead —
 * `source = detected`, the candidate's own location, the floor typed here. One form rather
 * than two, because a second copy of it would drift from this one; the branch is a single
 * call at the end of [onSave].
 */
class ManualParkingViewModel(
    private val saveManualParking: SaveManualParkingUseCase,
    private val candidateId: String? = null,
    private val coordinator: ParkingCandidateCoordinator? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualParkingUiState())
    val uiState: StateFlow<ManualParkingUiState> = _uiState.asStateFlow()

    fun onFloorChange(value: String) = _uiState.update { it.copy(floorRaw = value) }

    fun onZoneChange(value: String) = _uiState.update { it.copy(zone = value) }

    fun onSpotChange(value: String) = _uiState.update { it.copy(spot = value) }

    fun onMemoChange(value: String) = _uiState.update { it.copy(memo = value) }

    fun onSave() {
        // Guarding on `saving` rather than disabling the button alone: a double tap can
        // land two clicks before the first recomposition, and each would open a session.
        if (_uiState.value.saving) return
        _uiState.update { it.copy(saving = true, alreadyActive = false) }

        viewModelScope.launch {
            val state = _uiState.value
            val input = ManualParkingInput(
                floorRaw = state.floorRaw,
                zone = state.zone,
                spot = state.spot,
                memo = state.memo,
            )
            if (candidateId != null && coordinator != null) {
                confirmCandidate(candidateId, coordinator, input)
            } else {
                applySaveResult(saveManualParking(input))
            }
        }
    }

    private suspend fun confirmCandidate(
        candidateId: String,
        coordinator: ParkingCandidateCoordinator,
        input: ManualParkingInput,
    ) {
        val result = coordinator.confirm(
            candidateId,
            ConfirmedCandidateDetails(
                floor = FloorParser.parse(input.floorRaw),
                zone = input.zone?.normalize(MAX_SHORT_FIELD),
                spot = input.spot?.normalize(MAX_SHORT_FIELD),
                memo = input.memo?.normalize(MAX_MEMO),
            ),
        )
        _uiState.update {
            when (result) {
                is ConfirmCandidateResult.Confirmed ->
                    it.copy(saving = false, savedRecordId = result.record.id)
                is ConfirmCandidateResult.AlreadyActive ->
                    it.copy(saving = false, alreadyActive = true)
                // Expired or superseded while the form was open. The record it would have
                // become does not exist, so the screen leaves the way it would have on a
                // save (docs/05 §10a: no dialog).
                is ConfirmCandidateResult.Gone -> it.copy(saving = false, candidateGone = true)
            }
        }
    }

    private fun applySaveResult(result: SaveManualParkingResult) {
        _uiState.update {
            when (result) {
                is SaveManualParkingResult.Saved ->
                    it.copy(saving = false, savedRecordId = result.record.id)
                is SaveManualParkingResult.AlreadyActive ->
                    it.copy(saving = false, alreadyActive = true)
            }
        }
    }

    /**
     * The same trimming [SaveManualParkingUseCase] applies, because the confirmation path
     * bypasses it. FR-006's caps are a property of what gets stored, not of which screen
     * stored it.
     */
    private fun String.normalize(maxLength: Int): String? =
        trim().take(maxLength).takeIf { it.isNotEmpty() }

    companion object {
        /** FR-006: zone and spot are each capped at 40 characters. */
        private const val MAX_SHORT_FIELD = 40

        /** FR-006 does not size the memo; this is a storage bound, not a product rule. */
        private const val MAX_MEMO = 200

        fun factory(
            container: AppContainer,
            candidateId: String? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ManualParkingViewModel(
                        saveManualParking = SaveManualParkingUseCase(
                            repository = container.parkingRepository,
                            locationProvider = container.parkingLocationProvider,
                            clock = container.clock,
                            idGenerator = { UUID.randomUUID().toString() },
                            analytics = container.analyticsRecorder,
                        ),
                        candidateId = candidateId,
                        coordinator = container.parkingCandidateCoordinator,
                    ) as T
            }
    }
}

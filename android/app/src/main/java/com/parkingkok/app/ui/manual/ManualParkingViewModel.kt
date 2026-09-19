package com.parkingkok.app.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.core.Clock
import com.parkingkok.app.core.SystemClock
import com.parkingkok.app.detection.ConfirmCandidateResult
import com.parkingkok.app.detection.ConfirmedCandidateDetails
import com.parkingkok.app.detection.ParkingCandidateCoordinator
import com.parkingkok.app.detection.ParkingDetectionRuntime
import com.parkingkok.app.domain.detection.DetectionEvent
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.usecase.AttachParkingPhotoUseCase
import com.parkingkok.app.domain.parking.usecase.ManualParkingInput
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingResult
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingUseCase
import com.parkingkok.app.domain.photo.PhotoSource
import com.parkingkok.app.domain.photo.PillarSuggestion
import com.parkingkok.app.domain.photo.ReadPillarSuggestionUseCase
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
    /**
     * True once a pillar photo actually read something and the form was filled from it
     * (docs/02 §6a).
     *
     * It exists so the screen can put the cursor where the user now has to check a
     * machine's guess. It stays false when the read found nothing, which is the ordinary
     * case — §6a: "the form opens exactly as it does today, empty. No message, no spinner
     * left behind, no 인식 실패 dialog".
     */
    val pillarSuggestionOffered: Boolean = false,
)

/**
 * The photo `사진으로 입력` took, and the two things the form does with it
 * (docs/10 §7a, docs/02 §6a).
 *
 * One object rather than three nullable constructor parameters, because they are one
 * decision: either this form was reached through the camera, or it was not.
 */
class PillarPhotoEntry(
    private val photo: PhotoSource,
    private val readSuggestion: ReadPillarSuggestionUseCase,
    private val attachPhoto: AttachParkingPhotoUseCase,
) {

    suspend fun read(): PillarSuggestion = readSuggestion(photo)

    /**
     * Keeps the photo on the record it just became (docs/02 §6a).
     *
     * Not read and thrown away: it is the pillar photo the user would otherwise have to
     * take a second time from the detail screen. A failure here is silent for the same
     * reason FR-007 makes a photo optional — the record is already written and is
     * complete without one.
     */
    suspend fun attachTo(recordId: String) {
        attachPhoto(recordId, photo)
    }
}

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
    /**
     * Where a `직접 입력` confirmation reaches the §3a state machine.
     *
     * The same answer as the one-tap floor chip on the confirmation screen, so it has to
     * move the machine the same way: without it the record exists and the engine sits in
     * `CANDIDATE_PENDING` until the 45 minutes run out, and §12's one-candidate rule keeps
     * the next trip silent for that whole time. Null on the FR-001 manual path, which is
     * not a candidate and has no state machine to move.
     */
    private val detectionRuntime: ParkingDetectionRuntime? = null,
    /**
     * Set when `사진으로 입력` brought the user here; null on every other arrival.
     *
     * The suggestion is read here rather than on the screen that took the photo because
     * this is where the fields it fills live — §6a is explicit that the reading produces
     * "a suggestion, never a saved value", and a value that only exists in an editable
     * form the user still has to submit is the strongest way to say that.
     */
    private val pillarPhoto: PillarPhotoEntry? = null,
    private val clock: Clock = SystemClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualParkingUiState())
    val uiState: StateFlow<ManualParkingUiState> = _uiState.asStateFlow()

    init {
        if (pillarPhoto != null) viewModelScope.launch { prefillFromPillar(pillarPhoto) }
    }

    /**
     * Fills the blanks the pillar answered, and nothing else.
     *
     * Blanks only, because the read is asynchronous and the user may already have started
     * typing: overwriting what a person wrote with what a camera guessed is the one thing
     * §6a's "never auto-saved" is protecting against, a keystroke earlier.
     *
     * Nothing is reported when it reads nothing. There is no spinner to clear because
     * none was shown, and the form the user sees is the one they would have seen anyway.
     */
    private suspend fun prefillFromPillar(entry: PillarPhotoEntry) {
        val suggestion = entry.read()
        if (suggestion.isEmpty) return
        _uiState.update {
            it.copy(
                floorRaw = it.floorRaw.ifEmpty { suggestion.floorRaw.orEmpty() },
                zone = it.zone.ifEmpty { suggestion.zone.orEmpty() },
                spot = it.spot.ifEmpty { suggestion.spot.orEmpty() },
                pillarSuggestionOffered = true,
            )
        }
    }

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
        // Only a write that happened moves the machine: `Gone` and `AlreadyActive` left
        // the candidate exactly where it was.
        if (result is ConfirmCandidateResult.Confirmed) {
            detectionRuntime?.handleUserAnswer(DetectionEvent.UserConfirmedParking(clock.nowEpochMillis()))
            pillarPhoto?.attachTo(result.record.id)
        }
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

    private suspend fun applySaveResult(result: SaveManualParkingResult) {
        if (result is SaveManualParkingResult.Saved) pillarPhoto?.attachTo(result.record.id)
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
            /** True when `사진으로 입력` took a pillar photo on the way here (docs/10 §7a). */
            fromPillarPhoto: Boolean = false,
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
                        detectionRuntime = container.parkingDetectionRuntime,
                        pillarPhoto = if (fromPillarPhoto) container.pillarPhotoEntry() else null,
                        clock = container.clock,
                    ) as T
            }
    }
}

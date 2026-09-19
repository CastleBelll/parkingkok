package com.parkingkok.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.detection.ParkingCandidate
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.usecase.AdjustParkingFloorUseCase
import com.parkingkok.app.domain.parking.usecase.ApplyPillarSuggestionUseCase
import com.parkingkok.app.domain.parking.usecase.AttachParkingPhotoResult
import com.parkingkok.app.domain.parking.usecase.AttachParkingPhotoUseCase
import com.parkingkok.app.domain.parking.usecase.EndParkingUseCase
import com.parkingkok.app.domain.parking.usecase.ObserveActiveParkingUseCase
import com.parkingkok.app.domain.parking.usecase.ObserveParkingHistoryUseCase
import com.parkingkok.app.domain.parking.usecase.SuggestFromPillarPhotoUseCase
import com.parkingkok.app.domain.photo.PhotoSaveResult
import com.parkingkok.app.domain.photo.PhotoSource
import com.parkingkok.app.domain.photo.PillarSuggestion
import com.parkingkok.app.domain.photo.ReadPillarSuggestionUseCase
import com.parkingkok.app.map.MapOpenResult
import com.parkingkok.app.ui.UiNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The transient bits of screen state the ViewModel owns itself. */
private data class HomeChrome(
    val notice: UiNotice?,
    val photoBusy: Boolean,
    /** What the last attached photo read, until it is applied or waved away (docs/02 §6a). */
    val pillarSuggestion: PillarSuggestion?,
)

/** What `01-home-main.png` renders. */
data class HomeUiState(
    val active: ParkingRecord? = null,
    val recent: List<ParkingRecord> = emptyList(),
    val nowMillis: Long = 0L,
    /**
     * False until the first read comes back.
     *
     * Until then the screen shows neither state. Defaulting to the empty state would flash
     * "지금은 주차 중이 아니에요" at a user who is in fact parked, and docs/10_DESIGN_UX_SPEC.md §13
     * rules out covering that with a global spinner.
     */
    val loaded: Boolean = false,
    /** True while a chosen photo is being downsampled and written (FR-007). */
    val photoBusy: Boolean = false,
    val notice: UiNotice? = null,
    /**
     * The candidate waiting for an answer, or null.
     *
     * docs/05 §10a: "denied, the candidate is saved and surfaces in the app on next
     * launch". Home is where that promise is kept — a candidate that only existed in a
     * notification would be lost for every user who turned notifications off.
     *
     * The id and the time, and nothing else. This row must not state a floor, an address
     * or a coordinate any more than the notification may (docs/09 §9).
     */
    val pendingCandidateId: String? = null,
    val pendingCandidateAtMillis: Long? = null,
    /**
     * What a pillar photo read for the fields this record left empty, or null.
     *
     * docs/02 §6a: home has no floor or zone form for a suggestion to land in, so it is
     * offered as a card and written only when the user taps it. Null is the ordinary
     * state, including every read that found nothing.
     */
    val pillarSuggestion: PillarSuggestion? = null,
) {
    /** FR-008: with no stored coordinate there is nowhere to send a maps app. */
    val canOpenMap: Boolean get() = active?.location != null

    val hasPhoto: Boolean get() = active?.photoRelativePath != null
}

/**
 * Drives the home screen.
 *
 * Holds no rules of its own — it observes use cases and forwards intents to them
 * (docs/03_SYSTEM_ARCHITECTURE.md §5).
 */
class HomeViewModel(
    observeActive: ObserveActiveParkingUseCase,
    observeHistory: ObserveParkingHistoryUseCase,
    private val observePendingCandidate: () -> Flow<ParkingCandidate?>,
    private val endParking: EndParkingUseCase,
    private val adjustParkingFloor: AdjustParkingFloorUseCase,
    private val attachPhoto: AttachParkingPhotoUseCase,
    /** docs/02 §6a, the home and detail half: offered, never written (see [onPhotoSelected]). */
    private val suggestFromPillarPhoto: SuggestFromPillarPhotoUseCase,
    private val applyPillarSuggestion: ApplyPillarSuggestionUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val notice = MutableStateFlow<UiNotice?>(null)
    private val photoBusy = MutableStateFlow(false)
    private val pillarSuggestion = MutableStateFlow<PillarSuggestion?>(null)

    val uiState: StateFlow<HomeUiState> =
        combine(
            observeActive(),
            observeHistory(limit = ObserveParkingHistoryUseCase.HOME_PREVIEW),
            minuteTicker(),
            // Paired so the combine stays on the five-argument typed overload. A sixth
            // source would fall onto the `Array<*>` one, where every field becomes an
            // unchecked cast and the compiler stops catching a reordered argument.
            combine(notice, photoBusy, pillarSuggestion, ::HomeChrome),
            observePendingCandidate(),
        ) { active, recent, nowMillis, chrome, candidate ->
            HomeUiState(
                active = active,
                recent = recent,
                nowMillis = nowMillis,
                loaded = true,
                photoBusy = chrome.photoBusy,
                notice = chrome.notice,
                pillarSuggestion = chrome.pillarSuggestion,
                pendingCandidateId = candidate?.id,
                pendingCandidateAtMillis = candidate?.parkedAtMillis,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = HomeUiState(),
        )

    fun onEndParking() {
        viewModelScope.launch { endParking() }
    }

    fun onStepFloor(delta: Int) {
        viewModelScope.launch { adjustParkingFloor(delta) }
    }

    /**
     * FR-007 from the home card's `사진 추가` row.
     *
     * The record id is read at the moment of the save rather than captured when the picker
     * opened, so a parking ended while the album was in front of the user attaches nothing
     * instead of attaching to a record the user has moved on from.
     */
    fun onPhotoSelected(source: PhotoSource) {
        val recordId = uiState.value.active?.id ?: return
        viewModelScope.launch {
            photoBusy.value = true
            val result = attachPhoto(recordId, source)
            notice.value = when (result) {
                is AttachParkingPhotoResult.Failed -> result.reason.toNotice()
                AttachParkingPhotoResult.RecordGone -> null
                is AttachParkingPhotoResult.Attached -> null
            }
            photoBusy.value = false
            // Only a photo that was actually kept is worth reading: §6a's whole premise
            // is "the photo the user takes anyway".
            if (result is AttachParkingPhotoResult.Attached) {
                pillarSuggestion.value =
                    suggestFromPillarPhoto(recordId, source).takeUnless { it.isEmpty }
            }
        }
    }

    /** §6a: the record changes here and nowhere earlier. */
    fun onApplyPillarSuggestion() {
        val suggestion = pillarSuggestion.value ?: return
        val recordId = uiState.value.active?.id ?: return
        pillarSuggestion.value = null
        viewModelScope.launch { applyPillarSuggestion(recordId, suggestion) }
    }

    fun onDismissPillarSuggestion() {
        pillarSuggestion.value = null
    }

    fun onCameraUnavailable() {
        notice.value = UiNotice.CAMERA_UNAVAILABLE
    }

    /** Reported by the shell, which owns the Activity the maps intent starts from. */
    fun onMapOpened(result: MapOpenResult) {
        notice.value = when (result) {
            MapOpenResult.NO_MAPS_APP -> UiNotice.NO_MAPS_APP
            MapOpenResult.NO_LOCATION, MapOpenResult.OPENED -> null
        }
    }

    fun onNoticeShown() {
        notice.value = null
    }

    private fun PhotoSaveResult.Failed.Reason.toNotice(): UiNotice = when (this) {
        PhotoSaveResult.Failed.Reason.UNREADABLE -> UiNotice.PHOTO_UNREADABLE
        PhotoSaveResult.Failed.Reason.STORAGE -> UiNotice.PHOTO_NOT_SAVED
    }

    /**
     * Emits the current time on every minute boundary, so `1시간 24분째 주차 중` advances while
     * the screen is open.
     *
     * It sleeps to the next boundary rather than polling on a fixed period, so the label
     * changes when the minute changes instead of up to 59 seconds late. `WhileSubscribed`
     * stops it as soon as the screen goes away, which is what keeps this off the battery
     * budget the detection engine is measured against.
     */
    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            val now = clock.nowEpochMillis()
            emit(now)
            delay(MILLIS_PER_MINUTE - Math.floorMod(now, MILLIS_PER_MINUTE))
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val MILLIS_PER_MINUTE = 60_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                    observeActive = ObserveActiveParkingUseCase(container.parkingRepository),
                    observeHistory = ObserveParkingHistoryUseCase(container.parkingRepository),
                    observePendingCandidate = container.parkingCandidateCoordinator::observePending,
                    endParking = EndParkingUseCase(container.parkingRepository, container.clock),
                    adjustParkingFloor = AdjustParkingFloorUseCase(
                        container.parkingRepository,
                        container.clock,
                    ),
                    attachPhoto = AttachParkingPhotoUseCase(
                        container.parkingRepository,
                        container.parkingPhotoStore,
                        container.clock,
                    ),
                    suggestFromPillarPhoto = SuggestFromPillarPhotoUseCase(
                        container.parkingRepository,
                        ReadPillarSuggestionUseCase(container.pillarTextReader),
                    ),
                    applyPillarSuggestion = ApplyPillarSuggestionUseCase(
                        container.parkingRepository,
                        container.clock,
                    ),
                    clock = container.clock,
                ) as T
            }
    }
}

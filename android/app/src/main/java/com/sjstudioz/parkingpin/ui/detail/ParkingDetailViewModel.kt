package com.sjstudioz.parkingpin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjstudioz.parkingpin.AppContainer
import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.data.photo.ParkingPhotoImage
import com.sjstudioz.parkingpin.data.photo.ParkingPhotoImageLoader
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.usecase.ApplyPillarSuggestionUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoResult
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.DeleteParkingRecordUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ObserveParkingRecordUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.RemoveParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SuggestFromPillarPhotoUseCase
import com.sjstudioz.parkingpin.domain.photo.PhotoSaveResult
import com.sjstudioz.parkingpin.domain.photo.PhotoSource
import com.sjstudioz.parkingpin.domain.photo.PillarSuggestion
import com.sjstudioz.parkingpin.domain.photo.ReadPillarSuggestionUseCase
import com.sjstudioz.parkingpin.map.MapOpenResult
import com.sjstudioz.parkingpin.ui.UiNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What `03-parking-detail.png` renders. */
data class ParkingDetailUiState(
    val record: ParkingRecord? = null,
    /**
     * The decoded photo, or null while it loads and when there is none.
     *
     * Held here rather than decoded at the draw site so no bitmap work happens on the
     * main thread, and so the screen has nothing to decide.
     */
    val photo: ParkingPhotoImage? = null,
    val nowMillis: Long = 0L,
    val loaded: Boolean = false,
    val photoBusy: Boolean = false,
    val notice: UiNotice? = null,
    /**
     * What a pillar photo read for the fields this record left empty, or null.
     *
     * The same offer home makes and for the same reason (docs/02 §6a): detail has no
     * editable floor or zone field either, so the read is shown and written on a tap.
     */
    val pillarSuggestion: PillarSuggestion? = null,
) {
    /** True once the record is known to be gone — deleted here, or from another screen. */
    val missing: Boolean get() = loaded && record == null

    /** FR-008: with no stored coordinate there is nowhere to send a maps app. */
    val canOpenMap: Boolean get() = record?.location != null

    val hasPhoto: Boolean get() = record?.photoRelativePath != null
}

/** Drives the parking detail screen. */
class ParkingDetailViewModel(
    private val recordId: String,
    observeRecord: ObserveParkingRecordUseCase,
    photoLoader: ParkingPhotoImageLoader,
    private val endParking: EndParkingUseCase,
    private val deleteRecord: DeleteParkingRecordUseCase,
    private val attachPhoto: AttachParkingPhotoUseCase,
    private val removePhoto: RemoveParkingPhotoUseCase,
    /** docs/02 §6a: offered for the blanks, written only on a tap. */
    private val suggestFromPillarPhoto: SuggestFromPillarPhotoUseCase,
    private val applyPillarSuggestion: ApplyPillarSuggestionUseCase,
    clock: Clock,
) : ViewModel() {

    private val notice = MutableStateFlow<UiNotice?>(null)
    private val photoBusy = MutableStateFlow(false)
    private val pillarSuggestion = MutableStateFlow<PillarSuggestion?>(null)

    private val record: Flow<ParkingRecord?> = observeRecord(recordId)

    /**
     * Re-decoded only when the stored path changes, not on every edit: stepping the floor
     * emits a new record, and re-reading a 1600px JPEG for that would be a decode per tap.
     */
    private val photo: Flow<ParkingPhotoImage?> = record
        .map { it?.photoRelativePath }
        .distinctUntilChanged()
        .map { photoLoader.load(it) }

    val uiState: StateFlow<ParkingDetailUiState> =
        combine(record, photo, notice, photoBusy, pillarSuggestion) {
                record, photo, notice, busy, suggestion ->
            ParkingDetailUiState(
                record = record,
                photo = photo,
                nowMillis = clock.nowEpochMillis(),
                loaded = true,
                photoBusy = busy,
                notice = notice,
                pillarSuggestion = suggestion,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ParkingDetailUiState(),
        )

    fun onEndParking() {
        viewModelScope.launch { endParking() }
    }

    fun onDelete() {
        viewModelScope.launch { deleteRecord(recordId) }
    }

    /** FR-007: downsample and store what the picker or the camera returned. */
    fun onPhotoSelected(source: PhotoSource) {
        viewModelScope.launch {
            photoBusy.value = true
            val result = attachPhoto(recordId, source)
            notice.value = when (result) {
                is AttachParkingPhotoResult.Failed -> result.reason.toNotice()
                // The record vanished mid-pick; the screen is already closing itself.
                AttachParkingPhotoResult.RecordGone -> null
                is AttachParkingPhotoResult.Attached -> null
            }
            photoBusy.value = false
            if (result is AttachParkingPhotoResult.Attached) {
                pillarSuggestion.value =
                    suggestFromPillarPhoto(recordId, source).takeUnless { it.isEmpty }
            }
        }
    }

    /** §6a: the record changes here and nowhere earlier. */
    fun onApplyPillarSuggestion() {
        val suggestion = pillarSuggestion.value ?: return
        pillarSuggestion.value = null
        viewModelScope.launch { applyPillarSuggestion(recordId, suggestion) }
    }

    fun onDismissPillarSuggestion() {
        pillarSuggestion.value = null
    }

    fun onRemovePhoto() {
        viewModelScope.launch { removePhoto(recordId) }
    }

    fun onCameraUnavailable() {
        notice.value = UiNotice.CAMERA_UNAVAILABLE
    }

    /** Reported by the shell, which is what owns the Activity the intent starts from. */
    fun onMapOpened(result: MapOpenResult) {
        notice.value = when (result) {
            MapOpenResult.NO_MAPS_APP -> UiNotice.NO_MAPS_APP
            // NO_LOCATION cannot be reached from a screen that disables the action, and
            // if it ever is, saying nothing beats an error about a normal record.
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

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer, recordId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ParkingDetailViewModel(
                        recordId = recordId,
                        observeRecord = ObserveParkingRecordUseCase(container.parkingRepository),
                        photoLoader = container.parkingPhotoImageLoader,
                        endParking = EndParkingUseCase(
                            container.parkingRepository,
                            container.clock,
                        ),
                        deleteRecord = DeleteParkingRecordUseCase(
                            container.parkingRepository,
                            container.parkingPhotoStore,
                        ),
                        attachPhoto = AttachParkingPhotoUseCase(
                            container.parkingRepository,
                            container.parkingPhotoStore,
                            container.clock,
                        ),
                        removePhoto = RemoveParkingPhotoUseCase(
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

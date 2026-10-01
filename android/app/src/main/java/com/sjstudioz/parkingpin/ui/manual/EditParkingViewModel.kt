package com.sjstudioz.parkingpin.ui.manual

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sjstudioz.parkingpin.AppContainer
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import com.sjstudioz.parkingpin.domain.parking.usecase.EditParkingDetailsUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `수정` on the detail screen, drawing the same form as a manual save
 * ([ManualParkingScreen]) filled with the record as it stands.
 */
class EditParkingViewModel(
    private val recordId: String,
    private val repository: ParkingRepository,
    private val editDetails: EditParkingDetailsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualParkingUiState(editing = true))
    val uiState: StateFlow<ManualParkingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val record = repository.find(recordId)
            if (record == null) {
                // Deleted meanwhile: there is nothing to edit, so the form leaves.
                _uiState.update { it.copy(candidateGone = true) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    floorRaw = record.floor?.raw.orEmpty(),
                    zone = record.zone.orEmpty(),
                    spot = record.spot.orEmpty(),
                    memo = record.memo.orEmpty(),
                )
            }
        }
    }

    fun onFloorChange(value: String) = _uiState.update { it.copy(floorRaw = value) }

    fun onZoneChange(value: String) = _uiState.update { it.copy(zone = value) }

    fun onSpotChange(value: String) = _uiState.update { it.copy(spot = value) }

    fun onMemoChange(value: String) = _uiState.update { it.copy(memo = value) }

    fun onSave() {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val state = _uiState.value
            try {
                val saved = editDetails(
                    recordId,
                    ManualParkingInput(floorRaw = state.floorRaw, zone = state.zone, spot = state.spot, memo = state.memo),
                )
                _uiState.update {
                    if (saved == null) it.copy(saving = false, candidateGone = true) else it.copy(saving = false, savedRecordId = saved.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // By type only: the message may quote the row, which holds the location.
                Log.w(TAG, "edit failed: ${failure.javaClass.simpleName}")
                _uiState.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    companion object {
        private const val TAG = "EditParking"

        fun factory(container: AppContainer, recordId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EditParkingViewModel(
                        recordId = recordId,
                        repository = container.parkingRepository,
                        editDetails = EditParkingDetailsUseCase(container.parkingRepository, container.clock),
                    ) as T
            }
    }
}

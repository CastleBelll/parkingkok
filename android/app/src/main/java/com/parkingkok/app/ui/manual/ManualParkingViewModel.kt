package com.parkingkok.app.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
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
)

/**
 * Drives the manual entry form.
 *
 * This screen asks for no permission and reads no sensor. That is the point: FR-001 says
 * a manual save must work with everything denied, and the way to guarantee it is for this
 * path not to depend on any of it.
 */
class ManualParkingViewModel(
    private val saveManualParking: SaveManualParkingUseCase,
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
            val result = saveManualParking(
                ManualParkingInput(
                    floorRaw = state.floorRaw,
                    zone = state.zone,
                    spot = state.spot,
                    memo = state.memo,
                ),
            )
            _uiState.update {
                when (result) {
                    is SaveManualParkingResult.Saved ->
                        it.copy(saving = false, savedRecordId = result.record.id)
                    is SaveManualParkingResult.AlreadyActive ->
                        it.copy(saving = false, alreadyActive = true)
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ManualParkingViewModel(
                        SaveManualParkingUseCase(
                            repository = container.parkingRepository,
                            locationProvider = container.parkingLocationProvider,
                            clock = container.clock,
                            idGenerator = { UUID.randomUUID().toString() },
                        ),
                    ) as T
            }
    }
}

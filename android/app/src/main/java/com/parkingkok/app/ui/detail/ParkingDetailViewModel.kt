package com.parkingkok.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.usecase.DeleteParkingRecordUseCase
import com.parkingkok.app.domain.parking.usecase.EndParkingUseCase
import com.parkingkok.app.domain.parking.usecase.ObserveParkingRecordUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What `03-parking-detail.png` renders. */
data class ParkingDetailUiState(
    val record: ParkingRecord? = null,
    val nowMillis: Long = 0L,
    val loaded: Boolean = false,
) {
    /** True once the record is known to be gone — deleted here, or from another screen. */
    val missing: Boolean get() = loaded && record == null
}

/** Drives the parking detail screen. */
class ParkingDetailViewModel(
    private val recordId: String,
    observeRecord: ObserveParkingRecordUseCase,
    private val endParking: EndParkingUseCase,
    private val deleteRecord: DeleteParkingRecordUseCase,
    clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<ParkingDetailUiState> =
        observeRecord(recordId)
            .map { ParkingDetailUiState(it, clock.nowEpochMillis(), loaded = true) }
            .stateIn(
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

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer, recordId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ParkingDetailViewModel(
                        recordId = recordId,
                        observeRecord = ObserveParkingRecordUseCase(container.parkingRepository),
                        endParking = EndParkingUseCase(
                            container.parkingRepository,
                            container.clock,
                        ),
                        deleteRecord = DeleteParkingRecordUseCase(container.parkingRepository),
                        clock = container.clock,
                    ) as T
            }
    }
}

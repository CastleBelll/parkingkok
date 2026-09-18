package com.parkingkok.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.usecase.DeleteParkingHistoryUseCase
import com.parkingkok.app.domain.parking.usecase.ObserveParkingHistoryUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What `04-history-list.png` renders. */
data class HistoryUiState(
    val records: List<ParkingRecord> = emptyList(),
    val nowMillis: Long = 0L,
    val loaded: Boolean = false,
)

/**
 * Drives the history list.
 *
 * Every completed record is listed. FR-009's free/Plus split is deliberately not applied:
 * this build has no subscription, so capping the list at five would hide the user's own
 * data behind a paywall that does not exist — docs/06 §9 is explicit that the data stays
 * and stays theirs.
 */
class HistoryViewModel(
    observeHistory: ObserveParkingHistoryUseCase,
    private val deleteHistory: DeleteParkingHistoryUseCase,
    clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> =
        observeHistory()
            .map { records ->
                HistoryUiState(
                    records = records,
                    nowMillis = clock.nowEpochMillis(),
                    loaded = true,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = HistoryUiState(),
            )

    fun onDeleteAll() {
        viewModelScope.launch { deleteHistory() }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(
                    observeHistory = ObserveParkingHistoryUseCase(container.parkingRepository),
                    deleteHistory = DeleteParkingHistoryUseCase(container.parkingRepository),
                    clock = container.clock,
                ) as T
            }
    }
}

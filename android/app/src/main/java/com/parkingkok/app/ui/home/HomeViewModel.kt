package com.parkingkok.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.parkingkok.app.AppContainer
import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.usecase.AdjustParkingFloorUseCase
import com.parkingkok.app.domain.parking.usecase.EndParkingUseCase
import com.parkingkok.app.domain.parking.usecase.ObserveActiveParkingUseCase
import com.parkingkok.app.domain.parking.usecase.ObserveParkingHistoryUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
)

/**
 * Drives the home screen.
 *
 * Holds no rules of its own — it observes use cases and forwards intents to them
 * (docs/03_SYSTEM_ARCHITECTURE.md §5).
 */
class HomeViewModel(
    observeActive: ObserveActiveParkingUseCase,
    observeHistory: ObserveParkingHistoryUseCase,
    private val endParking: EndParkingUseCase,
    private val adjustParkingFloor: AdjustParkingFloorUseCase,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(
            observeActive(),
            observeHistory(limit = ObserveParkingHistoryUseCase.HOME_PREVIEW),
            minuteTicker(),
        ) { active, recent, nowMillis ->
            HomeUiState(active = active, recent = recent, nowMillis = nowMillis, loaded = true)
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
                    endParking = EndParkingUseCase(container.parkingRepository, container.clock),
                    adjustParkingFloor = AdjustParkingFloorUseCase(
                        container.parkingRepository,
                        container.clock,
                    ),
                    clock = container.clock,
                ) as T
            }
    }
}

package com.parkingkok.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.parkingkok.app.AppContainer
import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.detection.CandidateHistoryEntry
import com.parkingkok.app.domain.detection.CandidateOutcome
import com.parkingkok.app.domain.detection.ParkingCandidate
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.usecase.ObserveActiveParkingUseCase
import com.parkingkok.app.domain.parking.usecase.ObserveParkingHistoryUseCase
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * One line of §7b's list, already resolved into what the row draws.
 *
 * The ViewModel does the joining so the screen holds no rule about what a candidate is.
 * There is no location field here either — the entry has none to give it, and the record
 * it reads the place from is asked only for its floor, zone and spot (docs/09 §9).
 */
data class NotificationRow(
    val candidateId: String,
    val raisedAtMillis: Long,
    /** Null while the candidate is still waiting for an answer. */
    val outcome: CandidateOutcome?,
    /** `B3 · A구역 142`, for a confirmed row whose record is still there. */
    val place: String? = null,
    /** The record a tap opens, or null when there is nothing left to open. */
    val openRecordId: String? = null,
) {
    /**
     * §7b: a rejected or expired row "does nothing — it is history, and there is nothing
     * left to act on."
     *
     * A confirmed row whose record the user has since deleted falls in the same bucket:
     * the thing it would open is gone. The chevron is a narrower question and belongs to
     * the pending row alone (§7b "The pending row"), so the screen asks [outcome] for it
     * rather than this.
     */
    val tappable: Boolean get() = outcome == null || openRecordId != null
}

/** What the bell's screen renders (docs/10_DESIGN_UX_SPEC.md §7b). */
data class NotificationHistoryUiState(
    val rows: List<NotificationRow> = emptyList(),
    val nowMillis: Long = 0L,
    val loaded: Boolean = false,
)

/**
 * Drives the notification history.
 *
 * ### Why it reads parking records at all
 * §7b's confirmed row names the floor the candidate became, but §10a forbids the history
 * entry from storing it — an entry keeps the time, the outcome and the record id, and
 * nothing else. So the place is read back from the record at display time, which also
 * means a floor the user corrected afterwards is the floor this screen shows.
 *
 * The two record flows are combined into one map rather than looked up per row: thirty
 * rows would otherwise be thirty queries.
 */
class NotificationHistoryViewModel(
    observePending: () -> Flow<ParkingCandidate?>,
    observeHistory: () -> Flow<List<CandidateHistoryEntry>>,
    observeActive: ObserveActiveParkingUseCase,
    observeRecords: ObserveParkingHistoryUseCase,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<NotificationHistoryUiState> =
        combine(
            observePending(),
            observeHistory(),
            observeActive(),
            observeRecords(),
        ) { pending, history, active, completed ->
            val records = (completed + listOfNotNull(active)).associateBy { it.id }
            NotificationHistoryUiState(
                // Newest first (§7b), and the unanswered one is always the newest there
                // is: §12 allows one at a time and it has not been resolved yet.
                rows = listOfNotNull(pending?.toRow()) + history.reversed().map { it.toRow(records) },
                nowMillis = clock.nowEpochMillis(),
                loaded = true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = NotificationHistoryUiState(),
        )

    private fun ParkingCandidate.toRow(): NotificationRow = NotificationRow(
        candidateId = id,
        raisedAtMillis = detectedAtMillis,
        outcome = null,
    )

    private fun CandidateHistoryEntry.toRow(records: Map<String, ParkingRecord>): NotificationRow {
        val record = recordId?.let(records::get)
        return NotificationRow(
            candidateId = candidateId,
            raisedAtMillis = raisedAtMillis,
            outcome = outcome,
            place = record?.placeLabel(),
            openRecordId = record?.id,
        )
    }

    /**
     * `B3 · A구역 142` — the floor, then the place on it, exactly as §7b writes it.
     *
     * The bay number belongs to the zone, so the two are one phrase and the separator
     * falls between the floor and them. Never more than these three fields.
     */
    private fun ParkingRecord.placeLabel(): String? {
        val within = listOfNotNull(zone, spot).joinToString(BAY_SEPARATOR).takeIf { it.isNotEmpty() }
        return listOfNotNull(floor?.displayLabel, within)
            .joinToString(PLACE_SEPARATOR)
            .takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private const val PLACE_SEPARATOR = " · "

        private const val BAY_SEPARATOR = " "

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NotificationHistoryViewModel(
                        observePending = container.parkingCandidateCoordinator::observePending,
                        observeHistory = container.parkingCandidateCoordinator::observeHistory,
                        observeActive = ObserveActiveParkingUseCase(container.parkingRepository),
                        observeRecords = ObserveParkingHistoryUseCase(container.parkingRepository),
                        clock = container.clock,
                    ) as T
            }
    }
}

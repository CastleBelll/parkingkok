package kr.parkingpin.app.ui.confirm

import kr.parkingpin.app.analytics.DetectionProperties
import kr.parkingpin.app.analytics.RecordingAnalytics
import kr.parkingpin.app.data.DetectionStateStore
import kr.parkingpin.app.data.InMemoryPreferencesDataStore
import kr.parkingpin.app.data.parking.ParkingDatabase
import kr.parkingpin.app.data.parking.RoomParkingRepository
import kr.parkingpin.app.data.parking.createTestParkingDatabase
import kr.parkingpin.app.detection.FakeCandidateNotifier
import kr.parkingpin.app.detection.MutableTestClock
import kr.parkingpin.app.detection.ConfirmCandidateResult
import kr.parkingpin.app.detection.ConfirmedCandidateDetails
import kr.parkingpin.app.detection.ParkingCandidateCoordinator
import kr.parkingpin.app.domain.detection.ParkingCandidate
import kr.parkingpin.app.domain.parking.ConfidenceBucket
import kr.parkingpin.app.domain.parking.FloorParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The confirmation screen's behaviour, above the coordinator and below Compose
 * (docs/10_DESIGN_UX_SPEC.md §7a).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmCandidateViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: DetectionStateStore
    private lateinit var notifier: FakeCandidateNotifier
    private lateinit var coordinator: ParkingCandidateCoordinator

    private val start = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = start)
    private var nextId = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        notifier = FakeCandidateNotifier()
        coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { repository },
            notifier = notifier,
            clock = clock,
            idGenerator = { "id-${nextId++}" },
            analytics = RecordingAnalytics(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `the screen shows when the car was left`() = runTest {
        val candidate = newCandidate()

        val state = viewModelFor(candidate).uiState.value

        assertEquals(candidate.parkedAtMillis, state.parkedAtMillis)
        assertTrue(state.loaded)
    }

    @Test
    fun `a drive that kept no fix says so rather than leaving the row out`() = runTest {
        val candidate = newCandidate()

        val state = viewModelFor(candidate).uiState.value

        // §7a "where": null is the ordinary underground outcome, and the screen renders
        // 위치 없음 from it. The row itself is never conditional.
        assertNull(state.location)
    }

    @Test
    fun `주차 아님 discards the candidate and closes the screen`() = runTest {
        val candidate = newCandidate()
        val viewModel = viewModelFor(candidate)

        viewModel.onReject()

        assertTrue(viewModel.awaitSettled().rejected)
        assertNull(store.readCandidateOnce())
        // §7a: "it never asks why", and nothing is written.
        assertNull(repository.findActive())
    }

    @Test
    fun `opening an expired notification lands on home rather than on an empty screen`() = runTest {
        val candidate = newCandidate()
        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS

        val state = viewModelFor(candidate).uiState.value

        // §10a: "a user who opens an expired notification lands on home; the app does not
        // apologise for it in a dialog".
        assertTrue(state.gone)
        assertNull(state.parkedAtMillis)
        // Nothing to open: the candidate lapsed rather than becoming a record.
        assertNull(state.openRecordId)
        assertNull(repository.findActive())
    }

    @Test
    fun `reopening a candidate that was already confirmed points at the record it became`() =
        runTest {
            val candidate = newCandidate()
            // The manual entry form is the only thing that confirms now, and this is the
            // call it makes. Driving it directly keeps this test about the screen's
            // reaction rather than about the form.
            val result = coordinator.confirm(
                candidate.id,
                ConfirmedCandidateDetails(floor = FloorParser.parse("B3")),
            )
            val recordId = (result as ConfirmCandidateResult.Confirmed).record.id

            // A second tap on the same notification, before the shade caught up.
            val state = viewModelFor(candidate).uiState.value

            assertTrue(state.gone)
            assertEquals(recordId, state.openRecordId)
        }

    /**
     * Builds the ViewModel and waits for its first read to land.
     *
     * The wait is on the state rather than on the test scheduler: the reads go through
     * Room, which runs on [Dispatchers.IO] by construction, so `advanceUntilIdle` would
     * return while the query was still in flight and every assertion would race it.
     */
    private suspend fun viewModelFor(candidate: ParkingCandidate): ConfirmCandidateViewModel {
        val viewModel = ConfirmCandidateViewModel(
            candidateId = candidate.id,
            coordinator = coordinator,
        )
        viewModel.uiState.first { it.loaded }
        return viewModel
    }

    /** Waits for the one state change a press produces, for the same reason. */
    private suspend fun ConfirmCandidateViewModel.awaitSettled(): ConfirmCandidateUiState =
        uiState.first { it.gone || it.rejected }

    private suspend fun newCandidate(): ParkingCandidate = coordinator.create(
        evidence = DetectionProperties(
            confidenceBucket = ConfidenceBucket.HIGH,
            walkingEvidence = true,
            gpsDegradation = false,
            optionalVehicleSignal = false,
        ),
        lastReliableLocation = null,
    )
}

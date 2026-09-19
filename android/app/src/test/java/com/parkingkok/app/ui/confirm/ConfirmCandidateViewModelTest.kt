package com.parkingkok.app.ui.confirm

import com.parkingkok.app.analytics.DetectionProperties
import com.parkingkok.app.analytics.RecordingAnalytics
import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.data.InMemoryPreferencesDataStore
import com.parkingkok.app.data.parking.ParkingDatabase
import com.parkingkok.app.data.parking.RoomParkingRepository
import com.parkingkok.app.data.parking.createTestParkingDatabase
import com.parkingkok.app.detection.FakeCandidateNotifier
import com.parkingkok.app.detection.MutableTestClock
import com.parkingkok.app.detection.ParkingCandidateCoordinator
import com.parkingkok.app.domain.detection.ParkingCandidate
import com.parkingkok.app.domain.parking.ConfidenceBucket
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingSource
import com.parkingkok.app.domain.parking.usecase.EndParkingUseCase
import com.parkingkok.app.domain.parking.usecase.ManualParkingInput
import com.parkingkok.app.domain.parking.usecase.RecentFloorPicksUseCase
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingUseCase
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
    fun `the screen shows when the car was left and the floors this user has saved`() = runTest {
        saveAndEnd("B3")
        saveAndEnd("2F")
        val candidate = newCandidate()

        val state = viewModelFor(candidate).uiState.value

        assertEquals(candidate.parkedAtMillis, state.parkedAtMillis)
        assertEquals(listOf("2F", "B3"), state.floorPicks.map { it.displayLabel })
        assertTrue(state.loaded)
    }

    @Test
    fun `a first-ever run offers no picks and leaves only 직접 입력`() = runTest {
        val candidate = newCandidate()

        val state = viewModelFor(candidate).uiState.value

        assertEquals(emptyList<String>(), state.floorPicks.map { it.displayLabel })
    }

    @Test
    fun `picking a floor confirms in one tap`() = runTest {
        saveAndEnd("B3")
        val candidate = newCandidate()
        val viewModel = viewModelFor(candidate)

        viewModel.onPickFloor(FloorParser.parse("B3")!!)
        val settled = viewModel.awaitSettled()

        val record = repository.findActive()
        assertEquals(ParkingSource.DETECTED, record?.source)
        assertEquals("B3", record?.floor?.displayLabel)
        assertEquals(record?.id, settled.confirmedRecordId)
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
            val first = viewModelFor(candidate)
            first.onPickFloor(FloorParser.parse("B3")!!)
            val recordId = first.awaitSettled().confirmedRecordId

            // A second tap on the same notification, before the shade caught up.
            val state = viewModelFor(candidate).uiState.value

            assertTrue(state.gone)
            assertEquals(recordId, state.openRecordId)
        }

    @Test
    fun `confirming while a session is already open explains instead of writing a second one`() =
        runTest {
            saveAndEnd("B1", end = false)
            val candidate = newCandidate()
            val viewModel = viewModelFor(candidate)

            viewModel.onPickFloor(FloorParser.parse("B3")!!)
            val settled = viewModel.awaitSettled()

            assertTrue(settled.alreadyActive)
            assertNull(settled.confirmedRecordId)
            assertEquals("B1", repository.findActive()?.floor?.displayLabel)
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
            recentFloorPicks = RecentFloorPicksUseCase(repository),
        )
        viewModel.uiState.first { it.loaded }
        return viewModel
    }

    /** Waits for the one state change a press produces, for the same reason. */
    private suspend fun ConfirmCandidateViewModel.awaitSettled(): ConfirmCandidateUiState =
        uiState.first { it.confirmedRecordId != null || it.alreadyActive || it.gone || it.rejected }

    private suspend fun newCandidate(): ParkingCandidate = coordinator.create(
        evidence = DetectionProperties(
            confidenceBucket = ConfidenceBucket.HIGH,
            walkingEvidence = true,
            gpsDegradation = false,
            optionalVehicleSignal = false,
        ),
        lastReliableLocation = null,
    )

    private suspend fun saveAndEnd(floorRaw: String, end: Boolean = true) {
        SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-${nextId++}" },
        )(ManualParkingInput(floorRaw = floorRaw))
        clock.epochMillis += 60_000L
        if (end) {
            EndParkingUseCase(repository, clock)()
            clock.epochMillis += 60_000L
        }
    }
}

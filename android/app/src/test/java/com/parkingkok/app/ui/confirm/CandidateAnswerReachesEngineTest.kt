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
import com.parkingkok.app.detection.ParkingDetectionRuntime
import com.parkingkok.app.domain.detection.DetectionState
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.MotionEventKind
import com.parkingkok.app.domain.detection.ParkingDetectionEngine
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.usecase.RecentFloorPicksUseCase
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingUseCase
import com.parkingkok.app.ui.manual.ManualParkingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Both ways a user can answer the prompt have to reach the §3a state machine.
 *
 * docs/10_DESIGN_UX_SPEC.md §7a gives the confirmation screen two ways to say yes — a
 * one-tap recent floor on [ConfirmCandidateViewModel], and `직접 입력` handing over to
 * [ManualParkingViewModel] — and `주차 아님` on either. An answer that writes the record but
 * never tells the engine leaves it in `CANDIDATE_PENDING` for the full 45 minutes, and §12's
 * one-candidate-per-session rule then keeps the *next* trip silent for that whole time. It
 * is invisible on the screen that did it, which is why it is pinned here rather than left
 * to the two ViewModels' own tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CandidateAnswerReachesEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: DetectionStateStore
    private lateinit var coordinator: ParkingCandidateCoordinator
    private lateinit var runtime: ParkingDetectionRuntime

    private val clock = MutableTestClock(epochMillis = START)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { repository },
            notifier = FakeCandidateNotifier(),
            clock = clock,
            idGenerator = { "unexpected-coordinator-id" },
            analytics = RecordingAnalytics(),
        )
        var nextId = 0
        runtime = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            engine = ParkingDetectionEngine { "engine-candidate-${nextId++}" },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `a one-tap floor on the confirmation screen parks the machine`() = runTest {
        val candidateId = detectParking()

        val viewModel = ConfirmCandidateViewModel(
            candidateId = candidateId,
            coordinator = coordinator,
            recentFloorPicks = RecentFloorPicksUseCase(repository),
            detectionRuntime = runtime,
            clock = clock,
        )
        viewModel.uiState.first { it.loaded }
        viewModel.onPickFloor(checkNotNull(FloorParser.parse("B3")))
        val settled = viewModel.uiState.first { it.confirmedRecordId != null || it.gone || it.alreadyActive }

        assertNotNull(settled.confirmedRecordId)
        assertEquals(DetectionState.PARKED, runtime.restore().state)
        assertNull(store.readCandidateOnce())
    }

    @Test
    fun `직접 입력 parks the machine too`() = runTest {
        val candidateId = detectParking()

        val viewModel = ManualParkingViewModel(
            saveManualParking = SaveManualParkingUseCase(
                repository = repository,
                locationProvider = { null },
                clock = clock,
                idGenerator = { UUID.randomUUID().toString() },
                analytics = RecordingAnalytics(),
            ),
            candidateId = candidateId,
            coordinator = coordinator,
            detectionRuntime = runtime,
            clock = clock,
        )
        viewModel.onFloorChange("B3")
        viewModel.onSave()
        val settled = viewModel.uiState.first { it.savedRecordId != null || it.candidateGone || it.alreadyActive }

        assertNotNull(settled.savedRecordId)
        assertEquals(
            "the long way round the same screen has to land in the same state",
            DetectionState.PARKED,
            runtime.restore().state,
        )
        assertEquals("B3", checkNotNull(repository.findActive()).floor?.displayLabel)
    }

    @Test
    fun `주차 아님 returns the machine to IDLE`() = runTest {
        val candidateId = detectParking()

        val viewModel = ConfirmCandidateViewModel(
            candidateId = candidateId,
            coordinator = coordinator,
            recentFloorPicks = RecentFloorPicksUseCase(repository),
            detectionRuntime = runtime,
            clock = clock,
        )
        viewModel.uiState.first { it.loaded }
        viewModel.onReject()
        viewModel.uiState.first { it.rejected }

        assertEquals(DetectionState.IDLE, runtime.restore().state)
        assertNull(store.readCandidateOnce())
    }

    /** The §3a motion path through the real engine, returning the candidate it minted. */
    private suspend fun detectParking(): String {
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        runtime.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + DRIVE_MILLIS))
        runtime.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + DRIVE_MILLIS + 20_000))
        return checkNotNull(store.readCandidateOnce()) { "the drive produced no candidate" }.id
    }

    private fun motion(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis)

    private companion object {
        const val START = 1_700_000_000_000L
        const val DRIVE_MILLIS = 420_000L
    }
}

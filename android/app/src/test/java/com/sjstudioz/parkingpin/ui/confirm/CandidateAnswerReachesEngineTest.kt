package com.sjstudioz.parkingpin.ui.confirm

import com.sjstudioz.parkingpin.analytics.DetectionProperties
import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.FakeCandidateNotifier
import com.sjstudioz.parkingpin.detection.FakeParkingEndProposalNotifier
import com.sjstudioz.parkingpin.detection.ParkingEndProposalCoordinator
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.detection.ParkingCandidateCoordinator
import com.sjstudioz.parkingpin.detection.ParkingDetectionRuntime
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.detection.ParkingEndProposal
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import com.sjstudioz.parkingpin.ui.manual.ManualParkingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Both ways a user can answer the prompt have to reach the §3a state machine.
 *
 * docs/10_DESIGN_UX_SPEC.md §7a gives the confirmation screen two ways to say yes — a
 * one-tap recent floor on [ConfirmCandidateViewModel], and `직접 입력` handing over to
 * [ManualParkingViewModel], which is now the only thing that confirms — and `주차 아님` on
 * the confirmation screen. An answer that writes the record but
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

    /** How often the runtime reached the location capture — only a hand save does (§11c). */
    private var captureStops = 0

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
            stopLocationCapture = { captureStops++ },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
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
        assertEquals("answering a candidate is a confirmation, not a hand save", 0, captureStops)
    }

    @Test
    fun `a manual save parks the machine so the departure is watched`() = runTest {
        // Arrange — docs/05 §11c: no candidate, the machine idle, the user saving by hand.
        val viewModel = manualViewModel()
        viewModel.onFloorChange("4F")

        // Act
        viewModel.onSave()
        val settled = viewModel.uiState.first { it.savedRecordId != null || it.alreadyActive }

        // Assert — `UserConfirmedParking` would have left an IDLE machine where it was.
        assertNotNull(settled.savedRecordId)
        assertEquals(DetectionState.PARKED, runtime.restore().state)
        assertEquals(START, runtime.restore().stateEnteredAtMillis)
        assertEquals(1, captureStops)
    }

    @Test
    fun `a manual save refused for an open parking tells the machine nothing`() = runTest {
        // Arrange — a parking is already open, so this save writes nothing.
        manualViewModel().apply { onSave() }.uiState.first { it.savedRecordId != null }
        val refused = manualViewModel()

        // Act — vehicle evidence first, which a second hand save would drop (§11c).
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 1_000))
        refused.onSave()
        refused.uiState.first { it.alreadyActive }

        // Assert
        assertNotNull("the departure evidence is still being gathered", runtime.restore().session)
        assertEquals(1, captureStops)
    }

    @Test
    fun `a manual save while a departure is asked ends the old record at the departure time`() = runTest {
        // Arrange — docs/05 §11a: parked, driven away, the question never answered.
        val proposals = endProposals()
        manualViewModel().apply { onSave() }.uiState.first { it.savedRecordId != null }
        val old = checkNotNull(repository.findActive())
        proposals.propose(DEPARTED_AT)
        clock.epochMillis = DEPARTED_AT + 1_800_000L

        // Act — the next parking, saved by hand.
        val viewModel = manualViewModel(proposals)
        viewModel.onFloorChange("2F")
        viewModel.onSave()
        val settled = viewModel.uiState.first { it.savedRecordId != null || it.alreadyActive }

        // Assert — saved, and the old record closed when the car left rather than now.
        assertNotNull(settled.savedRecordId)
        assertEquals(DEPARTED_AT, repository.find(old.id)?.endedAtMillis)
        assertNull(store.readParkingEndProposalOnce())
    }

    @Test
    fun `a candidate confirmed while a departure is asked ends the old record at the departure time`() = runTest {
        // Arrange — parked two hours earlier, driven away ten minutes before this drive's
        // candidate, the question never answered.
        val proposals = endProposals()
        clock.epochMillis = START - 7_200_000L
        SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-old" },
        )(ManualParkingInput(floorRaw = "B3"))
        val departedAt = START - 600_000L
        proposals.propose(departedAt)
        clock.epochMillis = START
        val candidateId = detectParking()

        // Act — 직접 입력 on the new candidate.
        val viewModel = ManualParkingViewModel(
            saveManualParking = SaveManualParkingUseCase(
                repository = repository,
                locationProvider = { null },
                clock = clock,
                idGenerator = { UUID.randomUUID().toString() },
            ),
            candidateId = candidateId,
            coordinator = coordinator,
            detectionRuntime = runtime,
            clock = clock,
            endProposals = proposals,
        )
        viewModel.onFloorChange("B1")
        viewModel.onSave()
        val settled = viewModel.uiState.first { it.savedRecordId != null || it.candidateGone || it.alreadyActive }

        // Assert
        assertNotNull(settled.savedRecordId)
        assertEquals(departedAt, repository.find("record-old")?.endedAtMillis)
        assertEquals("B1", checkNotNull(repository.findActive()).floor?.displayLabel)
        assertNull(store.readParkingEndProposalOnce())
    }

    @Test
    fun `a candidate that expired while the form was open leaves the asked-about parking open`() = runTest {
        // Arrange — a departure asked about the old record, then a candidate for the next
        // parking that runs out (docs/05 §10a, 45 minutes) before 저장 is tapped.
        val proposals = endProposals()
        clock.epochMillis = START - 7_200_000L
        SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-old" },
        )(ManualParkingInput(floorRaw = "B3"))
        val departedAt = START - 600_000L
        proposals.propose(departedAt)
        clock.epochMillis = START
        val candidateId = detectParking()
        val viewModel = ManualParkingViewModel(
            saveManualParking = SaveManualParkingUseCase(
                repository = repository,
                locationProvider = { null },
                clock = clock,
                idGenerator = { UUID.randomUUID().toString() },
            ),
            candidateId = candidateId,
            coordinator = coordinator,
            detectionRuntime = runtime,
            clock = clock,
            endProposals = proposals,
        )
        viewModel.onFloorChange("B1")
        clock.epochMillis = checkNotNull(store.readCandidateOnce()).expiresAtMillis

        // Act
        viewModel.onSave()
        val settled = viewModel.uiState.first { it.savedRecordId != null || it.candidateGone || it.alreadyActive }

        // Assert — no next parking was written, so nothing resolved the question (§11a).
        assertEquals(true, settled.candidateGone)
        assertNull(settled.savedRecordId)
        assertEquals("record-old", repository.findActive()?.id)
        assertNull(repository.find("record-old")?.endedAtMillis)
        assertEquals(
            ParkingEndProposal(recordId = "record-old", departedAtMillis = departedAt),
            store.readParkingEndProposalOnce(),
        )
    }

    @Test
    fun `a hand save whose write fails ends nothing and leaves the question pending`() = runTest {
        // Arrange — docs/05 §11a: the end of the asked-about record and the insert of the next
        // one are one store write. The insert is made to fail with a real constraint error: the
        // new record reuses the old one's primary key.
        val notifier = FakeParkingEndProposalNotifier()
        val proposals = endProposals(notifier)
        saveOldParking()
        proposals.propose(DEPARTED_AT)
        clock.epochMillis = DEPARTED_AT + 1_800_000L
        val viewModel = ManualParkingViewModel(
            saveManualParking = SaveManualParkingUseCase(
                repository = repository,
                locationProvider = { null },
                clock = clock,
                idGenerator = { "record-old" },
            ),
            detectionRuntime = runtime,
            clock = clock,
            endProposals = proposals,
        )
        viewModel.onFloorChange("2F")

        // Act
        viewModel.onSave()
        val settled = viewModel.uiState.first { it.savedRecordId != null || it.saveFailed || it.alreadyActive }

        // Assert — nothing ended, the question still stored and still on screen.
        assertTrue(settled.saveFailed)
        assertFalse(settled.saving)
        assertNull(settled.savedRecordId)
        assertOldParkingStillAsked(notifier)
    }

    @Test
    fun `a candidate confirmation whose write fails ends nothing and leaves the question pending`() = runTest {
        // Arrange — the same failure on the candidate path: a live candidate, and a record id
        // that collides with the asked-about parking's.
        val notifier = FakeParkingEndProposalNotifier()
        val proposals = endProposals(notifier)
        clock.epochMillis = START - 7_200_000L
        saveOldParking()
        proposals.propose(DEPARTED_AT_BEFORE_CANDIDATE)
        clock.epochMillis = START
        val candidateId = detectParking()
        val collidingCoordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { repository },
            notifier = FakeCandidateNotifier(),
            clock = clock,
            idGenerator = { "record-old" },
        )
        val viewModel = ManualParkingViewModel(
            saveManualParking = SaveManualParkingUseCase(
                repository = repository,
                locationProvider = { null },
                clock = clock,
                idGenerator = { UUID.randomUUID().toString() },
            ),
            candidateId = candidateId,
            coordinator = collidingCoordinator,
            detectionRuntime = runtime,
            clock = clock,
            endProposals = proposals,
        )
        viewModel.onFloorChange("B1")

        // Act
        viewModel.onSave()
        val settled = viewModel.uiState.first {
            it.savedRecordId != null || it.saveFailed || it.candidateGone || it.alreadyActive
        }

        // Assert — and the candidate is still there to be answered again.
        assertTrue(settled.saveFailed)
        assertNull(settled.savedRecordId)
        assertOldParkingStillAsked(notifier, DEPARTED_AT_BEFORE_CANDIDATE)
        assertEquals(candidateId, store.readCandidateOnce()?.id)
        assertEquals(DetectionState.CANDIDATE_PENDING, runtime.restore().state)
    }

    private suspend fun saveOldParking() {
        SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-old" },
        )(ManualParkingInput(floorRaw = "B3"))
    }

    private suspend fun assertOldParkingStillAsked(
        notifier: FakeParkingEndProposalNotifier,
        departedAt: Long = DEPARTED_AT,
    ) {
        assertEquals("record-old", repository.findActive()?.id)
        assertNull(repository.find("record-old")?.endedAtMillis)
        assertEquals(
            ParkingEndProposal(recordId = "record-old", departedAtMillis = departedAt),
            store.readParkingEndProposalOnce(),
        )
        assertEquals("record-old", notifier.showing?.id)
        assertEquals(0, notifier.withdrawals)
    }

    private fun endProposals(
        notifier: FakeParkingEndProposalNotifier = FakeParkingEndProposalNotifier(),
    ) = ParkingEndProposalCoordinator(
        store = store,
        repository = { repository },
        notifier = notifier,
        clock = clock,
    )

    private fun manualViewModel(endProposals: ParkingEndProposalCoordinator? = null) = ManualParkingViewModel(
        saveManualParking = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { UUID.randomUUID().toString() },
            analytics = RecordingAnalytics(),
        ),
        detectionRuntime = runtime,
        clock = clock,
        endProposals = endProposals,
    )

    @Test
    fun `주차 아님 returns the machine to IDLE`() = runTest {
        val candidateId = detectParking()

        val viewModel = ConfirmCandidateViewModel(
            candidateId = candidateId,
            coordinator = coordinator,
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
        const val DEPARTED_AT = START + 3_600_000L
        const val DEPARTED_AT_BEFORE_CANDIDATE = START - 600_000L
    }
}

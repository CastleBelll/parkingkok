package com.sjstudioz.parkingpin.ui.detail

import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.data.photo.ParkingPhotoImage
import com.sjstudioz.parkingpin.data.photo.ParkingPhotoImageLoader
import com.sjstudioz.parkingpin.detection.FakeParkingEndProposalNotifier
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.detection.ParkingEndProposalCoordinator
import com.sjstudioz.parkingpin.domain.parking.usecase.ApplyPillarSuggestionUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.DeleteParkingRecordUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import com.sjstudioz.parkingpin.domain.parking.usecase.ObserveParkingRecordUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.RemoveParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingResult
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SuggestFromPillarPhotoUseCase
import com.sjstudioz.parkingpin.domain.photo.FakeParkingPhotoStore
import com.sjstudioz.parkingpin.domain.photo.ReadPillarSuggestionUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `삭제` on the detail screen and the departure question (docs/05 §11a): a question about a
 * record that no longer exists is withdrawn at once, as iOS retires it on delete — not left
 * in the shade until the app next comes forward.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParkingDetailDeleteTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = MutableTestClock(epochMillis = START)
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: DetectionStateStore
    private lateinit var notifier: FakeParkingEndProposalNotifier
    private lateinit var proposals: ParkingEndProposalCoordinator
    private val photoStore = FakeParkingPhotoStore()

    /** Room writes on its own threads, so the test waits for the delete to finish, not for idle. */
    private val deleteSettled = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        notifier = FakeParkingEndProposalNotifier()
        proposals = ParkingEndProposalCoordinator(
            store = store,
            repository = { repository },
            notifier = notifier,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `deleting the asked-about parking withdraws the question and its notification`() = runTest {
        // Arrange
        val active = save("record-1", floor = "B3")
        proposals.propose(DEPARTED_AT)
        val viewModel = viewModelFor(active)

        // Act
        viewModel.onDelete()
        deleteSettled.await()

        // Assert
        assertNull(repository.find(active))
        assertNull(store.readParkingEndProposalOnce())
        assertNull(notifier.showing)
    }

    @Test
    fun `deleting a finished parking leaves the question about the open one alone`() = runTest {
        // Arrange — an older, completed record is deleted while the open one is asked about.
        val finished = save("record-old", floor = "B1")
        EndParkingUseCase(repository, clock)()
        clock.epochMillis += 60_000L
        val active = save("record-new", floor = "B3")
        proposals.propose(DEPARTED_AT)
        val viewModel = viewModelFor(finished)

        // Act
        viewModel.onDelete()
        deleteSettled.await()

        // Assert
        assertEquals(active, store.readParkingEndProposalOnce()?.recordId)
        assertEquals(active, notifier.showing?.id)
    }

    private suspend fun save(id: String, floor: String): String {
        val result = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { id },
        )(ManualParkingInput(floorRaw = floor))
        return (result as SaveManualParkingResult.Saved).record.id
    }

    private fun viewModelFor(recordId: String) = ParkingDetailViewModel(
        recordId = recordId,
        observeRecord = ObserveParkingRecordUseCase(repository),
        photoLoader = NoPhotoLoader,
        endParking = EndParkingUseCase(repository, clock),
        deleteRecord = DeleteParkingRecordUseCase(repository, photoStore),
        attachPhoto = AttachParkingPhotoUseCase(repository, photoStore, clock),
        removePhoto = RemoveParkingPhotoUseCase(repository, photoStore, clock),
        suggestFromPillarPhoto = SuggestFromPillarPhotoUseCase(
            repository,
            ReadPillarSuggestionUseCase(reader = { emptyList() }),
        ),
        applyPillarSuggestion = ApplyPillarSuggestionUseCase(repository, clock),
        clock = clock,
        withdrawEndProposal = proposals::withdraw,
        dropStaleEndProposal = {
            proposals.dropIfStale()
            deleteSettled.complete(Unit)
        },
    )

    private object NoPhotoLoader : ParkingPhotoImageLoader {
        override suspend fun load(relativePath: String?, maxLongEdge: Int): ParkingPhotoImage? = null
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val DEPARTED_AT = START + 3_600_000L
    }
}

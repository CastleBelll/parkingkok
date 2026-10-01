package com.sjstudioz.parkingpin.ui.home

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import com.sjstudioz.parkingpin.domain.parking.usecase.AdjustParkingFloorUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ApplyPillarSuggestionUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ObserveActiveParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ObserveParkingHistoryUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SuggestFromPillarPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SuggestUsualSpotUseCase
import com.sjstudioz.parkingpin.domain.photo.FakeParkingPhotoStore
import com.sjstudioz.parkingpin.domain.photo.PillarSuggestion
import com.sjstudioz.parkingpin.domain.photo.PillarTextReader
import com.sjstudioz.parkingpin.domain.photo.ReadPillarSuggestionUseCase
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** docs/02 §18 on home: an open parking with blanks is offered last time's answer here. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeUsualSpotTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)
    private val mart = ParkingLocation(37.5, 127.0, 20f, 0L)

    @Before
    fun setUp() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase(queryContext = dispatcher)
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    /** Every view model a test made; its Room observers outlive the test unless cancelled. */
    private val viewModels = mutableListOf<HomeViewModel>()

    @After
    fun tearDown() {
        // Before resetMain: a live collector would otherwise touch Dispatchers.Main after it
        // is gone and fail whichever test class runs next.
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `an open parking with no floor is offered last time's and gets it on the tap`() = runTest {
        // Arrange
        repository.insert(record("last-week", floor = "B2", zone = "A", endedAt = 100L))
        repository.insert(record("today", floor = null, zone = null, endedAt = null))
        val viewModel = homeViewModel()

        // Act
        val offered = viewModel.uiState.first { it.usualSpot != null }.usualSpot
        viewModel.onApplyUsualSpot()

        // Assert
        assertEquals(PillarSuggestion(floorRaw = "B2", zone = "A"), offered)
        val applied = repository.observeActive().filterNotNull().first { it.floor != null }
        assertEquals("B2", applied.floor?.raw)
        assertEquals("A", applied.zone)
        assertEquals(null, viewModel.uiState.first { it.usualSpot == null }.usualSpot)
    }

    @Test
    fun `waving it away writes nothing and does not ask again`() = runTest {
        repository.insert(record("last-week", floor = "B2", zone = "A", endedAt = 100L))
        repository.insert(record("today", floor = null, zone = null, endedAt = null))
        val viewModel = homeViewModel()
        viewModel.uiState.first { it.usualSpot != null }

        viewModel.onDismissUsualSpot()

        assertEquals(null, viewModel.uiState.first { it.usualSpot == null }.usualSpot)
        assertEquals(null, repository.findActive()?.floor)
    }

    private fun homeViewModel() = HomeViewModel(
        observeActive = ObserveActiveParkingUseCase(repository),
        observeHistory = ObserveParkingHistoryUseCase(repository),
        observePendingCandidate = { flowOf(null) },
        endParking = EndParkingUseCase(repository, clock),
        adjustParkingFloor = AdjustParkingFloorUseCase(repository, clock),
        attachPhoto = AttachParkingPhotoUseCase(repository, FakeParkingPhotoStore(), clock),
        suggestFromPillarPhoto = SuggestFromPillarPhotoUseCase(
            repository,
            ReadPillarSuggestionUseCase(PillarTextReader { emptyList() }),
        ),
        applyPillarSuggestion = ApplyPillarSuggestionUseCase(repository, clock),
        clock = clock,
        suggestUsualSpot = { SuggestUsualSpotUseCase(repository).forRecord(it) },
    ).also { viewModels += it }

    private fun record(id: String, floor: String?, zone: String?, endedAt: Long?) = ParkingRecord(
        id = id,
        startedAtMillis = 0L,
        endedAtMillis = endedAt,
        source = ParkingSource.DETECTED,
        confidenceBucket = null,
        location = mart,
        floor = FloorParser.parse(floor),
        zone = zone,
        spot = null,
        memo = null,
        photoRelativePath = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        revision = 1,
    )
}

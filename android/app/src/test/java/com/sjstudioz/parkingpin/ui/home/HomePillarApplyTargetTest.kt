package com.sjstudioz.parkingpin.ui.home

import androidx.lifecycle.viewModelScope
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import com.sjstudioz.parkingpin.domain.parking.usecase.AdjustParkingFloorUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ApplyPillarSuggestionUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ObserveActiveParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ObserveParkingHistoryUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SuggestFromPillarPhotoUseCase
import com.sjstudioz.parkingpin.domain.photo.FakeParkingPhotoStore
import com.sjstudioz.parkingpin.domain.photo.PhotoSource
import com.sjstudioz.parkingpin.domain.photo.PillarLine
import com.sjstudioz.parkingpin.domain.photo.PillarTextReader
import com.sjstudioz.parkingpin.domain.photo.ReadPillarSuggestionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/** docs/02 §6a on home: a suggestion is written to the record it was read for (audit 2026-10-01). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomePillarApplyTargetTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)
    private val viewModels = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase(queryContext = dispatcher)
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `a suggestion read for one parking is not written onto the next`() = runTest(dispatcher) {
        // Arrange — a photo of the first parking's pillar read B2.
        repository.insert(record("first"))
        val viewModel = homeViewModel(reading = listOf(PillarLine("B2", 0.15)))
        viewModel.uiState.first { it.active?.id == "first" }
        viewModel.onPhotoSelected(PhotoSource { ByteArrayInputStream(ByteArray(0)) })
        viewModel.uiState.first { it.pillarSuggestion != null }

        // Act — that parking ends and another begins before the tap.
        EndParkingUseCase(repository, clock)()
        repository.insert(record("second"))
        viewModel.uiState.first { it.active?.id == "second" }
        viewModel.onApplyPillarSuggestion()
        advanceUntilIdle()

        // Assert
        assertNull(repository.find("second")?.floor)
    }

    private fun homeViewModel(reading: List<PillarLine>) = HomeViewModel(
        observeActive = ObserveActiveParkingUseCase(repository),
        observeHistory = ObserveParkingHistoryUseCase(repository),
        observePendingCandidate = { flowOf(null) },
        endParking = EndParkingUseCase(repository, clock),
        adjustParkingFloor = AdjustParkingFloorUseCase(repository, clock),
        attachPhoto = AttachParkingPhotoUseCase(repository, FakeParkingPhotoStore(), clock),
        suggestFromPillarPhoto = SuggestFromPillarPhotoUseCase(
            repository,
            ReadPillarSuggestionUseCase(PillarTextReader { reading }),
        ),
        applyPillarSuggestion = ApplyPillarSuggestionUseCase(repository, clock),
        clock = clock,
    ).also { viewModels += it }

    private fun record(id: String) = ParkingRecord(
        id = id,
        startedAtMillis = 0L,
        endedAtMillis = null,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = null,
        floor = null,
        zone = null,
        spot = null,
        memo = null,
        photoRelativePath = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        revision = 1,
    )
}

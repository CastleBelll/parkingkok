package com.sjstudioz.parkingpin.ui.manual

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import com.sjstudioz.parkingpin.domain.photo.PillarSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** docs/02 §18 on the manual form: offered, filled on the tap, never over what was typed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualParkingUsualSpotTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)
    private val lastTime = PillarSuggestion(floorRaw = "B2", zone = "A")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `last time's parking is offered and nothing is filled until the tap`() = runTest {
        val viewModel = viewModel { lastTime }

        advanceUntilIdle()

        assertEquals(lastTime, viewModel.uiState.value.usualSpot)
        assertEquals("", viewModel.uiState.value.floorRaw)
    }

    @Test
    fun `the tap fills the blanks and keeps what was typed`() = runTest {
        val viewModel = viewModel { lastTime }
        advanceUntilIdle()
        viewModel.onZoneChange("D")

        viewModel.onApplyUsualSpot()

        val state = viewModel.uiState.value
        assertEquals("B2", state.floorRaw)
        assertEquals("D", state.zone)
        assertNull(state.usualSpot)
    }

    @Test
    fun `typing everything it would fill retires the offer`() = runTest {
        val viewModel = viewModel { lastTime }
        advanceUntilIdle()

        viewModel.onFloorChange("B3")
        viewModel.onZoneChange("C")

        assertNull(viewModel.uiState.value.usualSpot)
    }

    @Test
    fun `typing a different floor retires the offer, the same floor keeps the zone`() = runTest {
        val viewModel = viewModel { lastTime }
        advanceUntilIdle()

        viewModel.onFloorChange("지하 2층")
        assertEquals(lastTime, viewModel.uiState.value.usualSpot)

        viewModel.onFloorChange("B3")
        assertNull(viewModel.uiState.value.usualSpot)
    }

    @Test
    fun `a lookup that fails costs the offer and nothing else`() = runTest {
        val viewModel = viewModel { throw IllegalStateException("disk") }

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.usualSpot)
        viewModel.onFloorChange("B1")
        viewModel.onSave()
        viewModel.uiState.first { it.savedRecordId != null }
        assertEquals("B1", repository.findActive()?.floor?.raw)
    }

    private fun viewModel(lookup: suspend () -> PillarSuggestion?) = ManualParkingViewModel(
        saveManualParking = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-1" },
        ),
        clock = clock,
        usualSpotNear = lookup,
    )
}

package com.sjstudioz.parkingpin.ui.manual

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import com.sjstudioz.parkingpin.domain.parking.usecase.EditParkingDetailsUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** `수정` must not write the empty form over a record it has not read yet (audit 2026-10-01). */
@OptIn(ExperimentalCoroutinesApi::class)
class EditParkingLoadTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase(queryContext = dispatcher)
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `a save before the record is read writes nothing`() = runTest(dispatcher) {
        // Arrange
        repository.insert(record())
        val slowRead = GatedFind(repository)
        val viewModel = EditParkingViewModel("record-1", slowRead, EditParkingDetailsUseCase(repository, clock))

        // Act
        viewModel.onSave()
        advanceUntilIdle()

        // Assert
        assertNull(viewModel.uiState.value.savedRecordId)
        assertEquals("B3", repository.find("record-1")?.floor?.raw)
        assertEquals("A", repository.find("record-1")?.zone)
    }

    @Test
    fun `what was typed during the read is kept, the rest is filled from the record`() = runTest(dispatcher) {
        // Arrange
        repository.insert(record())
        val slowRead = GatedFind(repository)
        val viewModel = EditParkingViewModel("record-1", slowRead, EditParkingDetailsUseCase(repository, clock))

        // Act
        viewModel.onFloorChange("B4")
        slowRead.release()
        advanceUntilIdle()

        // Assert
        assertEquals("B4", viewModel.uiState.value.floorRaw)
        assertEquals("A", viewModel.uiState.value.zone)
    }

    private fun record() = ParkingRecord(
        id = "record-1",
        startedAtMillis = 0L,
        endedAtMillis = null,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = null,
        floor = FloorParser.parse("B3"),
        zone = "A",
        spot = null,
        memo = null,
        photoRelativePath = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        revision = 1,
    )

    /** A repository whose `find` answers only when the test says so: a slow first read. */
    private class GatedFind(private val inner: ParkingRepository) : ParkingRepository by inner {
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun find(id: String): ParkingRecord? {
            gate.await()
            return inner.find(id)
        }
    }
}

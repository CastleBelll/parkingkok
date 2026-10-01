package com.sjstudioz.parkingpin.domain.parking

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.usecase.EditParkingDetailsUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingResult
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** `수정` on the detail screen (audit 2026-10-01): any record's floor, zone, spot and memo. */
class EditParkingDetailsUseCaseTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a record saved without a floor can be given one`() = runTest {
        // Arrange — the case that had no way in: a parking confirmed with nothing typed.
        val id = save(ManualParkingInput(floorRaw = ""))
        clock.epochMillis += 60_000

        // Act
        val edited = EditParkingDetailsUseCase(repository, clock)(id, ManualParkingInput(floorRaw = "B3", zone = "A구역"))

        // Assert
        assertEquals("B3", edited?.floor?.raw)
        assertEquals(FloorKind.BASEMENT, edited?.floor?.kind)
        assertEquals("A구역", edited?.zone)
        assertEquals(clock.epochMillis, edited?.updatedAtMillis)
        assertEquals(2, edited?.revision)
    }

    @Test
    fun `clearing a field is an edit too`() = runTest {
        // Arrange
        val id = save(ManualParkingInput(floorRaw = "B3", zone = "A구역", spot = "142", memo = "기둥 옆"))

        // Act
        val edited = EditParkingDetailsUseCase(repository, clock)(id, ManualParkingInput(floorRaw = "B3", zone = " ", spot = "", memo = null))

        // Assert
        assertNull(edited?.zone)
        assertNull(edited?.spot)
        assertNull(edited?.memo)
        assertEquals("B3", edited?.floor?.raw)
    }

    @Test
    fun `the same caps as a save apply`() = runTest {
        val id = save(ManualParkingInput(floorRaw = "B3"))

        val edited = EditParkingDetailsUseCase(repository, clock)(id, ManualParkingInput(floorRaw = "B3", zone = "가".repeat(60)))

        assertEquals(ParkingFieldLimits.MAX_SHORT_FIELD, edited?.zone?.length)
    }

    @Test
    fun `a finished record can be corrected and stays finished`() = runTest {
        // Arrange
        val id = save(ManualParkingInput(floorRaw = "B2"))
        EndParkingUseCase(repository, clock)()
        val endedAt = repository.find(id)?.endedAtMillis

        // Act
        val edited = EditParkingDetailsUseCase(repository, clock)(id, ManualParkingInput(floorRaw = "B4"))

        // Assert
        assertEquals("B4", edited?.floor?.raw)
        assertEquals(endedAt, edited?.endedAtMillis)
    }

    @Test
    fun `a record deleted meanwhile edits nothing`() = runTest {
        assertNull(EditParkingDetailsUseCase(repository, clock)("missing", ManualParkingInput(floorRaw = "B3")))
    }

    private suspend fun save(input: ManualParkingInput): String {
        val result = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-1" },
        )(input)
        return (result as SaveManualParkingResult.Saved).record.id
    }
}

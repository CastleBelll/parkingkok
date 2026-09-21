package kr.parkingpin.app.domain.parking

import kr.parkingpin.app.data.parking.ParkingDatabase
import kr.parkingpin.app.data.parking.RoomParkingRepository
import kr.parkingpin.app.data.parking.createTestParkingDatabase
import kr.parkingpin.app.detection.MutableTestClock
import kr.parkingpin.app.domain.parking.usecase.AdjustParkingFloorUseCase
import kr.parkingpin.app.domain.parking.usecase.EndParkingUseCase
import kr.parkingpin.app.domain.parking.usecase.ManualParkingInput
import kr.parkingpin.app.domain.parking.usecase.SaveManualParkingResult
import kr.parkingpin.app.domain.parking.usecase.SaveManualParkingUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The manual parking lifecycle: save, adjust the floor, end. */
class SaveManualParkingUseCaseTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository

    private val start = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = start)
    private var nextId = 0

    private lateinit var save: SaveManualParkingUseCase
    private lateinit var end: EndParkingUseCase
    private lateinit var adjustFloor: AdjustParkingFloorUseCase

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        save = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-${nextId++}" },
        )
        end = EndParkingUseCase(repository, clock)
        adjustFloor = AdjustParkingFloorUseCase(repository, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `stamps the record with the injected clock`() = runTest {
        save(ManualParkingInput(floorRaw = "B3"))

        val stored = repository.findActive()
        assertEquals(start, stored?.startedAtMillis)
        assertEquals(start, stored?.createdAtMillis)
        assertEquals(start, stored?.updatedAtMillis)
        assertEquals(1, stored?.revision)
    }

    @Test
    fun `trims input and drops fields that are only whitespace`() = runTest {
        save(ManualParkingInput(zone = "  A구역  ", spot = "   ", memo = ""))

        val stored = repository.findActive()
        assertEquals("A구역", stored?.zone)
        assertNull(stored?.spot)
        assertNull(stored?.memo)
    }

    @Test
    fun `truncates zone and spot at the FR-006 limit rather than refusing the save`() = runTest {
        save(ManualParkingInput(zone = "구".repeat(60), spot = "9".repeat(60)))

        val stored = repository.findActive()
        assertEquals(40, stored?.zone?.length)
        assertEquals(40, stored?.spot?.length)
    }

    @Test
    fun `refuses to open a second session while one is already active`() = runTest {
        save(ManualParkingInput(floorRaw = "B3"))

        val result = save(ManualParkingInput(floorRaw = "B4"))

        assertTrue(result is SaveManualParkingResult.AlreadyActive)
        assertEquals("B3", (result as SaveManualParkingResult.AlreadyActive).existing.floor?.displayLabel)
        assertEquals(1, repository.observeCompleted(-1).first().size + 1)
    }

    @Test
    fun `ending moves the record into history and frees the active slot`() = runTest {
        save(ManualParkingInput(floorRaw = "B3"))
        clock.epochMillis += 3_600_000

        val ended = end()

        assertEquals(start + 3_600_000, ended?.endedAtMillis)
        assertEquals(2, ended?.revision)
        assertNull(repository.observeActive().first())
        assertEquals(listOf(ended?.id), repository.observeCompleted(-1).first().map { it.id })
    }

    @Test
    fun `ending with nothing parked is a no-op, not a crash`() = runTest {
        assertNull(end())
    }

    @Test
    fun `ending twice keeps the first end time`() = runTest {
        save(ManualParkingInput(floorRaw = "B3"))
        clock.epochMillis += 60_000
        val first = end()

        clock.epochMillis += 60_000
        val second = end()

        assertNull(second)
        assertEquals(start + 60_000, repository.observeCompleted(-1).first().single().endedAtMillis)
        assertNotNull(first)
    }

    @Test
    fun `a backwards clock cannot end a parking before it started`() = runTest {
        save(ManualParkingInput(floorRaw = "B3"))
        clock.epochMillis -= 3_600_000

        val ended = end()

        assertEquals(start, ended?.endedAtMillis)
    }

    @Test
    fun `the floor keys step the active record and bump the revision`() = runTest {
        save(ManualParkingInput(floorRaw = "B3"))
        clock.epochMillis += 1_000

        val adjusted = adjustFloor(1)

        assertEquals("B2", adjusted?.floor?.displayLabel)
        assertEquals(2, adjusted?.revision)
        assertEquals(start + 1_000, adjusted?.updatedAtMillis)
    }

    @Test
    fun `the floor keys do nothing to a free-text floor`() = runTest {
        save(ManualParkingInput(floorRaw = "옥상 주차장"))

        assertNull(adjustFloor(1))
        assertEquals("옥상 주차장", repository.findActive()?.floor?.displayLabel)
        assertEquals(1, repository.findActive()?.revision)
    }

    @Test
    fun `the floor keys do nothing when nothing is parked`() = runTest {
        assertNull(adjustFloor(-1))
    }

    @Test
    fun `stepping off the end of the ladder leaves the record untouched`() = runTest {
        save(ManualParkingInput(floorRaw = "${FloorParser.MAX_LEVEL}F"))

        assertNull(adjustFloor(1))
        assertEquals(1, repository.findActive()?.revision)
    }
}

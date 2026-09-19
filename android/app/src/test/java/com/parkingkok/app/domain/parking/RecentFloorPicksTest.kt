package com.parkingkok.app.domain.parking

import com.parkingkok.app.data.parking.ParkingDatabase
import com.parkingkok.app.data.parking.RoomParkingRepository
import com.parkingkok.app.data.parking.createTestParkingDatabase
import com.parkingkok.app.detection.MutableTestClock
import com.parkingkok.app.domain.parking.usecase.ManualParkingInput
import com.parkingkok.app.domain.parking.usecase.RecentFloorPicksUseCase
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingUseCase
import com.parkingkok.app.domain.parking.usecase.EndParkingUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * docs/10_DESIGN_UX_SPEC.md §7a "The floor choices".
 *
 * > The picks come from the floors this user has saved before, most recent first — local
 * > history, no network, no guessing.
 *
 * The 0 / 1 / 3 cases are the ones the spec calls out by name, so they are here by name.
 */
class RecentFloorPicksTest {

    // ── The rule, on its own ─────────────────────────────────────────────────────────

    @Test
    fun `a first-ever run offers nothing`() {
        // §7a: "a first-ever run shows only 직접 입력".
        assertEquals(emptyList<Floor>(), RecentFloorPicks.of(emptyList()))
    }

    @Test
    fun `one past floor offers one pick`() {
        // §7a: "with fewer than three past floors the row simply shows fewer".
        val picks = RecentFloorPicks.of(listOf("B3"))

        assertEquals(listOf("B3"), picks.map { it.displayLabel })
    }

    @Test
    fun `three distinct past floors offer three picks, most recent first`() {
        val picks = RecentFloorPicks.of(listOf("B3", "2F", "B1", "B4"))

        assertEquals(listOf("B3", "2F", "B1"), picks.map { it.displayLabel })
    }

    @Test
    fun `a user who always parks on B3 sees B3 and nothing invented beside it`() {
        // The literal example in §7a. Three buttons are a maximum, never a quota — filling
        // the row with B1 and B2 would be the guessing the screen exists to avoid.
        val picks = RecentFloorPicks.of(listOf("B3", "B3", "B3", "B3"))

        assertEquals(listOf("B3"), picks.map { it.displayLabel })
    }

    @Test
    fun `spellings of the same floor collapse to the most recent one`() {
        val picks = RecentFloorPicks.of(listOf("지하 3층", "b3", "B3", "1F"))

        assertEquals(listOf("B3", "1F"), picks.map { it.displayLabel })
        // The first spelling wins because it is the one the user chose last.
        assertEquals("지하 3층", picks.first().raw)
    }

    @Test
    fun `free text is a floor like any other`() {
        // FR-005 keeps unparseable text verbatim, and a user who parks in 임원동 deserves
        // that button as much as anyone gets B3.
        val picks = RecentFloorPicks.of(listOf("임원동", "B2"))

        assertEquals(listOf("임원동", "B2"), picks.map { it.displayLabel })
        assertEquals(FloorKind.FREE_TEXT, picks.first().kind)
    }

    @Test
    fun `blank history entries are not offered as buttons`() {
        assertEquals(listOf("B1"), RecentFloorPicks.of(listOf("   ", "", "B1")).map { it.displayLabel })
    }

    @Test
    fun `a zero limit offers nothing rather than throwing`() {
        assertEquals(emptyList<Floor>(), RecentFloorPicks.of(listOf("B3"), limit = 0))
    }

    // ── The rule, over real stored history ───────────────────────────────────────────

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)
    private var nextId = 0

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
    fun `the picks come from the records this device actually holds, newest first`() = runTest {
        save("B1")
        save("2F")
        save("B3")

        val picks = RecentFloorPicksUseCase(repository)()

        assertEquals(listOf("B3", "2F", "B1"), picks.map { it.displayLabel })
    }

    @Test
    fun `a device with no history offers no picks`() = runTest {
        assertEquals(emptyList<Floor>(), RecentFloorPicksUseCase(repository)())
    }

    @Test
    fun `a record saved without a floor contributes no pick`() = runTest {
        save(floorRaw = null)
        save("B2")

        assertEquals(listOf("B2"), RecentFloorPicksUseCase(repository)().map { it.displayLabel })
    }

    /** Saves and immediately ends, so the next save is not refused for an open session. */
    private suspend fun save(floorRaw: String?) {
        SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-${nextId++}" },
        )(ManualParkingInput(floorRaw = floorRaw))
        clock.epochMillis += 60_000L
        EndParkingUseCase(repository, clock)()
        clock.epochMillis += 60_000L
    }
}

package com.parkingpin.app.domain.parking

import com.parkingpin.app.data.parking.ParkingDatabase
import com.parkingpin.app.data.parking.RoomParkingRepository
import com.parkingpin.app.data.parking.createTestParkingDatabase
import com.parkingpin.app.detection.MutableTestClock
import com.parkingpin.app.domain.parking.usecase.AdjustParkingFloorUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a "Rapid taps resolve by delta, not by value".
 *
 * Against real SQLite, because the guarantee under test is Room's transaction: the claim
 * is that two writes cannot lose each other, and a fake repository would only prove that
 * the fake agrees with itself.
 */
class WidgetFloorStepTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var stepFloor: AdjustParkingFloorUseCase

    private val clock = MutableTestClock()
    private val start = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        stepFloor = AdjustParkingFloorUseCase(repository, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(
        id: String = "session-1",
        endedAt: Long? = null,
        floorRaw: String = "B3",
    ) = ParkingRecord(
        id = id,
        startedAtMillis = start,
        endedAtMillis = endedAt,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = null,
        floor = FloorParser.parse(floorRaw),
        zone = "A구역",
        spot = "142",
        memo = null,
        photoRelativePath = null,
        createdAtMillis = start,
        updatedAtMillis = start,
        revision = 0,
    )

    @Test
    fun `two taps landing together move two floors`() = runTest {
        repository.insert(record())

        // Real threads, not the test scheduler: the point is that two transactions
        // genuinely overlap, and a single-threaded dispatcher would run them in turn and
        // prove nothing.
        withContext(Dispatchers.Default) {
            listOf(
                async { stepFloor(delta = 1, sessionId = "session-1") },
                async { stepFloor(delta = 1, sessionId = "session-1") },
            ).awaitAll()
        }

        val stored = repository.find("session-1")
        // B3 -> B2 -> B1. A callback carrying "the floor is now B2" would have left B2.
        assertEquals("B1", stored?.floor?.displayLabel)
        assertEquals(2, stored?.revision)
    }

    @Test
    fun `two opposing taps cancel out and still leave two revisions`() = runTest {
        repository.insert(record())

        withContext(Dispatchers.Default) {
            listOf(
                async { stepFloor(delta = 1, sessionId = "session-1") },
                async { stepFloor(delta = -1, sessionId = "session-1") },
            ).awaitAll()
        }

        val stored = repository.find("session-1")
        assertEquals("B3", stored?.floor?.displayLabel)
        assertEquals(2, stored?.revision)
    }

    @Test
    fun `a tap for a session that has ended is dropped`() = runTest {
        // The widget rendered session-1; by the time the user tapped, it had ended and
        // session-2 had begun.
        repository.insert(record(id = "session-1", endedAt = start + 60_000L, floorRaw = "B3"))
        repository.insert(record(id = "session-2", floorRaw = "2F"))

        assertNull(stepFloor(delta = 1, sessionId = "session-1"))

        // Neither the closed session nor the one that came after it moved.
        assertEquals("B3", repository.find("session-1")?.floor?.displayLabel)
        assertEquals(0, repository.find("session-1")?.revision)
        assertEquals("2F", repository.find("session-2")?.floor?.displayLabel)
        assertEquals(0, repository.find("session-2")?.revision)
    }

    @Test
    fun `a tap for a session that no longer exists is dropped`() = runTest {
        repository.insert(record(id = "session-2", floorRaw = "2F"))

        assertNull(stepFloor(delta = -1, sessionId = "deleted-session"))

        assertEquals("2F", repository.find("session-2")?.floor?.displayLabel)
        assertEquals(0, repository.find("session-2")?.revision)
    }

    @Test
    fun `a step off the bottom of the ladder is a no-op`() = runTest {
        repository.insert(record(floorRaw = "B${FloorParser.MAX_LEVEL}"))

        assertNull(stepFloor(delta = -1, sessionId = "session-1"))

        val stored = repository.find("session-1")
        assertEquals("B${FloorParser.MAX_LEVEL}", stored?.floor?.displayLabel)
        // Nothing moved, so nothing was written — the widget reload exists to snap the
        // display back, not to record a change.
        assertEquals(0, stored?.revision)
    }

    @Test
    fun `a step off the top of the ladder is a no-op`() = runTest {
        repository.insert(record(floorRaw = "${FloorParser.MAX_LEVEL}F"))

        assertNull(stepFloor(delta = 1, sessionId = "session-1"))

        assertEquals(0, repository.find("session-1")?.revision)
    }

    @Test
    fun `a free-text floor cannot be stepped from the widget`() = runTest {
        repository.insert(record(floorRaw = "주차타워 옆"))

        assertNull(stepFloor(delta = 1, sessionId = "session-1"))

        assertEquals("주차타워 옆", repository.find("session-1")?.floor?.displayLabel)
        assertEquals(0, repository.find("session-1")?.revision)
    }

    @Test
    fun `stepping crosses the missing ground floor in both directions`() = runTest {
        repository.insert(record(floorRaw = "B1"))

        assertEquals("1F", stepFloor(delta = 1, sessionId = "session-1")?.floor?.displayLabel)
        assertEquals("B1", stepFloor(delta = -1, sessionId = "session-1")?.floor?.displayLabel)
    }

    @Test
    fun `the home screen steps whatever is open without naming a session`() = runTest {
        repository.insert(record(id = "session-1", endedAt = start + 60_000L))
        repository.insert(record(id = "session-2", floorRaw = "2F"))

        val updated = stepFloor(delta = 1)

        assertEquals("session-2", updated?.id)
        assertEquals("3F", updated?.floor?.displayLabel)
        assertEquals(0, repository.find("session-1")?.revision)
    }

    @Test
    fun `stepping with nothing parked does nothing`() = runTest {
        assertNull(stepFloor(delta = 1))
    }
}

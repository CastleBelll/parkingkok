package com.parkingpin.app.data.parking

import com.parkingpin.app.domain.parking.ConfidenceBucket
import com.parkingpin.app.domain.parking.FloorKind
import com.parkingpin.app.domain.parking.FloorParser
import com.parkingpin.app.domain.parking.ParkingLocation
import com.parkingpin.app.domain.parking.ParkingRecord
import com.parkingpin.app.domain.parking.ParkingSource
import com.parkingpin.app.domain.parking.usecase.ObserveParkingHistoryUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The storage contract of docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §2, checked against real
 * SQLite.
 */
class RoomParkingRepositoryTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository

    private val start = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(
        id: String = "record-1",
        startedAt: Long = start,
        endedAt: Long? = null,
        floorRaw: String? = "B3",
        location: ParkingLocation? = null,
    ) = ParkingRecord(
        id = id,
        startedAtMillis = startedAt,
        endedAtMillis = endedAt,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = location,
        floor = FloorParser.parse(floorRaw),
        zone = "A구역",
        spot = "142",
        memo = "기둥 옆",
        photoRelativePath = null,
        createdAtMillis = startedAt,
        updatedAtMillis = startedAt,
        revision = 1,
    )

    @Test
    fun `every schema field survives a round trip`() = runTest {
        val original = record(
            location = ParkingLocation(37.1234, 127.5678, horizontalAccuracyM = 18f, capturedAtMillis = start - 5_000),
        ).copy(
            source = ParkingSource.DETECTED,
            confidenceBucket = ConfidenceBucket.HIGH,
            photoRelativePath = "photos/record-1.jpg",
            revision = 4,
        )

        repository.insert(original)

        assertEquals(original, repository.findActive())
    }

    @Test
    fun `the open record is the active parking`() = runTest {
        repository.insert(record(id = "open"))

        val active = repository.observeActive().first()

        assertNotNull(active)
        assertEquals("open", active?.id)
        assertTrue(active?.isActive ?: false)
    }

    @Test
    fun `a closed record leaves the active slot empty and joins history`() = runTest {
        repository.insert(record(id = "closed", endedAt = start + 3_600_000))

        assertNull(repository.observeActive().first())
        assertEquals(listOf("closed"), repository.observeCompleted(ALL).first().map { it.id })
    }

    @Test
    fun `history excludes the still-open record so the active car is never listed twice`() = runTest {
        repository.insert(record(id = "open"))
        repository.insert(record(id = "closed", startedAt = start - 1, endedAt = start))

        assertEquals(listOf("closed"), repository.observeCompleted(ALL).first().map { it.id })
    }

    @Test
    fun `history is newest first`() = runTest {
        repository.insert(record(id = "older", startedAt = start - 10_000, endedAt = start - 5_000))
        repository.insert(record(id = "newer", startedAt = start, endedAt = start + 5_000))

        assertEquals(listOf("newer", "older"), repository.observeCompleted(ALL).first().map { it.id })
    }

    @Test
    fun `history honours a limit and a negative limit means all`() = runTest {
        repeat(5) { index ->
            repository.insert(
                record(id = "r$index", startedAt = start + index, endedAt = start + index + 1),
            )
        }

        assertEquals(2, repository.observeCompleted(2).first().size)
        assertEquals(5, repository.observeCompleted(ALL).first().size)
    }

    @Test
    fun `update applies under a transaction and hands back what was stored`() = runTest {
        repository.insert(record(id = "open"))

        val updated = repository.update("open") {
            it.copy(endedAtMillis = start + 60_000, revision = it.revision + 1)
        }

        assertEquals(start + 60_000, updated?.endedAtMillis)
        assertEquals(2, updated?.revision)
        assertNull(repository.observeActive().first())
    }

    @Test
    fun `updating a record that is gone reports null instead of resurrecting it`() = runTest {
        assertNull(repository.update("never-existed") { it })
    }

    @Test
    fun `the floor reading is stored, not recomputed on read`() = runTest {
        // "3" parses as ground today; the stored kind is what must come back.
        repository.insert(record(id = "open", floorRaw = "지하 3층"))

        val floor = repository.findActive()?.floor

        assertEquals(FloorKind.BASEMENT, floor?.kind)
        assertEquals(3, floor?.number)
        assertEquals("지하 3층", floor?.raw)
    }

    @Test
    fun `a record with no location round trips as having none`() = runTest {
        repository.insert(record(location = null))

        assertNull(repository.findActive()?.location)
    }

    @Test
    fun `deleting history keeps the car that is still parked`() = runTest {
        repository.insert(record(id = "open"))
        repository.insert(record(id = "closed", startedAt = start - 1, endedAt = start))

        repository.deleteCompleted()

        assertEquals("open", repository.observeActive().first()?.id)
        assertTrue(repository.observeCompleted(ALL).first().isEmpty())
    }

    @Test
    fun `deleting one record leaves the rest`() = runTest {
        repository.insert(record(id = "a", endedAt = start + 1))
        repository.insert(record(id = "b", startedAt = start + 2, endedAt = start + 3))

        repository.delete("a")

        assertEquals(listOf("b"), repository.observeCompleted(ALL).first().map { it.id })
    }

    @Test
    fun `observing a record by id reports its removal`() = runTest {
        repository.insert(record(id = "watched"))
        assertNotNull(repository.observeRecord("watched").first())

        repository.delete("watched")

        assertNull(repository.observeRecord("watched").first())
    }

    private companion object {
        const val ALL = ObserveParkingHistoryUseCase.ALL
    }
}

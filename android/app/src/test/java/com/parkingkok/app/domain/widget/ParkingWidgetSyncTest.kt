package com.parkingkok.app.domain.widget

import com.parkingkok.app.data.parking.ParkingDatabase
import com.parkingkok.app.data.parking.RoomParkingRepository
import com.parkingkok.app.data.parking.createTestParkingDatabase
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §4 and §8: Room is canonical and the widget
 * projection is a derived cache that can never outlive the session it describes.
 *
 * Run against real SQLite rather than a fake repository, because the claim being tested is
 * about what the database actually holds.
 */
class ParkingWidgetSyncTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: RecordingProjectionStore

    private val start = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        store = RecordingProjectionStore()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sync(stepperEntitled: Boolean = true) =
        ParkingWidgetSync(repository, store, stepperEntitled)

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
    fun `refresh publishes the open record`() = runTest {
        repository.insert(record())

        sync().refresh()

        assertEquals("session-1", store.last?.sessionId)
        assertEquals("B3", store.last?.floorLabel)
    }

    @Test
    fun `startup repair clears a projection whose session has been completed`() = runTest {
        // The widget is still showing session-1 from before the process died.
        store.write(ParkingWidgetProjection.of(record(), stepperEntitled = true))
        // Room has since closed it — docs/06 §8's "completed record exists with the same
        // active sessionId".
        repository.insert(record(endedAt = start + 60_000L))

        sync().refresh()

        assertEquals(ParkingWidgetProjection.Empty, store.last)
    }

    @Test
    fun `startup repair clears a projection whose session was deleted outright`() = runTest {
        store.write(ParkingWidgetProjection.of(record(), stepperEntitled = true))

        sync().refresh()

        assertEquals(ParkingWidgetProjection.Empty, store.last)
    }

    @Test
    fun `refresh carries the entitlement answer into the snapshot`() = runTest {
        repository.insert(record())

        sync(stepperEntitled = false).refresh()

        assertEquals(false, store.last?.stepperEntitled)
        assertEquals(false, store.last?.showsStepper)
    }

    @Test
    fun `keepInSync republishes when the floor moves`() = collecting {
        repository.update("session-1") { it.copy(floor = FloorParser.parse("B2")) }

        withTimeout(TIMEOUT_MILLIS) { store.awaitFloor("B2") }
    }

    @Test
    fun `keepInSync empties the widget when the parking ends`() = collecting {
        repository.update("session-1") { it.copy(endedAtMillis = start + 60_000L) }

        withTimeout(TIMEOUT_MILLIS) { store.await { it == ParkingWidgetProjection.Empty } }
    }

    /**
     * Runs [body] with an open parking and a live [ParkingWidgetSync.keepInSync] collector.
     *
     * On [Dispatchers.Default] rather than the test scheduler: Room emits on its own
     * dispatcher, so a virtual clock would run the timeout out before any real query had
     * finished. The collector is cancelled before the test returns.
     */
    private fun collecting(body: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            repository.insert(record())
            val job = launch { sync().keepInSync() }
            withTimeout(TIMEOUT_MILLIS) { store.awaitFloor("B3") }
            try {
                body()
            } finally {
                job.cancel()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}

/** Stands in for the Glance state, which needs a widget host that a JVM test has not got. */
private class RecordingProjectionStore : WidgetProjectionStore {

    private val state = MutableStateFlow<ParkingWidgetProjection?>(null)

    val last: ParkingWidgetProjection? get() = state.value

    override suspend fun write(projection: ParkingWidgetProjection) {
        state.value = projection
    }

    suspend fun await(predicate: (ParkingWidgetProjection) -> Boolean) {
        state.first { it != null && predicate(it) }
    }

    suspend fun awaitFloor(label: String) = await { it.floorLabel == label }
}

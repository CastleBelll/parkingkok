package com.parkingpin.app.ui.notifications

import com.parkingpin.app.analytics.DetectionProperties
import com.parkingpin.app.data.DetectionStateStore
import com.parkingpin.app.data.InMemoryPreferencesDataStore
import com.parkingpin.app.data.parking.ParkingDatabase
import com.parkingpin.app.data.parking.RoomParkingRepository
import com.parkingpin.app.data.parking.createTestParkingDatabase
import com.parkingpin.app.detection.ConfirmedCandidateDetails
import com.parkingpin.app.detection.FakeCandidateNotifier
import com.parkingpin.app.detection.MutableTestClock
import com.parkingpin.app.detection.ParkingCandidateCoordinator
import com.parkingpin.app.domain.detection.CandidateOutcome
import com.parkingpin.app.domain.detection.ParkingCandidate
import com.parkingpin.app.domain.parking.ConfidenceBucket
import com.parkingpin.app.domain.parking.FloorParser
import com.parkingpin.app.domain.parking.usecase.EndParkingUseCase
import com.parkingpin.app.domain.parking.usecase.ObserveActiveParkingUseCase
import com.parkingpin.app.domain.parking.usecase.ObserveParkingHistoryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the bell's screen lists and what each row does when tapped
 * (docs/10_DESIGN_UX_SPEC.md §7b).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationHistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: DetectionStateStore
    private lateinit var coordinator: ParkingCandidateCoordinator

    private val start = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = start)
    private var nextId = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { repository },
            notifier = FakeCandidateNotifier(),
            clock = clock,
            idGenerator = { "id-${nextId++}" },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `the list is empty until a candidate has been raised`() = runTest {
        assertEquals(emptyList<NotificationRow>(), awaitRows())
    }

    @Test
    fun `a pending candidate leads the list and opens the confirmation screen`() = runTest {
        val candidate = newCandidate()

        val row = awaitRows().single()

        assertEquals(candidate.id, row.candidateId)
        assertEquals(candidate.detectedAtMillis, row.raisedAtMillis)
        // Still waiting for an answer, so there is no outcome and no record behind it.
        assertNull(row.outcome)
        assertNull(row.openRecordId)
        assertTrue(row.tappable)
    }

    @Test
    fun `a confirmed row names the floor it became and opens that record`() = runTest {
        val candidate = newCandidate()
        val recordId = confirm(candidate, floorRaw = "B3", zone = "A구역", spot = "142")

        val row = awaitRows().single()

        assertEquals(CandidateOutcome.CONFIRMED, row.outcome)
        assertEquals("B3 · A구역 142", row.place)
        assertEquals(recordId, row.openRecordId)
        assertTrue(row.tappable)
    }

    @Test
    fun `a rejected row does nothing and must not look tappable`() = runTest {
        val candidate = newCandidate()
        coordinator.reject(candidate.id)

        val row = awaitRows().single()

        assertEquals(CandidateOutcome.REJECTED, row.outcome)
        assertNull(row.openRecordId)
        assertFalse(row.tappable)
    }

    @Test
    fun `an unanswered row does nothing and must not look tappable`() = runTest {
        newCandidate()
        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS
        coordinator.expireIfDue()

        val row = awaitRows().single()

        assertEquals(CandidateOutcome.EXPIRED, row.outcome)
        assertNull(row.openRecordId)
        assertFalse(row.tappable)
    }

    @Test
    fun `a confirmed row whose record was deleted stops being tappable`() = runTest {
        val candidate = newCandidate()
        val recordId = confirm(candidate, floorRaw = "B3")
        repository.delete(recordId)

        val row = awaitRows { it.single().openRecordId == null }.single()

        // The line stays — it is what became of the candidate — but there is nothing
        // left to open, which §7b treats the same as a rejected row.
        assertEquals(CandidateOutcome.CONFIRMED, row.outcome)
        assertNull(row.place)
        assertFalse(row.tappable)
    }

    @Test
    fun `the newest is first and the pending one leads`() = runTest {
        val first = newCandidate()
        coordinator.reject(first.id)
        clock.epochMillis += 60_000L
        val second = newCandidate()
        clock.epochMillis += ParkingCandidate.LIFETIME_MILLIS
        coordinator.expireIfDue()
        clock.epochMillis += 60_000L
        val pending = newCandidate()

        val rows = awaitRows { it.size == 3 }

        assertEquals(listOf(pending.id, second.id, first.id), rows.map { it.candidateId })
    }

    private suspend fun awaitRows(
        until: (List<NotificationRow>) -> Boolean = { true },
    ): List<NotificationRow> {
        val viewModel = NotificationHistoryViewModel(
            observePending = coordinator::observePending,
            observeHistory = coordinator::observeHistory,
            observeActive = ObserveActiveParkingUseCase(repository),
            observeRecords = ObserveParkingHistoryUseCase(repository),
            clock = clock,
        )
        // Waits on the state rather than the scheduler: the record reads go through Room
        // on Dispatchers.IO, so `advanceUntilIdle` would return mid-query.
        return viewModel.uiState.first { it.loaded && until(it.rows) }.rows
    }

    private suspend fun newCandidate(): ParkingCandidate = coordinator.create(
        evidence = DetectionProperties(
            confidenceBucket = ConfidenceBucket.HIGH,
            walkingEvidence = true,
            gpsDegradation = false,
            optionalVehicleSignal = false,
        ),
        lastReliableLocation = null,
    )

    /** Confirms [candidate] and ends the parking, so the next one can be confirmed too. */
    private suspend fun confirm(
        candidate: ParkingCandidate,
        floorRaw: String,
        zone: String? = null,
        spot: String? = null,
    ): String {
        coordinator.confirm(
            candidate.id,
            ConfirmedCandidateDetails(
                floor = FloorParser.parse(floorRaw),
                zone = zone,
                spot = spot,
            ),
        )
        val record = requireNonNull(repository.findActive())
        clock.epochMillis += 60_000L
        EndParkingUseCase(repository, clock)()
        clock.epochMillis += 60_000L
        return record.id
    }

    private fun <T : Any> requireNonNull(value: T?): T = requireNotNull(value)
}

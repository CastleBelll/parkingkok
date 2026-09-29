package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.AnalyticsEvent
import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.detection.ParkingEndProposal
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingResult
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * docs/05 §11a (DECIDED 2026-09-29), end to end: a departure is **asked**, never ended
 * silently, and each of its answers does exactly one thing to the record.
 *
 * A real Room database and a real store, as in [ParkingCandidateCoordinatorTest]: the rules
 * are about what the record and the stored proposal say afterwards.
 */
class DepartureProposalTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: DetectionStateStore
    private lateinit var analytics: RecordingAnalytics
    private lateinit var notifier: FakeParkingEndProposalNotifier
    private lateinit var proposals: ParkingEndProposalCoordinator
    private lateinit var runtime: ParkingDetectionRuntime
    private val clock = MutableTestClock(epochMillis = START)
    private var captureStops = 0

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        analytics = RecordingAnalytics()
        notifier = FakeParkingEndProposalNotifier()
        wire()
    }

    /** Builds the coordinator and runtime over the current store — also a process restart. */
    private fun wire() {
        var nextId = 0
        val candidates = ParkingCandidateCoordinator(
            store = store,
            repository = { repository },
            notifier = FakeCandidateNotifier(),
            analytics = analytics,
            clock = clock,
            idGenerator = { "coordinator-${nextId++}" },
        )
        lateinit var built: ParkingDetectionRuntime
        proposals = ParkingEndProposalCoordinator(
            store = store,
            repository = { repository },
            notifier = notifier,
            clock = clock,
            analytics = analytics,
            keptParking = { at -> built.handleUserKeptParking(at) },
        )
        built = ParkingDetectionRuntime(
            store = store,
            candidates = { candidates },
            engine = ParkingDetectionEngine { "engine-${nextId++}" },
            proposeParkingEnd = { at -> proposals.propose(at) },
            stopLocationCapture = { captureStops++ },
        )
        runtime = built
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── Asked, not ended ────────────────────────────────────────────────────────────

    @Test
    fun `driving away asks and keeps the record open`() = runTest {
        // Arrange
        val parked = saveByHand("B3", spot = "142")

        // Act
        val departedAt = driveAway()

        // Assert — the record is open, the question is stored with the departure time, and
        // it is on screen in the words that never state the departure as fact.
        assertEquals(parked.id, repository.findActive()?.id)
        assertEquals(ParkingEndProposal(parked.id, departedAt), store.readParkingEndProposalOnce())
        assertEquals(parked.id, notifier.showing?.id)
        assertEquals(DetectionState.DRIVING, runtime.restore().state)
        assertFalse("nothing was ended, so nothing is reported yet", analytics.events.any { it is AnalyticsEvent.ParkingAutoEnd })
    }

    @Test
    fun `sitting in the parked car asks nothing`() = runTest {
        // Arrange
        saveByHand("B3")

        // Act — §11: vehicle evidence with no movement is a phone that woke up in the car.
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, DEPART))
        runtime.handleMotion(motion(MotionEventKind.STARTED_WALKING, DEPART + 200_000L))

        // Assert
        assertNull(store.readParkingEndProposalOnce())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `with notifications denied the question is still stored for the home card`() = runTest {
        // Arrange
        notifier.authorized = false
        val parked = saveByHand("B3")

        // Act
        driveAway()

        // Assert
        assertEquals(parked.id, store.readParkingEndProposalOnce()?.recordId)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a departure with nothing open asks nothing`() = runTest {
        // Arrange — parked, then ended by hand before driving off.
        saveByHand("B3")
        EndParkingUseCase(repository, clock)()

        // Act
        driveAway()

        // Assert
        assertNull(store.readParkingEndProposalOnce())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a later departure replaces the pending question`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        proposals.propose(DEPART)

        // Act
        proposals.propose(DEPART + 60_000L)

        // Assert — one at a time, the latest departure time.
        assertEquals(ParkingEndProposal(parked.id, DEPART + 60_000L), store.readParkingEndProposalOnce())
        assertEquals(parked.id, notifier.showing?.id)
    }

    // ── 주차 종료 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `accepting ends the record at the departure time and reports the auto end`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        val departedAt = driveAway()
        clock.epochMillis = departedAt + 900_000L

        // Act
        val closed = proposals.accept(parked.id)

        // Assert — closed when the car pulled away, not when the user answered.
        assertEquals(departedAt, closed?.endedAtMillis)
        assertNull(repository.findActive())
        assertNull(store.readParkingEndProposalOnce())
        assertNull("the question is withdrawn", notifier.showing)
        assertEquals(1, analytics.events.count { it is AnalyticsEvent.ParkingAutoEnd })
    }

    @Test
    fun `accepting after the parking was ended by hand ends nothing and reports nothing`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        driveAway()
        clock.epochMillis = DEPART + 900_000L
        EndParkingUseCase(repository, clock)()

        // Act
        val closed = proposals.accept(parked.id)

        // Assert — the hand end stands.
        assertNull(closed)
        assertEquals(DEPART + 900_000L, repository.find(parked.id)?.endedAtMillis)
        assertFalse(analytics.events.any { it is AnalyticsEvent.ParkingAutoEnd })
    }

    @Test
    fun `a stale tap about another record leaves the pending question alone`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        proposals.propose(DEPART)

        // Act
        val closed = proposals.accept("some-older-record")

        // Assert
        assertNull(closed)
        assertEquals(parked.id, repository.findActive()?.id)
        assertEquals(parked.id, store.readParkingEndProposalOnce()?.recordId)
    }

    // ── 아직 주차 중 ──────────────────────────────────────────────────────────────────

    @Test
    fun `keeping the parking keeps the record and returns the engine to PARKED`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        driveAway()
        val stopsBefore = captureStops

        // Act
        val answered = proposals.keep(parked.id)

        // Assert — user_kept_parking: PARKED, the drive dropped, the record untouched.
        assertTrue(answered)
        assertEquals(parked.id, repository.findActive()?.id)
        val engine = runtime.restore()
        assertEquals(DetectionState.PARKED, engine.state)
        assertNull(engine.session)
        assertEquals("the drive's capture is stopped, as a hand save stops it", stopsBefore + 1, captureStops)
        assertNull(store.readParkingEndProposalOnce())
        assertNull(notifier.showing)
        assertFalse(analytics.events.any { it is AnalyticsEvent.ParkingAutoEnd })
    }

    @Test
    fun `keeping with no pending question tells the engine nothing`() = runTest {
        // Arrange
        saveByHand("B3")
        driveAway()
        proposals.accept()

        // Act
        val answered = proposals.keep()

        // Assert
        assertFalse(answered)
        assertEquals(DetectionState.DRIVING, runtime.restore().state)
    }

    // ── Ignored ──────────────────────────────────────────────────────────────────────

    @Test
    fun `an ignored question survives a process death`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        val departedAt = driveAway()

        // Act — a new process over the same store.
        wire()

        // Assert
        assertEquals(ParkingEndProposal(parked.id, departedAt), proposals.observePending().first())
        assertEquals(parked.id, repository.findActive()?.id)
    }

    @Test
    fun `the next parking saved by hand ends the old record at the departure time`() = runTest {
        // Arrange
        val old = saveByHand("B3")
        val departedAt = driveAway()
        clock.epochMillis = departedAt + 1_800_000L

        // Act — what ManualParkingViewModel does: the save closes the asked-about record in
        // its own write, and the question is retired once that write committed.
        val saved = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-new" },
        )(ManualParkingInput(floorRaw = "2F"), ending = proposals.pendingEnd())
        (saved as? SaveManualParkingResult.Saved)?.endedPrevious?.let { proposals.retire(it.id) }

        // Assert — the old one closed when the car left, the new one open, the question gone.
        assertTrue(saved is SaveManualParkingResult.Saved)
        assertEquals(departedAt, repository.find(old.id)?.endedAtMillis)
        assertEquals("record-new", repository.findActive()?.id)
        assertNull(store.readParkingEndProposalOnce())
        assertNull(notifier.showing)
        assertFalse(
            "docs/17: parking_auto_end is the user's acceptance, not a save",
            analytics.events.any { it is AnalyticsEvent.ParkingAutoEnd },
        )
    }

    @Test
    fun `a question whose record was deleted is dropped when the app looks`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        driveAway()
        repository.delete(parked.id)

        // Act
        proposals.dropIfStale()

        // Assert
        assertNull(store.readParkingEndProposalOnce())
        assertNull(notifier.showing)
    }

    @Test
    fun `a live question survives the app looking`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        driveAway()

        // Act
        proposals.dropIfStale()

        // Assert
        assertEquals(parked.id, store.readParkingEndProposalOnce()?.recordId)
        assertEquals(parked.id, notifier.showing?.id)
    }

    @Test
    fun `ending the parking by hand withdraws the question`() = runTest {
        // Arrange
        val parked = saveByHand("B3")
        driveAway()
        clock.epochMillis = DEPART + 900_000L

        // Act — what the home and detail screens do.
        EndParkingUseCase(repository, clock)()
        proposals.withdraw()

        // Assert — the hand end's time stands, and the question is gone.
        assertEquals(DEPART + 900_000L, repository.find(parked.id)?.endedAtMillis)
        assertNull(store.readParkingEndProposalOnce())
        assertNull(notifier.showing)
    }

    private suspend fun saveByHand(floor: String, spot: String? = null): ParkingRecord {
        val result = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-1" },
        )(ManualParkingInput(floorRaw = floor, spot = spot))
        runtime.handleUserSavedParking(clock.nowEpochMillis())
        return (result as SaveManualParkingResult.Saved).record
    }

    /**
     * §11's bars and then §7's: 90 s of vehicle activity and real distance covered. Returns
     * when the machine entered `DEPARTURE_CANDIDATE` — the time §11a proposes.
     */
    private suspend fun driveAway(): Long {
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, DEPART))
        var north = 0.0
        var at = DEPART
        var departureEnteredAt: Long? = null
        repeat(12) {
            at += 30_000L
            north += 300.0
            runtime.handleLocations(listOf(fix(at, north)))
            val state = runtime.restore()
            if (state.state == DetectionState.DEPARTURE_CANDIDATE && departureEnteredAt == null) {
                departureEnteredAt = state.stateEnteredAtMillis
            }
        }
        return checkNotNull(departureEnteredAt) { "never reached DEPARTURE_CANDIDATE" }
    }

    private fun fix(atMillis: Long, north: Double) = LocationSample(
        atMillis = atMillis,
        latitude = 37.5 + north / 111_320.0,
        longitude = 127.0,
        horizontalAccuracyM = 8f,
        speedMps = 12f,
    )

    private fun motion(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis)

    private companion object {
        const val START = 1_700_000_000_000L
        const val DEPART = START + 3_600_000L
    }
}

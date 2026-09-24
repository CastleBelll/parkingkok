package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The seam the DEV hook used to bypass: a detected parking has to reach the same stored
 * candidate, the same notification and the same checkpoint a manual injection did.
 *
 * Real `DetectionStateStore` over an in-memory DataStore and a real coordinator, because
 * what is under test is precisely that the engine's decision survives a write and that the
 * two stores agree about which candidate is on screen.
 */
class ParkingDetectionRuntimeTest {

    private lateinit var database: ParkingDatabase
    private lateinit var store: DetectionStateStore
    private lateinit var notifier: FakeCandidateNotifier
    private lateinit var coordinator: ParkingCandidateCoordinator
    private lateinit var runtime: ParkingDetectionRuntime

    private val clock = MutableTestClock(epochMillis = START)

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        notifier = FakeCandidateNotifier()
        coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { RoomParkingRepository(database.parkingRecordDao()) },
            notifier = notifier,
            clock = clock,
            idGenerator = { "unexpected-coordinator-id" },
            analytics = RecordingAnalytics(),
        )
        var nextId = 0
        runtime = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            engine = ParkingDetectionEngine { "engine-candidate-${nextId++}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a detected drive posts the notification the confirmation screen can open`() = runTest {
        driveAndPark()

        val candidate = assertNotNull(store.readCandidateOnce())
        assertEquals(listOf(candidate), notifier.showing)
        assertEquals(
            "the id the engine minted is the id everything else uses",
            "engine-candidate-0",
            candidate.id,
        )
        assertEquals(candidate, coordinator.pending(candidate.id))
    }

    @Test
    fun `the checkpoint names the pending candidate`() = runTest {
        driveAndPark()

        val checkpoint = assertNotNull(store.readCheckpointOnce())
        assertEquals(DetectionState.CANDIDATE_PENDING, checkpoint.state)
        assertEquals("engine-candidate-0", checkpoint.candidateId)
        assertTrue("the write advances the revision", checkpoint.revision > 0)
    }

    @Test
    fun `the engine state survives to the next process`() = runTest {
        driveAndPark()

        // A fresh runtime over the same store is what a broadcast-started process sees.
        val restored = ParkingDetectionRuntime(store, { coordinator }).restore()

        assertEquals(DetectionState.CANDIDATE_PENDING, restored.state)
        assertEquals("engine-candidate-0", assertNotNull(restored.candidate).id)
    }

    @Test
    fun `a car link reconnect withdraws the notification without reporting a rejection`() = runTest {
        // The link opens the session, but §3a does not let it promote on its own — people
        // sit in parked cars — so the drive that makes the disconnect mean something is
        // motion's to report.
        runtime.handleCarLink(DetectionEvent.CarLinkConnected(START))
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        runtime.handleCarLink(DetectionEvent.CarLinkDisconnected(START + DRIVE_MILLIS))
        val candidate = assertNotNull(store.readCandidateOnce())

        runtime.handleCarLink(
            DetectionEvent.CarLinkConnected(START + DRIVE_MILLIS + 120_000),
        )

        assertNull("the fuel stop retires it", store.readCandidateOnce())
        assertEquals(listOf(candidate.id), notifier.withdrawn)
        assertEquals(emptyList<Any>(), notifier.showing)
        assertEquals(DetectionState.DRIVING, runtime.restore().state)
    }

    @Test
    fun `rejecting returns the machine to IDLE so the next trip is heard`() = runTest {
        driveAndPark()
        val candidate = assertNotNull(store.readCandidateOnce())

        coordinator.reject(candidate.id)
        runtime.handleUserAnswer(DetectionEvent.UserRejectedParking(START + DRIVE_MILLIS + 60_000))

        assertEquals(DetectionState.IDLE, runtime.restore().state)
        assertNull(store.readCandidateOnce())
    }

    @Test
    fun `a hand save while a prompt is up withdraws it and parks the machine`() = runTest {
        // Arrange — docs/05 §11c: the user saved the parking themselves instead of answering.
        driveAndPark()
        val candidate = assertNotNull(store.readCandidateOnce())

        // Act
        runtime.handleUserSavedParking(START + DRIVE_MILLIS + 60_000)

        // Assert — retired, which is not a rejection, and PARKED so §11 watches the departure.
        assertEquals(DetectionState.PARKED, runtime.restore().state)
        assertNull(store.readCandidateOnce())
        assertEquals(listOf(candidate.id), notifier.withdrawn)
    }

    @Test
    fun `a hand save stops the location capture`() = runTest {
        // Arrange — the Fused Location request is not the engine's to own, so the runtime
        // is what reaches it when the user has answered the question capture was for.
        var stops = 0
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            stopLocationCapture = { stops++ },
        )
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))

        // Act
        withCapture.handleUserSavedParking(START + 60_000)

        // Assert
        assertEquals(1, stops)
        assertEquals(DetectionState.PARKED, withCapture.restore().state)
    }

    @Test
    fun `a low confidence candidate is stored and not announced`() = runTest {
        // A drive with nothing but a 90-second vehicle stretch behind it: §7's duration and
        // distance clauses are both unmet, so §8's `trip below minimum` applies and §9 puts
        // it under the notification bar.
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        runtime.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + 100_000))
        runtime.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + 110_000))

        val candidate = assertNotNull(store.readCandidateOnce())
        assertEquals(ConfidenceBucket.LOW, candidate.confidenceBucket)
        assertEquals("§9: low posts nothing but is still recorded", emptyList<Any>(), notifier.posted)
    }

    @Test
    fun `a denied notification permission loses nothing`() = runTest {
        notifier.authorized = false

        driveAndPark()

        assertEquals(emptyList<Any>(), notifier.posted)
        assertNotNull("the candidate is still there for the next app launch", store.readCandidateOnce())
        assertEquals(DetectionState.CANDIDATE_PENDING, runtime.restore().state)
    }

    /** The §3a motion path, end to end, with no car link anywhere in it. */
    private suspend fun driveAndPark() {
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        runtime.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + DRIVE_MILLIS))
        runtime.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + DRIVE_MILLIS + 20_000))
    }

    @Test
    fun `a tick closes a drive that stopped producing events`() = runTest {
        // docs/05 §3a's remaining half. The engine settles the timeout rows against each
        // event's own timestamp, which covers every drive that keeps producing events; this
        // is the one that stops — underground, no `vehicle_exit`, no fixes because there is
        // no sky, no walk transition delivered. Nothing would ever end it, and the location
        // foreground service would stay up behind it.
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + SUSTAIN))
        assertEquals(DetectionState.DRIVING, runtime.restore().state)

        runtime.handleTick(START + ParkingDetectionEngine.SESSION_MAXIMUM_DURATION_MILLIS)

        // The two-hour ceiling, which is the only row that bounds a drive with no movement
        // evidence at all — `movementIdleWindow` correctly declines to fire on one.
        val settled = runtime.restore()
        assertEquals(DetectionState.IDLE, settled.state)
        assertNull("the session ends with it, which is what stops the capture", settled.session)
    }

    private fun motion(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis)

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull("expected a value", value)
        return checkNotNull(value)
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val DRIVE_MILLIS = 420_000L
        const val SUSTAIN = ParkingDetectionEngine.MINIMUM_VEHICLE_DURATION_MILLIS
    }
}

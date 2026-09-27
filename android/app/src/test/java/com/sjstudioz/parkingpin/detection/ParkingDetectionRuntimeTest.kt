package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionActivity
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.detection.TransitionKind
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.location.LocationSessionMode
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the capture follows the engine out of the transition`() = runTest {
        // Arrange — docs/05 §3a / §19: the runtime reports what the engine wants of the
        // capture whenever that changes, because no motion event marks these edges.
        val follows = mutableListOf<Pair<LocationSessionMode?, LocationSessionMode?>>()
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> follows += before to after },
        )

        // Act
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        withCapture.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + DRIVE_MILLIS))
        withCapture.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + DRIVE_MILLIS + 20_000))

        // Assert — the exit batch promotes and ends the drive at once; the walk confirms.
        assertEquals(
            listOf(
                null to LocationSessionMode.DRIVING_CANDIDATE,
                LocationSessionMode.DRIVING_CANDIDATE to LocationSessionMode.PARKING_TRANSITION,
                LocationSessionMode.PARKING_TRANSITION to null,
            ),
            follows,
        )
    }

    @Test
    fun `a stop-only candidate keeps the capture until transitionWindow after the drive ended`() = runTest {
        // Arrange — docs/05 §3a "A stop-only candidate can still be a long light" rule 1 and
        // §19: the resume rows are location rows, so the capture outlives the candidate until
        // the transition's own deadline, and is released at the tick that reaches it.
        val follows = mutableListOf<Pair<LocationSessionMode?, LocationSessionMode?>>()
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> follows += before to after },
        )
        driveToStopOnlyCandidate(withCapture)
        val deadline = STOP_ONLY_DRIVE_END + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS

        // Act
        withCapture.handleTick(deadline - 1)
        val beforeDeadline = follows.edges()
        withCapture.handleTick(deadline)

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, withCapture.restore().state)
        assertEquals(
            "no release while the window is open",
            listOf(
                null to LocationSessionMode.DRIVING_CANDIDATE,
                LocationSessionMode.DRIVING_CANDIDATE to LocationSessionMode.DRIVING,
                LocationSessionMode.DRIVING to LocationSessionMode.PARKING_TRANSITION,
            ),
            beforeDeadline,
        )
        assertEquals(LocationSessionMode.PARKING_TRANSITION to null, follows.last())
        assertEquals(4, follows.edges().size)
    }

    @Test
    fun `the capture is reconciled after every batch, not only when the want changed`() = runTest {
        // Arrange — docs/05 §19: a capture whose owner went away with no state change (a
        // stop-only window closed at a deadline no event reached, an exit the engine refused)
        // has no want edge, so an edge-only follow would never release it.
        val follows = mutableListOf<Pair<LocationSessionMode?, LocationSessionMode?>>()
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> follows += before to after },
        )

        // Act — two batches that leave the engine where it was.
        withCapture.handleMotion(motion(MotionEventKind.STARTED_WALKING, START))
        withCapture.handleMotion(motion(MotionEventKind.BECAME_STATIONARY, START + 1_000))

        // Assert
        assertEquals(listOf<Pair<LocationSessionMode?, LocationSessionMode?>>(null to null, null to null), follows)
    }

    @Test
    fun `a stop-only resume window survives a process death while its capture does`() = runTest {
        // Arrange — docs/05 §3a "The window lives exactly as long as its capture": on Android
        // the Fused Location request is a PendingIntent Play services keeps delivering to a new
        // process, so the window a broadcast-started process reloads is still backed by one.
        driveToStopOnlyCandidate(runtime)
        val candidate = assertNotNull(store.readCandidateOnce())
        val restarted = ParkingDetectionRuntime(store, { coordinator }, captureRunning = { true })

        // Act — the two reported-moving fixes that resume the drive.
        restarted.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 20_000, speedMps = 9f)))
        restarted.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 30_000, speedMps = 9f)))

        // Assert
        assertEquals(DetectionState.DRIVING, restarted.restore().state)
        assertEquals(listOf(candidate.id), notifier.withdrawn)
    }

    @Test
    fun `a stop-only resume window whose capture is gone closes and the candidate stays`() = runTest {
        // Arrange — reboot, force-stop, app update or a revoked permission took the capture:
        // §3a rule 4's lost capture — the window goes, the candidate stays — before any event
        // of the new process is handled. §19: that window wants no capture either.
        driveToStopOnlyCandidate(runtime)
        val candidate = assertNotNull(store.readCandidateOnce())
        val follows = mutableListOf<Pair<LocationSessionMode?, LocationSessionMode?>>()
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> follows += before to after },
            captureRunning = { false },
        )

        // Act
        val seen = restarted.restore()
        restarted.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 20_000, speedMps = 9f)))
        restarted.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 30_000, speedMps = 9f)))

        // Assert
        assertNull("closed before the first event", seen.stopOnlyResumeWindow)
        val state = restarted.restore()
        assertNull(state.stopOnlyResumeWindow)
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertEquals(candidate, store.readCandidateOnce())
        assertEquals(emptyList<String>(), notifier.withdrawn)
        assertEquals(listOf<Pair<LocationSessionMode?, LocationSessionMode?>>(null to null, null to null), follows)
    }

    @Test
    fun `a reboot inside a stop-only window closes it`() = runTest {
        // Arrange — the whole Android path: the recovery receiver tells the controller the
        // system dropped its request, and the runtime reads the capture from the controller.
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        clock.epochMillis = STOP_ONLY_DRIVE_END
        driveToStopOnlyCandidate(withCapture)

        // Act
        controller.reconcileAfterSystemReset()
        val afterBoot = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            captureRunning = { controller.isCaptureRunning() },
        )

        // Assert
        val state = afterBoot.restore()
        assertNull(state.stopOnlyResumeWindow)
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
    }

    @Test
    fun `a stop-only resume window still resumes within the process that opened it`() = runTest {
        // Arrange
        driveToStopOnlyCandidate(runtime)
        val candidate = assertNotNull(store.readCandidateOnce())

        // Act
        runtime.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 20_000, speedMps = 9f)))
        runtime.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 30_000, speedMps = 9f)))

        // Assert
        assertEquals(DetectionState.DRIVING, runtime.restore().state)
        assertEquals(listOf(candidate.id), notifier.withdrawn)
    }

    @Test
    fun `an exit inside a stop-only window whose capture ended opens no kerb capture`() = runTest {
        // Arrange — docs/05 §19 rule 2 and 4: the capture ended while the window was still
        // open, so the window no longer holds it and wants nothing; the exit that follows must
        // not open a 300 s high-accuracy capture, not even for one batch.
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        clock.epochMillis = STOP_ONLY_DRIVE_END
        driveToStopOnlyCandidate(withCapture)
        controller.stop()
        val requestsBefore = registrar.requestedConfigs.size
        val ingestor = TransitionEventIngestor(store, clock, controller, withCapture)

        // Act
        ingestor.ingest(
            listOf(
                TransitionEventIngestor.RawTransition(
                    MotionActivity.IN_VEHICLE,
                    TransitionKind.EXIT,
                    STOP_ONLY_DRIVE_END + 60_000,
                ),
            ),
        )

        // Assert
        assertEquals("no kerb request was made", requestsBefore, registrar.requestedConfigs.size)
        assertFalse(registrar.isRegistered)
        assertNull(withCapture.restore().stopOnlyResumeWindow)
        assertEquals(DetectionState.CANDIDATE_PENDING, withCapture.restore().state)
    }

    @Test
    fun `a capture lost inside the transition leaves a stop-only candidate no resume window`() = runTest {
        // Arrange — R4-B1, docs/05 §3a / §19 "an open window always holds its capture": the
        // location permission is revoked while PARKING_TRANSITION decides, so the stop-only
        // candidate that stillness then confirms has no capture to hold. iOS twin:
        // `ParkingTransitionEvidenceTests` "A stop-only candidate whose transition lost its
        // capture opens no resume window" — the same sequence must end in the same state with
        // the same candidate on both platforms.
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        withCapture.handleTick(START + SUSTAIN)
        withCapture.handleLocations(listOf(fix(START + 100_000, speedMps = 9f)))
        withCapture.handleTick(STOP_ONLY_DRIVE_END)
        assertEquals(DetectionState.PARKING_TRANSITION, withCapture.restore().state)
        registrar.foregroundGranted = false
        withCapture.handleMotion(motion(MotionEventKind.BECAME_STATIONARY, STOP_ONLY_DRIVE_END + 20_000))
        val candidate = assertNotNull(store.readCandidateOnce())
        assertNotNull("the engine alone opened a stop-only window", store.readEngineStateOnce()?.stopOnlyResumeWindow)

        // Act — back in a vehicle while the window would still have been open.
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, STOP_ONLY_DRIVE_END + 50_000))

        // Assert — §3a "Leaving a pending candidate behind", not a resume.
        val state = withCapture.restore()
        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
        assertEquals(candidate.id, state.candidate?.id)
        assertEquals(candidate, store.readCandidateOnce())
        assertEquals(emptyList<String>(), notifier.withdrawn)
        assertFalse("no capture without the permission", registrar.isRegistered)
    }

    /**
     * Sustained vehicle evidence, one moving fix, then stopped fixes: `movementIdle` ends the
     * drive at [STOP_ONLY_DRIVE_END] and the same stopped fix confirms it — a silent low
     * candidate with no exit, no link and no walk, which is what opens a stop-only window.
     */
    private suspend fun driveToStopOnlyCandidate(target: ParkingDetectionRuntime) {
        target.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        target.handleTick(START + SUSTAIN)
        target.handleLocations(listOf(fix(START + 100_000, speedMps = 9f)))
        target.handleLocations(listOf(fix(START + 200_000, speedMps = 0f)))
        target.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END, speedMps = 0f)))
        assertNotNull("a stop-only window is open", target.restore().stopOnlyResumeWindow)
    }

    @Test
    fun `a departure survives a process death and still ends the parking`() = runTest {
        // Arrange — docs/05 §11 / §14 (R2-B3): a hand-saved parking, then §11's two bars
        // cleared (100 s in the car, 600 m) and §7's guard still unmet. The engine state is
        // written after every batch, and a broadcast-started process reloads it whole, so a
        // restart inside DEPARTURE_CANDIDATE keeps the departure's session. iOS twin requested:
        // a `restore` of DEPARTURE_CANDIDATE that rebuilds the departure, not one that drops it.
        runtime.handleUserSavedParking(START)
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 600_000))
        runtime.handleLocations(listOf(fixNorth(START + 610_000, northMeters = 0.0)))
        runtime.handleLocations(listOf(fixNorth(START + 700_000, northMeters = 600.0)))
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, runtime.restore().state)
        val endedAt = mutableListOf<Long>()
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            endParking = { at -> endedAt += at; null },
        )

        // Act — the next fix meets §7's guard (160 s, 1 300 m).
        restarted.handleLocations(listOf(fixNorth(START + 760_000, northMeters = 1_300.0)))

        // Assert — confirmed, and the parking ends at the DEPARTURE_CANDIDATE entry.
        assertEquals(DetectionState.DRIVING, restarted.restore().state)
        assertEquals(listOf(START + 700_000), endedAt)
    }

    /** The want changes only; the per-batch calls in between repeat the same want. */
    private fun List<Pair<LocationSessionMode?, LocationSessionMode?>>.edges() = filter { it.first != it.second }

    @Test
    fun `a walk-confirmed candidate releases the capture at once`() = runTest {
        // Arrange
        val follows = mutableListOf<Pair<LocationSessionMode?, LocationSessionMode?>>()
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> follows += before to after },
        )
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        withCapture.handleTick(START + SUSTAIN)
        withCapture.handleLocations(listOf(fix(START + 100_000, speedMps = 9f)))
        withCapture.handleTick(START + 280_000)

        // Act
        withCapture.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + 300_000))

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, withCapture.restore().state)
        assertEquals(LocationSessionMode.PARKING_TRANSITION to null, follows.last())
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

    /** A fix at the origin: these tests are about time and reported speed, not distance. */
    private fun fix(atMillis: Long, speedMps: Float?) =
        LocationSample(atMillis = atMillis, latitude = 37.5, longitude = 127.0, horizontalAccuracyM = 8f, speedMps = speedMps)

    /** A driving-speed fix [northMeters] up the meridian, for the departure's distance bar. */
    private fun fixNorth(atMillis: Long, northMeters: Double) = LocationSample(
        atMillis = atMillis,
        latitude = 37.5 + northMeters / METERS_PER_DEGREE_LATITUDE,
        longitude = 127.0,
        horizontalAccuracyM = 8f,
        speedMps = 12f,
    )

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull("expected a value", value)
        return checkNotNull(value)
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val DRIVE_MILLIS = 420_000L
        const val SUSTAIN = ParkingDetectionEngine.MINIMUM_VEHICLE_DURATION_MILLIS
        const val STOP_ONLY_DRIVE_END = START + 280_000L
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    }
}

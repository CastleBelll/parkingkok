package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.domain.detection.DetectionEffect
import com.sjstudioz.parkingpin.domain.detection.DetectionEngineState
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionActivity
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.detection.TransitionKind
import com.sjstudioz.parkingpin.domain.location.DrivingConfirmationGuard
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

    // ── docs/05 §3a / §14 "The capture did not survive": a system reset inside a stop-only
    // window reopens the capture the stored state wants, and that capture carries the window.
    // iOS twins, same names: `ParkingTransitionEvidenceTests`.

    /**
     * The whole Android reset path over a real controller: the recovery receiver tells the
     * controller the system dropped its request, then asks the runtime to resume at [resetAt].
     */
    private suspend fun rebootInsideStopOnlyWindow(
        registrar: FakeLocationSessionRegistrar,
        resetAt: Long,
        /** What changes while the phone is down, e.g. a permission revoked in settings. */
        whileDown: () -> Unit = {},
    ): ParkingDetectionRuntime {
        val controller = FusedLocationSessionController(store, registrar, clock)
        fun capturingRuntime() = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        clock.epochMillis = STOP_ONLY_DRIVE_END
        driveToStopOnlyCandidate(capturingRuntime())
        assertTrue("the window holds a capture before the reset", registrar.isRegistered)
        controller.reconcileAfterSystemReset()
        assertFalse(registrar.isRegistered)
        whileDown()
        clock.epochMillis = resetAt
        val afterBoot = capturingRuntime()
        afterBoot.resumeAfterSystemReset(resetAt)
        return afterBoot
    }

    @Test
    fun `a relaunch inside a stop-only window reopens its capture and keeps the resume`() = runTest {
        // Arrange — the reset lands 20 s into the window (iOS: relaunch at 300 s, drive end 280 s).
        val registrar = FakeLocationSessionRegistrar()
        val afterBoot = rebootInsideStopOnlyWindow(registrar, resetAt = STOP_ONLY_DRIVE_END + 20_000)
        val candidate = assertNotNull(store.readCandidateOnce())
        val restored = afterBoot.restore()
        val reopened = registrar.isRegistered

        // Act — the jam moves on: two fixes reporting driving speed.
        clock.epochMillis = STOP_ONLY_DRIVE_END + 40_000
        afterBoot.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 40_000, speedMps = 8f)))
        clock.epochMillis = STOP_ONLY_DRIVE_END + 55_000
        afterBoot.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 55_000, speedMps = 9f)))

        // Assert
        assertTrue("the capture the window wants is reopened", reopened)
        assertNotNull("the reopened capture carries the window", restored.stopOnlyResumeWindow)
        assertEquals(listOf(candidate.id), notifier.withdrawn)
        assertEquals(DetectionState.DRIVING, afterBoot.restore().state)
    }

    @Test
    fun `a relaunch after a stop-only window lapsed reopens no capture and keeps the candidate`() = runTest {
        // Arrange — the reset lands 60 s past the window's deadline.
        val registrar = FakeLocationSessionRegistrar()
        val deadline = STOP_ONLY_DRIVE_END + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS

        // Act
        val afterBoot = rebootInsideStopOnlyWindow(registrar, resetAt = deadline + 60_000)

        // Assert
        val state = afterBoot.restore()
        assertFalse("nothing is reopened for a lapsed window", registrar.isRegistered)
        assertNull(state.stopOnlyResumeWindow)
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertNotNull("the candidate is kept", store.readCandidateOnce())
        assertEquals(emptyList<String>(), notifier.withdrawn)
    }

    @Test
    fun `a relaunch inside a stop-only window whose capture cannot reopen closes it and keeps the candidate`() =
        runTest {
            // Arrange — docs/05 §3a / §14 step 3's exception: background location went to "only
            // while using" while the phone was down, so the reopened capture cannot deliver.
            // That is rule 4's lost capture: the window closes, the candidate stays.
            val registrar = FakeLocationSessionRegistrar()
            val afterBoot = rebootInsideStopOnlyWindow(registrar, resetAt = STOP_ONLY_DRIVE_END + 20_000) {
                registrar.backgroundGranted = false
            }
            val candidate = assertNotNull(store.readCandidateOnce())
            val afterReset = afterBoot.restore()
            val runningAfterReset = registrar.isRegistered

            // Act — back in a vehicle while the window would still have been open.
            afterBoot.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, STOP_ONLY_DRIVE_END + 50_000))

            // Assert — a new journey, not the jam moving on.
            assertNull("no capture holds the window, so it closes", afterReset.stopOnlyResumeWindow)
            assertFalse("the reset leaves nothing running", runningAfterReset)
            assertEquals(emptyList<String>(), notifier.withdrawn)
            assertEquals(candidate, store.readCandidateOnce())
            assertEquals(DetectionState.DRIVING_CANDIDATE, afterBoot.restore().state)
        }

    @Test
    fun `a reboot inside a stop-only window with location revoked closes it and keeps the candidate`() = runTest {
        // Arrange
        val registrar = FakeLocationSessionRegistrar()

        // Act
        val afterBoot = rebootInsideStopOnlyWindow(registrar, resetAt = STOP_ONLY_DRIVE_END + 20_000) {
            registrar.foregroundGranted = false
            registrar.backgroundGranted = false
        }

        // Assert
        val state = afterBoot.restore()
        assertNull(state.stopOnlyResumeWindow)
        assertFalse(registrar.isRegistered)
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertNotNull("the candidate is kept", store.readCandidateOnce())
        assertEquals(emptyList<String>(), notifier.withdrawn)
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
    fun `a stop-only candidate whose transition lost its capture opens no resume window`() = runTest {
        // Arrange — R4-B1, docs/05 §3a / §19 "an open window always holds its capture": the
        // location permission is revoked while PARKING_TRANSITION decides, so the stop-only
        // candidate that stillness then confirms has no capture to hold. iOS twin, same name:
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
        assertNull("no capture holds the window, so it is closed at once", store.readEngineStateOnce()?.stopOnlyResumeWindow)

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

    @Test
    fun `background location revoked inside a stop-only window closes it and vehicle_enter keeps the candidate`() = runTest {
        // Arrange — docs/05 §3a rule 4: a downgrade to "only while using" stops Play services
        // delivering to the PendingIntent in the background, so the window's capture is gone
        // even though foreground location is still granted.
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
        val candidate = assertNotNull(store.readCandidateOnce())
        registrar.backgroundGranted = false

        // Act — back in a vehicle while the window would still have been open.
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, STOP_ONLY_DRIVE_END + 50_000))

        // Assert — CANDIDATE_PENDING -> DRIVING_CANDIDATE, the candidate kept.
        val state = withCapture.restore()
        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
        assertEquals(candidate.id, state.candidate?.id)
        assertEquals(emptyList<String>(), notifier.withdrawn)
    }

    // ── The Smart Detection opt-out (docs/05 §11 / §19) ────────────────────────────────
    //
    // Turning detection off must stop every capture and close every window, and nothing may
    // open one again while it is off. The engine state it leaves is iOS's
    // `endDrivingSession(reason: .smartDetectionDisabled)`.

    /** Coordinator, runtime and controller wired the way `AppContainer` wires them. */
    private class OptOutHarness(
        val registrar: FakeLocationSessionRegistrar,
        val controller: FusedLocationSessionController,
        val runtime: ParkingDetectionRuntime,
        val registration: DetectionRegistrationCoordinator,
    )

    private fun optOutHarness(registrar: FakeLocationSessionRegistrar = FakeLocationSessionRegistrar()): OptOutHarness {
        val controller = FusedLocationSessionController(store, registrar, clock)
        var nextId = 0
        val wired = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            engine = ParkingDetectionEngine { "opt-out-candidate-${nextId++}" },
            stopLocationCapture = { controller.stop() },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
            detectionEnabled = { store.readDesiredEnabledOnce() },
        )
        val registration = DetectionRegistrationCoordinator(
            store = store,
            registrar = FakeTransitionRegistrar(),
            clock = clock,
            optOut = { atMillis -> wired.handleSmartDetectionDisabled(atMillis) },
        )
        return OptOutHarness(registrar, controller, wired, registration)
    }

    @Test
    fun `a car link while opted out opens no capture`() = runTest {
        // Arrange
        val h = optOutHarness()
        h.registration.setDetectionEnabled(false)

        // Act
        h.runtime.handleCarLink(DetectionEvent.CarLinkConnected(START))

        // Assert
        assertTrue("no Fused Location request", h.registrar.requestedConfigs.isEmpty())
        assertFalse(h.registrar.isRegistered)
        assertEquals(DetectionState.IDLE, h.runtime.restore().state)
    }

    @Test
    fun `a location batch while Smart Detection is off feeds nothing and releases the capture`() = runTest {
        // Arrange — a capture a drive opened, and the flag off with the capture still running.
        val h = optOutHarness()
        h.registration.setDetectionEnabled(true)
        h.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        h.runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        store.setDesiredEnabled(false)
        val before = h.runtime.restore()

        // Act
        h.runtime.handleLocations(listOf(fix(START + 30_000, speedMps = 9f)))

        // Assert
        assertEquals("the engine is not fed", before, h.runtime.restore())
        assertFalse("the capture is released", h.registrar.isRegistered)
    }

    @Test
    fun `opting out while driving stops the capture and ends the drive in IDLE`() = runTest {
        // Arrange
        val h = optOutHarness()
        h.registration.setDetectionEnabled(true)
        h.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        h.runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        h.runtime.handleTick(START + SUSTAIN)
        h.runtime.handleLocations(listOf(fix(START + 100_000, speedMps = 9f)))
        assertEquals(DetectionState.DRIVING, h.runtime.restore().state)
        assertTrue(h.registrar.isRegistered)
        clock.epochMillis = START + 120_000

        // Act
        h.registration.setDetectionEnabled(false)
        h.runtime.handleCarLink(DetectionEvent.CarLinkConnected(START + 130_000))

        // Assert
        val state = h.runtime.restore()
        assertEquals(DetectionState.IDLE, state.state)
        assertNull(state.session)
        assertFalse("the capture is stopped", h.registrar.isRegistered)
        assertFalse("and nothing reopens it", h.controller.isCaptureRunning())
    }

    @Test
    fun `opting out inside a stop-only window closes it and keeps the candidate`() = runTest {
        // Arrange
        val h = optOutHarness()
        h.registration.setDetectionEnabled(true)
        h.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        clock.epochMillis = STOP_ONLY_DRIVE_END
        driveToStopOnlyCandidate(h.runtime)
        val candidate = assertNotNull(store.readCandidateOnce())
        assertTrue(h.registrar.isRegistered)

        // Act
        clock.epochMillis = STOP_ONLY_DRIVE_END + 20_000
        h.registration.setDetectionEnabled(false)
        h.runtime.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 30_000, speedMps = 9f)))
        h.runtime.handleLocations(listOf(fix(STOP_ONLY_DRIVE_END + 40_000, speedMps = 9f)))

        // Assert — rule 4: the window closes, the candidate stands, nothing is withdrawn.
        val state = h.runtime.restore()
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertNull(state.stopOnlyResumeWindow)
        assertEquals(candidate, store.readCandidateOnce())
        assertEquals(emptyList<String>(), notifier.withdrawn)
        assertFalse("the capture is stopped", h.registrar.isRegistered)
    }

    /**
     * docs/05 §3a "Turning Smart Detection off": the process died between writing the flag and
     * the opt-out it asked for, so DRIVING and its capture (a Play services request that
     * outlives the process) were left behind. iOS twin, same name: `DrivingSessionLifecycleTests`
     * "A relaunch while Smart Detection is off ends the session a dead process left open".
     */
    @Test
    fun `a relaunch while Smart Detection is off ends the session a dead process left open`() = runTest {
        // Arrange
        val registrar = FakeLocationSessionRegistrar()
        val before = optOutHarness(registrar)
        before.registration.setDetectionEnabled(true)
        before.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, START))
        before.runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        before.runtime.handleTick(START + SUSTAIN)
        before.runtime.handleLocations(listOf(fix(START + 100_000, speedMps = 9f)))
        assertEquals(DetectionState.DRIVING, before.runtime.restore().state)
        store.setDesiredEnabled(false)
        val requestsBefore = registrar.requestedConfigs.size
        clock.epochMillis = START + 120_000
        val relaunched = optOutHarness(registrar)

        // Act — what ParkingpinApplication.onCreate does on every process start.
        relaunched.registration.reconcile()

        // Assert
        val state = relaunched.runtime.restore()
        assertEquals(DetectionState.IDLE, state.state)
        assertNull(state.session)
        assertEquals("no capture is opened first", requestsBefore, registrar.requestedConfigs.size)
        assertFalse("the left-over capture is stopped", registrar.isRegistered)
    }

    @Test
    fun `a relaunch while Smart Detection is off leaves a settled state untouched`() = runTest {
        // Arrange — a candidate pending with no window: the opt-out has nothing to end there.
        val h = optOutHarness()
        h.registration.setDetectionEnabled(true)
        driveAndPark()
        h.registration.setDetectionEnabled(false)
        val settled = h.runtime.restore()
        val relaunched = optOutHarness()

        // Act
        relaunched.registration.reconcile()

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, settled.state)
        assertEquals(settled, relaunched.runtime.restore())
    }

    // ── docs/05 §11: a capture lost inside DRIVING_CANDIDATE or DRIVING ────────────────
    //
    // "A lost capture decides nothing" extends to a drive: on Android the loss (a revoked
    // permission) never reaches the engine, so the drive keeps its state, session and
    // vehicle level, and the next exit and walk decide it. iOS twins, same names and events,
    // for `.authorizationLost` and `.captureFailed`: `ParkingTransitionEvidenceTests`.

    /** Coordinator-free runtime whose capture is the real controller over [registrar]. */
    private fun capturingRuntime(registrar: FakeLocationSessionRegistrar): ParkingDetectionRuntime {
        val controller = FusedLocationSessionController(store, registrar, clock)
        return ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
    }

    /**
     * docs/05 §11's sequence: `vehicle_enter` 0 s, the loss at [lossAtSeconds], tick +150 s,
     * `vehicle_exit` +600 s, `walking_enter` +630 s. Returns the state right after the loss.
     */
    private suspend fun driveLosingCaptureThenExit(lossAtSeconds: Long, registrar: FakeLocationSessionRegistrar): DetectionState {
        val withCapture = capturingRuntime(registrar)
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        if (lossAtSeconds > 90) withCapture.handleTick(START + SUSTAIN)
        assertTrue("the drive holds a capture", registrar.isRegistered)
        clock.epochMillis = START + lossAtSeconds * 1_000
        registrar.foregroundGranted = false
        withCapture.handleTick(START + lossAtSeconds * 1_000)
        val afterLoss = withCapture.restore().state
        clock.epochMillis = START + 150_000
        withCapture.handleTick(START + 150_000)
        clock.epochMillis = START + 600_000
        withCapture.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + 600_000))
        clock.epochMillis = START + 630_000
        withCapture.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + 630_000))
        return afterLoss
    }

    @Test
    fun `a drive that loses its capture still parks on the next exit`() = runTest {
        // Arrange
        val registrar = FakeLocationSessionRegistrar()
        clock.epochMillis = START

        // Act — the loss at +120 s, in DRIVING.
        val afterLoss = driveLosingCaptureThenExit(lossAtSeconds = 120, registrar = registrar)

        // Assert — the loss decided nothing; the exit and the walk made the parking.
        assertEquals(DetectionState.DRIVING, afterLoss)
        assertEquals(DetectionState.CANDIDATE_PENDING, runtime.restore().state)
        assertNotNull(store.readCandidateOnce())
        assertFalse(registrar.isRegistered)
    }

    @Test
    fun `a drive that loses its capture still parks on the next exit - lost in DRIVING_CANDIDATE`() = runTest {
        // Arrange
        val registrar = FakeLocationSessionRegistrar()
        clock.epochMillis = START

        // Act — the loss at +30 s; the tick at +150 s still promotes on §3a's 90 s bar.
        val afterLoss = driveLosingCaptureThenExit(lossAtSeconds = 30, registrar = registrar)

        // Assert
        assertEquals(DetectionState.DRIVING_CANDIDATE, afterLoss)
        assertEquals(DetectionState.CANDIDATE_PENDING, runtime.restore().state)
        assertNotNull(store.readCandidateOnce())
        assertFalse(registrar.isRegistered)
    }

    @Test
    fun `a drive that lost its capture reopens none after a process death`() = runTest {
        // Arrange — DRIVING at +90 s, a moving fix at +100 s, the loss at +120 s, then a new
        // process with the permission granted again.
        val registrar = FakeLocationSessionRegistrar()
        clock.epochMillis = START
        val beforeDeath = capturingRuntime(registrar)
        beforeDeath.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        beforeDeath.handleTick(START + SUSTAIN)
        beforeDeath.handleLocations(listOf(fix(START + 100_000, speedMps = 9f)))
        clock.epochMillis = START + 120_000
        registrar.foregroundGranted = false
        beforeDeath.handleTick(START + 120_000)
        registrar.foregroundGranted = true
        val requestsBefore = registrar.requestedConfigs.size
        val controller = FusedLocationSessionController(store, registrar, clock)
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        val ingestor = TransitionEventIngestor(store, clock, controller, restarted)

        // Act — stillness at +290 s, after movementIdle at +280 s.
        clock.epochMillis = START + 290_000
        ingestor.ingest(listOf(TransitionEventIngestor.RawTransition(MotionActivity.STILL, TransitionKind.ENTER, START + 290_000)))

        // Assert — the candidate, with no capture reopened and so no resume window.
        val state = restarted.restore()
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertNotNull(store.readCandidateOnce())
        assertNull(state.stopOnlyResumeWindow)
        assertEquals("no capture is reopened", requestsBefore, registrar.requestedConfigs.size)
    }

    // ── docs/05 §11: the permission comes back while a session is capture-lost ─────────
    //
    // "A lost capture stays lost for its session": nothing reopens a capture the engine's
    // current session lost, and that includes the motion edges `TransitionEventIngestor`
    // shapes the capture on before the engine takes them. These go through the real ingestor
    // for exactly that reason. iOS twins, same names and events: `ParkingTransitionEvidenceTests`.

    /** The ingestor, runtime and controller wired the way `AppContainer` wires them. */
    private class IngestingHarness(
        val registrar: FakeLocationSessionRegistrar,
        val runtime: ParkingDetectionRuntime,
        val ingestor: TransitionEventIngestor,
    )

    private fun ingestingHarness(registrar: FakeLocationSessionRegistrar): IngestingHarness {
        val controller = FusedLocationSessionController(store, registrar, clock)
        val wired = capturingRuntimeOver(controller)
        return IngestingHarness(registrar, wired, TransitionEventIngestor(store, clock, controller, wired))
    }

    private fun capturingRuntimeOver(controller: FusedLocationSessionController) = ParkingDetectionRuntime(
        store = store,
        candidates = { coordinator },
        followLocationCapture = { before, after -> controller.followEngine(before, after) },
        captureRunning = { controller.isCaptureRunning() },
    )

    private suspend fun IngestingHarness.transition(activity: MotionActivity, kind: TransitionKind, atMillis: Long) {
        clock.epochMillis = atMillis
        ingestor.ingest(listOf(TransitionEventIngestor.RawTransition(activity, kind, atMillis)))
    }

    /** A tick at [atMillis] with the permission revoked: the follow sees the loss. */
    private suspend fun IngestingHarness.revokeAt(atMillis: Long) {
        clock.epochMillis = atMillis
        registrar.foregroundGranted = false
        runtime.handleTick(atMillis)
        assertFalse("the loss stops the capture", registrar.isRegistered)
    }

    @Test
    fun `a drive whose permission returns before the exit decides as it would without a capture`() = runTest {
        // Arrange — vehicle_enter 0 s, tick +150 s (DRIVING), the permission revoked at +200 s
        // and granted again at +300 s.
        val h = ingestingHarness(FakeLocationSessionRegistrar())
        h.transition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START)
        clock.epochMillis = START + 150_000
        h.runtime.handleTick(START + 150_000)
        assertEquals(DetectionState.DRIVING, h.runtime.restore().state)
        h.revokeAt(START + 200_000)
        clock.epochMillis = START + 300_000
        h.registrar.foregroundGranted = true
        val requestsBefore = h.registrar.requestedConfigs.size

        // Act — vehicle_exit +600 s, then walking_enter +630 s.
        h.transition(MotionActivity.IN_VEHICLE, TransitionKind.EXIT, START + 600_000)
        val stateAfterExit = h.runtime.restore().state
        val kerbCaptureOpened = h.registrar.requestedConfigs.size > requestsBefore
        h.transition(MotionActivity.WALKING, TransitionKind.ENTER, START + 630_000)

        // Assert — no kerb capture, so no fix can resume the drive; the walk parks it.
        assertEquals(DetectionState.PARKING_TRANSITION, stateAfterExit)
        assertFalse("the exit reopens no capture the drive lost", kerbCaptureOpened)
        assertEquals(DetectionState.CANDIDATE_PENDING, h.runtime.restore().state)
        assertNotNull(store.readCandidateOnce())
        assertEquals(requestsBefore, h.registrar.requestedConfigs.size)
    }

    @Test
    fun `a drive whose permission returns reopens none on a repeated vehicle_enter`() = runTest {
        // Arrange — the same drive; the OS repeats IN_VEHICLE ENTER at +400 s, mid-drive.
        val h = ingestingHarness(FakeLocationSessionRegistrar())
        h.transition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START)
        clock.epochMillis = START + 150_000
        h.runtime.handleTick(START + 150_000)
        h.revokeAt(START + 200_000)
        h.registrar.foregroundGranted = true
        val requestsBefore = h.registrar.requestedConfigs.size

        // Act
        h.transition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START + 400_000)

        // Assert — the same journey, still without a capture.
        assertEquals(DetectionState.DRIVING, h.runtime.restore().state)
        assertEquals(requestsBefore, h.registrar.requestedConfigs.size)
        assertFalse(h.registrar.isRegistered)
    }

    @Test
    fun `a journey opened after a capture-lost session ends starts with its own capture`() = runTest {
        // Arrange — the capture-lost drive parks (exit +600 s, walk +630 s).
        val h = ingestingHarness(FakeLocationSessionRegistrar())
        h.transition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START)
        clock.epochMillis = START + 150_000
        h.runtime.handleTick(START + 150_000)
        h.revokeAt(START + 200_000)
        h.registrar.foregroundGranted = true
        h.transition(MotionActivity.IN_VEHICLE, TransitionKind.EXIT, START + 600_000)
        h.transition(MotionActivity.WALKING, TransitionKind.ENTER, START + 630_000)
        val candidate = assertNotNull(store.readCandidateOnce())

        // Act — a new journey (§3a "Leaving a pending candidate behind").
        h.transition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START + 900_000)

        // Assert — the loss belonged to the old session; the new one captures.
        val state = h.runtime.restore()
        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
        assertEquals(candidate.id, state.candidate?.id)
        assertTrue("a new session opens its own capture", h.registrar.isRegistered)
    }

    @Test
    fun `a transition that lost its capture reopens none after a process death`() = runTest {
        // Arrange — DRIVING at +90 s, a moving fix at +100 s, the loss at +120 s, then
        // movementIdle (+280 s) moves the drive into PARKING_TRANSITION with no capture. The
        // process dies and the permission comes back.
        val registrar = FakeLocationSessionRegistrar()
        val beforeDeath = ingestingHarness(registrar)
        beforeDeath.transition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START)
        clock.epochMillis = START + SUSTAIN
        beforeDeath.runtime.handleTick(START + SUSTAIN)
        clock.epochMillis = START + 100_000
        beforeDeath.runtime.handleLocations(listOf(fix(START + 100_000, speedMps = 9f)))
        beforeDeath.revokeAt(START + 120_000)
        clock.epochMillis = START + 285_000
        beforeDeath.runtime.handleTick(START + 285_000)
        assertEquals(DetectionState.PARKING_TRANSITION, beforeDeath.runtime.restore().state)
        registrar.foregroundGranted = true
        val requestsBefore = registrar.requestedConfigs.size
        val restarted = ingestingHarness(registrar)

        // Act — stillness at +300 s confirms a stop-only candidate; back in a vehicle at
        // +350 s, inside what would have been its resume window (+280 .. +580 s).
        restarted.transition(MotionActivity.STILL, TransitionKind.ENTER, START + 300_000)
        val candidate = assertNotNull(store.readCandidateOnce())
        val windowAfterCandidate = restarted.runtime.restore().stopOnlyResumeWindow
        val reopenedBeforeEnter = registrar.requestedConfigs.size > requestsBefore
        restarted.transition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START + 350_000)

        // Assert — no window, so "Leaving a pending candidate behind": the candidate kept.
        assertFalse("no capture is reopened for the transition", reopenedBeforeEnter)
        assertNull(windowAfterCandidate)
        val state = restarted.runtime.restore()
        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
        assertEquals(candidate.id, state.candidate?.id)
        assertEquals(candidate, store.readCandidateOnce())
        assertEquals(emptyList<String>(), notifier.withdrawn)
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

    // ── docs/05 §14: a process death inside DEPARTURE_CANDIDATE ─────────────────────────
    //
    // Each test below has an iOS twin in `DepartureTests` with the same name and the same
    // event sequence. On Android a process death is a new runtime over the same store: the
    // whole engine state is reloaded, which is the rule §14 makes iOS persist too.

    @Test
    fun `a departure restored after a process death can still propose the parking end`() = runTest {
        // Arrange — a hand-saved parking, then §11's two bars cleared (100 s in the car,
        // 600 m) with §7's guard still unmet, then the process dies.
        departFromHandSavedParking(runtime)
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, runtime.restore().state)
        val proposedEndAt = mutableListOf<Long>()
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
        )

        // Act — relaunched at +705 s; the next fix meets §7's guard (160 s, 1 300 m).
        val restored = restarted.restore()
        restarted.handleLocations(listOf(fixNorth(START + 760_000, northMeters = 1_300.0)))

        // Assert — confirmed, and the parking ends at the DEPARTURE_CANDIDATE entry.
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, restored.state)
        assertEquals(DetectionState.DRIVING, restarted.restore().state)
        assertEquals(listOf(START + 700_000), proposedEndAt)
    }

    @Test
    fun `a short departure restored before its exit still becomes the next parking`() = runTest {
        // Arrange — platform-tests/manual_save_then_short_departure.json with the process
        // dying between the 600 m fix (+700 s) and the vehicle_exit (+740 s).
        departFromHandSavedParking(runtime)
        val proposedEndAt = mutableListOf<Long>()
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
        )

        // Act
        restarted.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + 740_000))
        val afterExit = restarted.restore().state
        restarted.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + 760_000))

        // Assert — the exit confirms the departure and ends the drive (§11), and the walk
        // produces the fixture's medium candidate.
        assertEquals(DetectionState.PARKING_TRANSITION, afterExit)
        assertEquals(listOf(START + 700_000), proposedEndAt)
        assertEquals(DetectionState.CANDIDATE_PENDING, restarted.restore().state)
        assertEquals(ConfidenceBucket.MEDIUM, assertNotNull(store.readCandidateOnce()).confidenceBucket)
    }

    @Test
    fun `a departure restored with stale evidence returns to PARKED and ends nothing`() = runTest {
        // Arrange — the only vehicle evidence is the enter at +600 s, so §11's lapse is at
        // +900 s; the process is relaunched one second past it.
        departFromHandSavedParking(runtime)
        val proposedEndAt = mutableListOf<Long>()
        val follows = mutableListOf<Pair<LocationSessionMode?, LocationSessionMode?>>()
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
            followLocationCapture = { before, after -> follows += before to after },
        )
        val lapse = START + 600_000 + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS

        // Act
        restarted.handleTick(lapse + 1_000)

        // Assert — back to PARKED, stamped at the lapse, no capture wanted, nothing ended.
        val state = restarted.restore()
        assertEquals(DetectionState.PARKED, state.state)
        assertEquals(lapse, state.stateEnteredAtMillis)
        assertEquals(listOf<Pair<LocationSessionMode?, LocationSessionMode?>>(LocationSessionMode.DRIVING_CANDIDATE to null), follows)
        assertEquals(emptyList<Long>(), proposedEndAt)
    }

    @Test
    fun `a departure a link connect opened keeps its postponed lapse across a process death`() = runTest {
        // Arrange — §11b: the connect at +950 s opens the departure after the enter's own
        // window (600 + 300 s) has passed, so its lapse runs from the connect (+1 250 s).
        runtime.handleUserSavedParking(START)
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 600_000))
        runtime.handleCarLink(DetectionEvent.CarLinkConnected(START + 950_000))
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, runtime.restore().state)
        val restarted = ParkingDetectionRuntime(store, { coordinator })

        // Act
        restarted.handleTick(START + 1_200_000)
        val held = restarted.restore().state
        restarted.handleTick(START + 1_300_000)

        // Assert
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, held)
        val lapsed = restarted.restore()
        assertEquals(DetectionState.PARKED, lapsed.state)
        assertEquals(START + 950_000 + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS, lapsed.stateEnteredAtMillis)
    }

    @Test
    fun `a link connect inside a departure still postpones its lapse after a process death`() = runTest {
        // Arrange — the connect at +710 s (guard unmet: 110 s, 600 m) moves the lapse from
        // +900 s to +1 010 s, and the process dies right after it.
        departFromHandSavedParking(runtime)
        runtime.handleCarLink(DetectionEvent.CarLinkConnected(START + 710_000))
        val proposedEndAt = mutableListOf<Long>()
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
        )
        val restored = restarted.restore().state

        // Act — past the enter's lapse, inside the connect's.
        restarted.handleLocations(listOf(fixNorth(START + 1_000_000, northMeters = 1_300.0)))

        // Assert
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, restored)
        assertEquals(DetectionState.DRIVING, restarted.restore().state)
        assertEquals(listOf(START + 700_000), proposedEndAt)
    }

    @Test
    fun `a get-in restored before the departure bars can still open the departure`() = runTest {
        // Arrange — died after the +610 s fix, before the +700 s fix clears 500 m.
        runtime.handleUserSavedParking(START)
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 600_000))
        runtime.handleLocations(listOf(fixNorth(START + 610_000, northMeters = 0.0)))
        val before = runtime.restore()
        val restarted = ParkingDetectionRuntime(store, { coordinator })

        // Act
        restarted.handleLocations(listOf(fixNorth(START + 700_000, northMeters = 600.0)))

        // Assert
        assertEquals(DetectionState.PARKED, before.state)
        assertNotNull("the get-in is part of the stored state", before.session)
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, restarted.restore().state)
    }

    @Test
    fun `a reboot inside a departure reopens its bounded capture`() = runTest {
        // Arrange — FG11, docs/05 §14 "the bounded capture is reopened": the reboot drops the
        // Play services request and the recovery receiver clears its record.
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        fun capturingRuntime(proposedEndAt: MutableList<Long>) = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        clock.epochMillis = START + 600_000
        departFromHandSavedParking(capturingRuntime(mutableListOf()))
        assertTrue("the departure opened a capture", registrar.isRegistered)
        controller.reconcileAfterSystemReset()
        assertFalse(registrar.isRegistered)
        val proposedEndAt = mutableListOf<Long>()
        val afterBoot = capturingRuntime(proposedEndAt)
        clock.epochMillis = START + 705_000

        // Act
        afterBoot.resumeAfterSystemReset(START + 705_000)
        val reopened = registrar.isRegistered
        afterBoot.handleLocations(listOf(fixNorth(START + 760_000, northMeters = 1_300.0)))

        // Assert — reopened in the departure's mode, bounded by the planner's deadlines, and
        // the fixes it delivers can still propose the parking end.
        assertTrue("the departure's capture is reopened", reopened)
        assertEquals(DetectionState.DRIVING, afterBoot.restore().state)
        assertEquals(listOf(START + 700_000), proposedEndAt)
    }

    /**
     * iOS twin: `DepartureTests` "A get-in restored with stale evidence reopens no capture and
     * keeps the parking". A system reset is the one Android relaunch that reopens a capture on
     * the stored state's word (docs/05 §14), so it is where a get-in whose vehicle evidence
     * went silent long ago is dropped instead: the parking stays, no GPS is reopened.
     */
    @Test
    fun `a get-in restored with stale evidence reopens no capture and keeps the parking`() = runTest {
        // Arrange — the get-in's only vehicle evidence is the +600 s enter.
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        val proposedEndAt = mutableListOf<Long>()
        fun capturingRuntime() = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        clock.epochMillis = START + 600_000
        val beforeBoot = capturingRuntime()
        beforeBoot.handleUserSavedParking(START)
        beforeBoot.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 600_000))
        beforeBoot.handleLocations(listOf(fixNorth(START + 610_000, northMeters = 0.0)))
        assertNotNull("the get-in is stored", beforeBoot.restore().session)
        controller.reconcileAfterSystemReset()
        val requestsBefore = registrar.requestedConfigs.size
        val relaunchAt = START + 600_000 + ParkingDetectionEngine.VEHICLE_EVIDENCE_TIMEOUT_MILLIS + 1_000
        clock.epochMillis = relaunchAt

        // Act
        capturingRuntime().resumeAfterSystemReset(relaunchAt)

        // Assert
        val state = capturingRuntime().restore()
        assertEquals(DetectionState.PARKED, state.state)
        assertNull("the stale get-in is dropped", state.session)
        assertEquals(requestsBefore, registrar.requestedConfigs.size)
        assertFalse(registrar.isRegistered)
        assertEquals(emptyList<Long>(), proposedEndAt)
    }

    @Test
    fun `a get-in restored inside its evidence window still reopens its capture`() = runTest {
        // Arrange — the control for the test above: the same get-in, reset 60 s later.
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        fun capturingRuntime() = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        clock.epochMillis = START + 600_000
        val beforeBoot = capturingRuntime()
        beforeBoot.handleUserSavedParking(START)
        beforeBoot.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 600_000))
        beforeBoot.handleLocations(listOf(fixNorth(START + 610_000, northMeters = 0.0)))
        controller.reconcileAfterSystemReset()
        clock.epochMillis = START + 670_000

        // Act
        capturingRuntime().resumeAfterSystemReset(START + 670_000)

        // Assert
        val state = capturingRuntime().restore()
        assertEquals(DetectionState.PARKED, state.state)
        assertNotNull("a fresh get-in is kept", state.session)
        assertTrue("its capture is reopened", registrar.isRegistered)
    }

    // ── docs/05 §14: a drive restored after its session timeout already ended it ─────────
    //
    // iOS twins, same names: `DetectionCheckpointTests`. A system reset is the Android
    // relaunch that loses the capture, so it is where a drive the session timeout
    // (`DrivingSessionTimeoutPolicy.expiryReason`: 2 h from the first vehicle evidence, or
    // 600 s of vehicle silence) already ends goes to IDLE — no capture reopened, no candidate.

    /** A reset [relaunchAt] after a drive, through runtimes that follow a real controller. */
    private suspend fun resetAfterDrive(
        drive: suspend (ParkingDetectionRuntime) -> Unit,
        relaunchAt: Long,
    ): StaleDriveReset {
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        fun capturingRuntime() = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        drive(capturingRuntime())
        val before = capturingRuntime().restore().state
        controller.reconcileAfterSystemReset()
        val requestsBefore = registrar.requestedConfigs.size
        clock.epochMillis = relaunchAt
        val effects = capturingRuntime().resumeAfterSystemReset(relaunchAt)
        return StaleDriveReset(
            before = before,
            after = capturingRuntime().restore(),
            effects = effects,
            reopened = registrar.isRegistered || registrar.requestedConfigs.size != requestsBefore,
        )
    }

    private data class StaleDriveReset(
        val before: DetectionState,
        val after: DetectionEngineState,
        val effects: List<DetectionEffect>,
        val reopened: Boolean,
    )

    /** `vehicle_enter` at [START] and a fix at +10 s: `DRIVING_CANDIDATE`, its capture open. */
    private suspend fun enterVehicle(target: ParkingDetectionRuntime) {
        clock.epochMillis = START
        target.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        clock.epochMillis = START + 10_000
        target.handleLocations(listOf(fix(START + 10_000, speedMps = null)))
    }

    /** Two `vehicle_enter` a sustain apart and no moving fix: `DRIVING`, its capture open. */
    private suspend fun confirmDriveWithoutMovement(target: ParkingDetectionRuntime) {
        clock.epochMillis = START
        target.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        clock.epochMillis = START + SUSTAIN
        target.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + SUSTAIN))
    }

    @Test
    fun `a stale driving candidate restored after a relaunch reopens no capture and ends in IDLE`() = runTest {
        // Arrange / Act — relaunched 30 min later, far past 600 s of vehicle silence.
        val reset = resetAfterDrive(::enterVehicle, relaunchAt = START + STALE_RELAUNCH_GAP)

        // Assert
        assertEquals(DetectionState.DRIVING_CANDIDATE, reset.before)
        assertEquals(DetectionState.IDLE, reset.after.state)
        assertNull("the session ends", reset.after.session)
        assertNull("no candidate", reset.after.candidate)
        assertTrue(reset.effects.none { it is DetectionEffect.CreateCandidate })
        assertFalse("no capture is reopened", reset.reopened)
        assertNull(store.readCandidateOnce())
    }

    @Test
    fun `a stale drive restored after a relaunch reopens no capture and ends in IDLE`() = runTest {
        // Arrange / Act — the scenario the review named: a confirmed drive with no moving
        // sample, which `movementIdleWindow` correctly declines to end, relaunched 30 min on.
        val reset = resetAfterDrive(::confirmDriveWithoutMovement, relaunchAt = START + STALE_RELAUNCH_GAP)

        // Assert
        assertEquals(DetectionState.DRIVING, reset.before)
        assertEquals(DetectionState.IDLE, reset.after.state)
        assertNull("the session ends", reset.after.session)
        assertNull("no candidate", reset.after.candidate)
        assertTrue(reset.effects.none { it is DetectionEffect.CreateCandidate })
        assertFalse("no capture is reopened", reset.reopened)
        assertNull(store.readCandidateOnce())
    }

    @Test
    fun `a drive restored inside its evidence window still reopens its capture`() = runTest {
        // Arrange / Act — the control for the test above: the same drive, reset 60 s after
        // its last vehicle evidence.
        val reset = resetAfterDrive(::confirmDriveWithoutMovement, relaunchAt = START + SUSTAIN + 60_000)

        // Assert
        assertEquals(DetectionState.DRIVING, reset.after.state)
        assertNotNull("the drive is kept", reset.after.session)
        assertTrue("its capture is reopened", reset.reopened)
    }

    @Test
    fun `a reboot mid-drive whose fixes kept arriving keeps the drive`() = runTest {
        // Arrange — Android's IN_VEHICLE is an edge: a 12-minute drive has one `vehicle_enter`
        // and a fix every 30 s. The reboot lands 60 s after the last fix, 13 min after the
        // only vehicle edge — past 600 s of vehicle silence, but the drive was in progress.
        // The fixes go straight to the runtime here, not through the controller's receiver, so
        // the capture's own renewal is not exercised; what is pinned is the engine keeping it.
        val lastFixAt = START + 12 * 60_000L
        suspend fun driveTwentyMinutes(target: ParkingDetectionRuntime) {
            clock.epochMillis = START
            target.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
            for (at in START + 30_000..lastFixAt step 30_000) {
                clock.epochMillis = at
                target.handleLocations(listOf(fixNorth(at, northMeters = (at - START) / 1_000.0 * 12)))
            }
        }

        // Act
        val reset = resetAfterDrive(::driveTwentyMinutes, relaunchAt = lastFixAt + 60_000)

        // Assert
        assertEquals(DetectionState.DRIVING, reset.before)
        assertEquals(DetectionState.DRIVING, reset.after.state)
        assertNotNull("the drive is kept", reset.after.session)
        assertTrue(reset.effects.none { it is DetectionEffect.CreateCandidate })
    }

    // ── docs/05 §11: a capture lost inside DEPARTURE_CANDIDATE ─────────────────────────
    //
    // On Android a lost capture (a revoked permission, a failed request) is not an engine
    // event: the state, the session and the vehicle evidence stay, and the next edge, fix or
    // tick decides — §7's guard by elapsed time, or §11's lapse. Each test below has an iOS
    // twin in `DepartureTests` with the same name and the same events, where the adapter's
    // `.authorizationLost` / `.captureFailed` end is read the same way.

    /** A departure with its capture, the permission revoked at +710 s (guard still unmet). */
    private suspend fun departAndLoseCaptureAt710(proposedEndAt: MutableList<Long>): Pair<ParkingDetectionRuntime, FakeLocationSessionRegistrar> {
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        val withCapture = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        clock.epochMillis = START + 600_000
        departFromHandSavedParking(withCapture)
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, withCapture.restore().state)
        clock.epochMillis = START + 710_000
        registrar.foregroundGranted = false
        withCapture.handleTick(START + 710_000)
        assertEquals("a lost capture decides nothing", DetectionState.DEPARTURE_CANDIDATE, withCapture.restore().state)
        return withCapture to registrar
    }

    @Test
    fun `capture lost at +710 then exit at +740 still proposes the parking end at +700 and raises the medium candidate`() = runTest {
        // Arrange
        val proposedEndAt = mutableListOf<Long>()
        val (withCapture, _) = departAndLoseCaptureAt710(proposedEndAt)

        // Act — §7's duration clause (140 s) is met at the exit, the enter only 140 s old.
        clock.epochMillis = START + 740_000
        withCapture.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + 740_000))
        val afterExit = withCapture.restore().state
        clock.epochMillis = START + 760_000
        withCapture.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + 760_000))

        // Assert — the same outcome as platform-tests/manual_save_then_short_departure.json.
        assertEquals(DetectionState.PARKING_TRANSITION, afterExit)
        assertEquals(listOf(START + 700_000), proposedEndAt)
        assertEquals(DetectionState.CANDIDATE_PENDING, withCapture.restore().state)
        assertEquals(ConfidenceBucket.MEDIUM, assertNotNull(store.readCandidateOnce()).confidenceBucket)
    }

    @Test
    fun `capture lost at +710 with no further edge lapses to PARKED stamped +900`() = runTest {
        // Arrange
        val proposedEndAt = mutableListOf<Long>()
        val (withCapture, registrar) = departAndLoseCaptureAt710(proposedEndAt)

        // Act — the next thing delivered is a tick past the enter's lapse (+900 s).
        clock.epochMillis = START + 901_000
        withCapture.handleTick(START + 901_000)

        // Assert — §11's lapse: back to PARKED at the lapse, nothing ended.
        val state = withCapture.restore()
        assertEquals(DetectionState.PARKED, state.state)
        assertEquals(START + 600_000 + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS, state.stateEnteredAtMillis)
        assertNull(state.session)
        assertEquals(emptyList<Long>(), proposedEndAt)
        assertFalse(registrar.isRegistered)
    }

    /**
     * docs/05 §11 "A lost capture decides nothing": the departure still confirms on elapsed
     * time, but the drive it confirms has no capture, so the stop-only candidate that drive
     * ends in holds none and gets no resume window. iOS twin, same name and events:
     * `DepartureTests` "A departure that lost its capture opens no resume window after it
     * confirms".
     */
    @Test
    fun `a departure that lost its capture opens no resume window after it confirms`() = runTest {
        // Arrange — capture lost at +710; the tick at +740 meets §7's guard by elapsed time;
        // the +700 moving fix puts movementIdle at +880, and stillness at +890 confirms it.
        val proposedEndAt = mutableListOf<Long>()
        val (withCapture, registrar) = departAndLoseCaptureAt710(proposedEndAt)
        clock.epochMillis = START + 740_000
        withCapture.handleTick(START + 740_000)
        assertEquals(DetectionState.DRIVING, withCapture.restore().state)
        clock.epochMillis = START + 890_000
        withCapture.handleMotion(motion(MotionEventKind.BECAME_STATIONARY, START + 890_000))
        val candidate = assertNotNull(store.readCandidateOnce())

        // Act — back in a vehicle inside what would have been the window (+880 .. +1180).
        clock.epochMillis = START + 950_000
        withCapture.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 950_000))

        // Assert — the parking ended at +700, the candidate is kept, and a new journey starts.
        val state = withCapture.restore()
        assertEquals(listOf(START + 700_000), proposedEndAt)
        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
        assertEquals(candidate.id, state.candidate?.id)
        assertEquals(emptyList<String>(), notifier.withdrawn)
        assertFalse(registrar.isRegistered)
    }

    /**
     * docs/05 §11 "A lost capture decides nothing" / §14: a process death does not give a
     * departure its lost capture back — on Android a new process reloads the same state, and
     * the follow reopens only what a reboot dropped. iOS twin, same name: `DepartureTests` "A
     * departure that lost its capture reopens none after a process death".
     */
    @Test
    fun `a departure that lost its capture reopens none after a process death`() = runTest {
        // Arrange — died right after the loss at +710; the permission is granted again.
        val proposedEndAt = mutableListOf<Long>()
        val (_, registrar) = departAndLoseCaptureAt710(proposedEndAt)
        registrar.foregroundGranted = true
        val requestsBefore = registrar.requestedConfigs.size
        val controller = FusedLocationSessionController(store, registrar, clock)
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )

        // Act
        clock.epochMillis = START + 720_000
        restarted.handleTick(START + 720_000)
        val reopened = registrar.requestedConfigs.size > requestsBefore
        clock.epochMillis = START + 740_000
        restarted.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + 740_000))

        // Assert — the departure is still judged exactly as without the death.
        assertFalse("no capture is reopened", reopened)
        assertEquals(listOf(START + 700_000), proposedEndAt)
        assertEquals(DetectionState.PARKING_TRANSITION, restarted.restore().state)
    }

    /**
     * docs/05 §11 "A lost capture stays lost for its session" / §14: the departure confirms on
     * elapsed time with no capture, the process dies, and the permission comes back. The
     * vehicle_enter that follows opens a new journey's capture before the engine takes it
     * (TransitionEventIngestor's order), and that capture must not revive a stop-only window
     * the transition never held one for. iOS twin, same name and events: `DepartureTests` "A
     * departure that lost its capture reopens none after it confirms and the process dies".
     */
    @Test
    fun `a departure that lost its capture reopens none after it confirms and the process dies`() = runTest {
        // Arrange — capture lost at +710, the tick at +740 confirms the departure (DRIVING),
        // then a new process over the same store, with the permission granted again.
        val proposedEndAt = mutableListOf<Long>()
        val (withCapture, registrar) = departAndLoseCaptureAt710(proposedEndAt)
        clock.epochMillis = START + 740_000
        withCapture.handleTick(START + 740_000)
        assertEquals(DetectionState.DRIVING, withCapture.restore().state)
        registrar.foregroundGranted = true
        val controller = FusedLocationSessionController(store, registrar, clock)
        val restarted = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            proposeParkingEnd = { at -> proposedEndAt += at },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        val ingestor = TransitionEventIngestor(store, clock, controller, restarted)

        // Act — stillness at +890 confirms the stop-only candidate (movementIdle at +880), then
        // back in a vehicle inside what would have been the window (+880 .. +1180).
        clock.epochMillis = START + 890_000
        ingestor.ingest(listOf(TransitionEventIngestor.RawTransition(MotionActivity.STILL, TransitionKind.ENTER, START + 890_000)))
        val candidate = assertNotNull(store.readCandidateOnce())
        clock.epochMillis = START + 950_000
        ingestor.ingest(listOf(TransitionEventIngestor.RawTransition(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, START + 950_000)))

        // Assert — "Leaving a pending candidate behind": a new journey, the candidate kept.
        val state = restarted.restore()
        assertEquals(listOf(START + 700_000), proposedEndAt)
        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
        assertEquals(candidate.id, state.candidate?.id)
        assertEquals(candidate, store.readCandidateOnce())
        assertEquals(emptyList<String>(), notifier.withdrawn)
    }

    /** The same for `PARKED`'s get-in. iOS twin, same name, in `DepartureTests`. */
    @Test
    fun `a get-in that lost its capture reopens none after a process death`() = runTest {
        // Arrange — PARKED with a get-in (+600 enter, +610 fix), the permission revoked at +650,
        // then the process died and the permission came back.
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        fun capturingRuntime() = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        clock.epochMillis = START + 600_000
        val beforeDeath = capturingRuntime()
        beforeDeath.handleUserSavedParking(START)
        beforeDeath.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 600_000))
        beforeDeath.handleLocations(listOf(fixNorth(START + 610_000, northMeters = 0.0)))
        clock.epochMillis = START + 650_000
        registrar.foregroundGranted = false
        beforeDeath.handleTick(START + 650_000)
        assertFalse(registrar.isRegistered)
        registrar.foregroundGranted = true
        val requestsBefore = registrar.requestedConfigs.size
        val restarted = capturingRuntime()

        // Act
        clock.epochMillis = START + 660_000
        restarted.handleTick(START + 660_000)
        val reopened = registrar.requestedConfigs.size > requestsBefore
        clock.epochMillis = START + 700_000
        restarted.handleLocations(listOf(fixNorth(START + 700_000, northMeters = 600.0)))

        // Assert — the get-in is kept, without a capture, and can still open the departure.
        assertFalse("no capture is reopened", reopened)
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, restarted.restore().state)
    }

    @Test
    fun `a reboot after a departure lapsed opens no capture`() = runTest {
        // Arrange
        val registrar = FakeLocationSessionRegistrar()
        val controller = FusedLocationSessionController(store, registrar, clock)
        fun capturingRuntime() = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            followLocationCapture = { before, after -> controller.followEngine(before, after) },
            captureRunning = { controller.isCaptureRunning() },
        )
        clock.epochMillis = START + 600_000
        departFromHandSavedParking(capturingRuntime())
        controller.reconcileAfterSystemReset()
        val requestsBefore = registrar.requestedConfigs.size
        clock.epochMillis = START + 901_000

        // Act
        capturingRuntime().resumeAfterSystemReset(START + 901_000)

        // Assert
        assertEquals(DetectionState.PARKED, capturingRuntime().restore().state)
        assertEquals(requestsBefore, registrar.requestedConfigs.size)
        assertFalse(registrar.isRegistered)
    }

    /**
     * The first half of platform-tests/manual_save_then_short_departure.json: a hand save at
     * [START], `vehicle_enter` at +600 s, fixes at +610 s (0 m) and +700 s (600 m) — §11's two
     * bars cleared, so `DEPARTURE_CANDIDATE` entered at +700 s with §7's guard unmet.
     */
    private suspend fun departFromHandSavedParking(target: ParkingDetectionRuntime) {
        target.handleUserSavedParking(START)
        target.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START + 600_000))
        target.handleLocations(listOf(fixNorth(START + 610_000, northMeters = 0.0)))
        target.handleLocations(listOf(fixNorth(START + 700_000, northMeters = 600.0)))
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
        const val STALE_RELAUNCH_GAP = 30 * 60 * 1000L
    }
}

package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.DrivingConfirmationGuard
import com.sjstudioz.parkingpin.domain.location.LocationCaptureModePolicy
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import com.sjstudioz.parkingpin.domain.trace.LocationQualityBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every row of docs/05_PARKING_DETECTION_ENGINE.md §3a, plus the three car-link rows and
 * the rules §3a states in prose rather than in the table.
 *
 * Written against the transition table rather than against the implementation: each test
 * names the row it holds, so a row that changes in the contract fails here by name.
 */
class ParkingDetectionEngineTest {

    private var nextId = 0
    private val engine = ParkingDetectionEngine { "candidate-${nextId++}" }

    // ── §3a main table ──────────────────────────────────────────────────────────────

    @Test
    fun `IDLE to DRIVING_CANDIDATE on vehicle enter`() {
        val state = idle().handle(DetectionEvent.VehicleEnter(T0))

        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
    }

    @Test
    fun `DRIVING_CANDIDATE to DRIVING once vehicle activity is sustained`() {
        val state = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.StationaryExit(T0 + SUSTAIN))

        assertEquals(DetectionState.DRIVING, state.state)
    }

    @Test
    fun `DRIVING_CANDIDATE stays put until the sustain bar is cleared`() {
        val state = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.StationaryExit(T0 + SUSTAIN - 1))

        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
    }

    @Test
    fun `DRIVING_CANDIDATE to IDLE on vehicle exit`() {
        val state = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.VehicleExit(T0 + 10_000))

        assertEquals(DetectionState.IDLE, state.state)
        assertNull("the travel session ends with the state", state.session)
    }

    @Test
    fun `the candidate window never discards a session whose activity did sustain`() {
        // §3a's two `DRIVING_CANDIDATE` rows overlap, and the promotion row wins. Silence is
        // what sustained vehicle activity *looks like* — the Activity Transition API reports
        // changes, so no news is the vehicle still going — and `subway_commute_underground`
        // is 303 s of exactly that silence followed by a drive the contract expects to be
        // detected.
        val candidate = idle().handle(DetectionEvent.VehicleEnter(T0))

        assertEquals(
            DetectionState.DRIVING,
            candidate.handle(DetectionEvent.TimerTick(T0 + SUSTAIN)).state,
        )

        // The same moment a full window later promotes and stays there. It has had no
        // movement evidence at all, which is not the same as movement that stopped — this
        // is the underground drive, and `movementIdleWindow` must not touch it.
        val atWindow = candidate
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.DRIVING_CANDIDATE_WINDOW_MILLIS))

        assertEquals(DetectionState.DRIVING, atWindow.state)
        assertNotNull("the travel session survives; only an unpromoted one is discarded", atWindow.session)
    }

    @Test
    fun `DRIVING_CANDIDATE to IDLE when the window elapses on a session whose vehicle ended`() {
        // What a process death between a `vehicle_exit` and its transition leaves behind:
        // restored in DRIVING_CANDIDATE with the vehicle activity already over, so nothing
        // can promote it and the window is what cleans it up.
        val stranded = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .let { it.copy(session = checkNotNull(it.session).copy(vehicleEndedAtMillis = T0 + 10_000)) }

        val state = stranded
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.DRIVING_CANDIDATE_WINDOW_MILLIS))

        assertEquals(DetectionState.IDLE, state.state)
        assertNull(state.session)
    }

    @Test
    fun `DRIVING to PARKING_TRANSITION on vehicle exit`() {
        val state = driving().handle(DetectionEvent.VehicleExit(T0 + 1_000))

        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
    }

    @Test
    fun `DRIVING to PARKING_TRANSITION when movement evidence goes quiet`() {
        // A fix that moved first: the row is "movement stopped", and a drive that never
        // moved has nothing that could have stopped.
        val state = driving()
            .handle(DetectionEvent.Location(fix(T0, accuracyM = 10f, speedMps = 12f)))
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS))

        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
    }

    @Test
    fun `a drive with no fix at all is never idled by the movement window`() {
        // The underground car park, which is the whole reason the window reads `null` as
        // "no movement to have stopped" rather than as "stopped long ago". Seeding it with
        // the session start instead flips `subway_commute_underground` to `IDLE` 180 s in,
        // measured 2026-09-20.
        val state = driving()
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS * 4))

        assertEquals(DetectionState.DRIVING, state.state)
    }

    @Test
    fun `the session ceiling ends a drive that nothing else ever ended`() {
        // Two hours of `DRIVING` with no fix and no exit — the underground-then-silent case
        // the movement window deliberately will not touch. Straight to IDLE and no
        // candidate: nothing here knows where the car was left.
        val state = driving()
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.SESSION_MAXIMUM_DURATION_MILLIS))

        assertEquals(DetectionState.IDLE, state.state)
        assertNull("the travel session ends with it, which is what stops the capture", state.session)
        assertNull(state.candidate)
    }

    @Test
    fun `a connected car link does not hold the session past the ceiling`() {
        // The gating table's fourth row. The link suppresses `movementIdleWindow` because
        // that row infers a parking from silence; the ceiling infers nothing.
        val state = driving()
            .handle(DetectionEvent.CarLinkConnected(T0))
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.SESSION_MAXIMUM_DURATION_MILLIS))

        assertEquals(DetectionState.IDLE, state.state)
    }

    @Test
    fun `a walk that arrives after the transition window confirms nothing`() {
        // The windows are judged **before** the edge, which is what iOS has always done and
        // Android did not: the walk used to find `PARKING_TRANSITION` still standing and open
        // a candidate for a stop that had already been abandoned 30 s earlier.
        val transition = driving().handle(DetectionEvent.VehicleExit(T0 + 1_000))
        assertEquals(DetectionState.PARKING_TRANSITION, transition.state)

        val late = transition.handle(
            DetectionEvent.WalkingEnter(
                T0 + 1_000 + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS + 30_000,
            ),
        )

        assertEquals(DetectionState.IDLE, late.state)
        assertNull("the window closed before the walk arrived", late.candidate)
    }

    @Test
    fun `PARKING_TRANSITION to CANDIDATE_PENDING on walking`() {
        val state = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 30_000))

        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertNotNull(state.candidate)
    }

    @Test
    fun `PARKING_TRANSITION to CANDIDATE_PENDING on stationary`() {
        val state = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.StationaryEnter(T0 + 30_000))

        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
    }

    @Test
    fun `PARKING_TRANSITION to CANDIDATE_PENDING on a location stop`() {
        val state = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.Location(fix(T0 + 30_000, accuracyM = 10f, speedMps = 0.1f)))

        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
    }

    @Test
    fun `PARKING_TRANSITION back to DRIVING when movement evidence returns`() {
        // The red light. Entering PARKING_TRANSITION is silent, so returning costs nothing.
        val state = driving()
            .handle(DetectionEvent.Location(fix(T0, accuracyM = 10f, speedMps = 12f)))
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS))
            .also { assertEquals(DetectionState.PARKING_TRANSITION, it.state) }
            .handle(DetectionEvent.Location(fix(T0 + 200_000, accuracyM = 10f, speedMps = 12f)))

        assertEquals(DetectionState.DRIVING, state.state)
        assertEquals("a red light must not create a candidate", 0, state.candidatesCreated)
    }

    // Twins of iOS `ParkingTransitionEvidenceTests` (docs/05 §3a "The PARKING_TRANSITION
    // rows, exactly"): the same events and the same outcome on both engines.

    @Test
    fun `a fix with no speed is not a location stop`() {
        // Arrange — iOS `speedlessFixIsNotALocationStop`.
        val transition = idleTransition()
        assertEquals(DetectionState.PARKING_TRANSITION, transition.state)

        // Act — underground there is no Doppler speed, and silence is not stillness (§7).
        val state = transition.handle(DetectionEvent.Location(fix(at(300), accuracyM = 8f, speedMps = null)))

        // Assert
        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
        assertEquals(0, state.candidatesCreated)
    }

    @Test
    fun `a speedless fix that clears section 7's movement bar also returns to DRIVING`() {
        // Arrange — iOS `derivedMovementResumesDriving`: a slow fix at 200 anchors the
        // distance fallback without moving it.
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 9f)))
            .handle(DetectionEvent.Location(fix(at(200), accuracyM = 8f, speedMps = 1f)))
            .handle(DetectionEvent.TimerTick(at(280)))
        assertEquals(DetectionState.PARKING_TRANSITION, transition.state)

        // Act — 500 m in 90 s at 10 m accuracy: travel, by §7's fallback.
        val state = transition.handle(DetectionEvent.Location(fix(at(290), accuracyM = 10f, speedMps = null, north = 500.0)))

        // Assert
        assertEquals(DetectionState.DRIVING, state.state)
    }

    @Test
    fun `a red-light resume does not orphan the drive's reliable fix`() {
        // Arrange — iOS `resumeKeepsTheInheritanceBound`: §5's bound runs from the trip's
        // start, not from the last red light.
        val resumed = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(150), accuracyM = 10f, speedMps = 9f)))
            .handle(DetectionEvent.TimerTick(at(330)))
            .also { assertEquals(DetectionState.PARKING_TRANSITION, it.state) }
            .handle(DetectionEvent.VehicleEnter(at(340)))

        // Act
        val step = engine.handle(resumed.handle(DetectionEvent.VehicleExit(at(400))), DetectionEvent.WalkingEnter(at(410)))

        // Assert
        val created = createCandidate(step.effects)
        assertEquals(at(150), created.lastReliableLocation?.capturedAtMillis)
        assertTrue(EvidenceReasonCode.RELIABLE_LOCATION_CAPTURED in created.reasons)
    }

    @Test
    fun `a link reconnecting inside the transition resumes the drive`() {
        // Arrange — iOS `connectInTransitionResumes`.
        val transition = idleTransition()

        // Act
        val state = transition.handle(DetectionEvent.CarLinkConnected(at(300)))

        // Assert — the same drive: its start survives the resume.
        assertEquals(DetectionState.DRIVING, state.state)
        assertEquals(T0, checkNotNull(state.session).evidence.vehicleFirstSeenAtMillis)
    }

    @Test
    fun `PARKING_TRANSITION to IDLE when the transition window elapses`() {
        val state = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.TimerTick(T0 + 1_000 + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS))

        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(0, state.candidatesCreated)
    }

    @Test
    fun `CANDIDATE_PENDING to PARKED when the user confirms`() {
        val state = pendingCandidate().handle(DetectionEvent.UserConfirmedParking(T0 + 40_000))

        assertEquals(DetectionState.PARKED, state.state)
    }

    @Test
    fun `CANDIDATE_PENDING to IDLE when the user rejects`() {
        val state = pendingCandidate().handle(DetectionEvent.UserRejectedParking(T0 + 40_000))

        assertEquals(DetectionState.IDLE, state.state)
        assertNull(state.candidate)
    }

    @Test
    fun `CANDIDATE_PENDING to IDLE at the 45-minute expiry`() {
        val pending = pendingCandidate()
        val expiresAt = checkNotNull(pending.candidate).expiresAtMillis

        val step = engine.handle(pending, DetectionEvent.TimerTick(expiresAt))

        assertEquals(DetectionState.IDLE, step.state.state)
        assertNull(step.state.candidate)
        assertTrue(
            "the notification must come down",
            step.effects.any { it is DetectionEffect.RetireCandidate },
        )
    }

    @Test
    fun `PARKED to DEPARTURE_CANDIDATE needs both the vehicle bar and 500m`() {
        val parked = pendingCandidate().handle(DetectionEvent.UserConfirmedParking(T0 + 40_000))
        val departing = parked
            .handle(DetectionEvent.VehicleEnter(T1))
            // 90 s of vehicle activity alone is a phone waking up in a parked car.
            .handle(DetectionEvent.Location(fix(T1 + SUSTAIN, accuracyM = 5f, speedMps = 15f)))

        assertEquals(DetectionState.PARKED, departing.state)

        val moved = departing
            .handle(DetectionEvent.Location(fix(T1 + SUSTAIN + 60_000, accuracyM = 5f, speedMps = 15f, north = 600.0)))

        assertEquals(DetectionState.DEPARTURE_CANDIDATE, moved.state)
    }

    @Test
    fun `PARKED to DEPARTURE_CANDIDATE on a car link connect, ending nothing yet`() {
        // §11b. The mirror of §3a's disconnect row: the phone rejoining the car is the
        // strongest departure signal there is, and it used to do nothing at all here.
        val parked = pendingCandidate().handle(DetectionEvent.UserConfirmedParking(T0 + 40_000))

        val step = engine.handle(parked, DetectionEvent.CarLinkConnected(T1))

        assertEquals(DetectionState.DEPARTURE_CANDIDATE, step.state.state)
        // Opening is not ending: §11a says only §7's guard closes a record.
        assertTrue(
            "a connect is not yet a drive",
            step.effects.none { it is DetectionEffect.EndActiveParking },
        )
    }

    @Test
    fun `a car link departure ends the parking at the moment of the connect`() {
        val parked = pendingCandidate().handle(DetectionEvent.UserConfirmedParking(T0 + 40_000))
        val gotIn = parked.handle(DetectionEvent.CarLinkConnected(T1))

        // §7's guard in full: 120 s and 800 m. Six fixes, because distance accumulates
        // between consecutive fixes — the first one only sets the anchor.
        var confirmed = gotIn
        var effects = emptyList<DetectionEffect>()
        for (leg in 1..6) {
            val step = engine.handle(
                confirmed,
                DetectionEvent.Location(
                    fix(T1 + leg * 30_000L, accuracyM = 5f, speedMps = 15f, north = leg * 200.0),
                ),
            )
            confirmed = step.state
            effects = effects + step.effects
        }

        assertEquals(DetectionState.DRIVING, confirmed.state)
        val ended = effects.filterIsInstance<DetectionEffect.EndActiveParking>().single()
        // Better than what §11's bars answered: the record closes when the phone rejoined
        // the car, not whenever 90 s and 500 m were reached afterwards.
        assertEquals(T1, ended.endedAtMillis)
    }

    @Test
    fun `sitting in a parked car with the radio on returns to PARKED and ends nothing`() {
        // The false positive §3a's gating table was narrowed for, on the departure side.
        val parked = pendingCandidate().handle(DetectionEvent.UserConfirmedParking(T0 + 40_000))
        val gotIn = parked.handle(DetectionEvent.CarLinkConnected(T1))

        val step = engine.handle(
            gotIn,
            DetectionEvent.TimerTick(T1 + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS + 60_000),
        )

        assertEquals(DetectionState.PARKED, step.state.state)
        assertTrue(step.effects.none { it is DetectionEffect.EndActiveParking })
    }

    @Test
    fun `DEPARTURE_CANDIDATE to PARKED when the evidence lapses`() {
        val departing = departureCandidate()
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, departing.state)

        val lapsed = departing.handle(DetectionEvent.VehicleExit(T1 + 100_000))

        assertEquals(
            "leaving a record open is recoverable; ending one the user is still inside is not",
            DetectionState.PARKED,
            lapsed.state,
        )
    }

    @Test
    fun `DEPARTURE_CANDIDATE to DRIVING once the section 7 guard confirms`() {
        val departing = departureCandidate()
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, departing.state)

        // Past 800 m and past 120 s: this is a trip, not a shuffle inside the car park.
        val driving = departing
            .handle(DetectionEvent.Location(fix(T1 + 150_000, accuracyM = 5f, speedMps = 16f, north = 1_800.0)))

        assertEquals(DetectionState.DRIVING, driving.state)
    }

    /**
     * docs/05 §11 "Departure rows are edges" (G3, 2026-09-27): the event that clears §11's
     * two bars opens `DEPARTURE_CANDIDATE` and nothing more, even when §7's guard is already
     * met; confirmation is judged on a later event. iOS used to confirm in the same tick
     * cascade, so the two platforms' state traces differed on the same input. iOS twin:
     * `DepartureTests` "A departure is confirmed on a later event than the one that opened it".
     */
    @Test
    fun `a departure is confirmed on a later event than the one that opened it`() {
        // Arrange — saved by hand, got in, one fix to anchor the distance.
        val gotIn = idle()
            .handle(DetectionEvent.UserSavedParking(T0))
            .handle(DetectionEvent.VehicleEnter(T1))
            .handle(DetectionEvent.Location(fix(T1 + 10_000, accuracyM = 5f, speedMps = 15f)))

        // Act — one fix clears §11's bars (130 s, 1000 m) and would also meet §7's guard.
        val opening = engine.handle(
            gotIn,
            DetectionEvent.Location(fix(T1 + 130_000, accuracyM = 5f, speedMps = 15f, north = 1_000.0)),
        )
        val confirming = engine.handle(
            opening.state,
            DetectionEvent.Location(fix(T1 + 160_000, accuracyM = 5f, speedMps = 15f, north = 1_300.0)),
        )

        // Assert — opened, not confirmed; the next event confirms and the parking ends at
        // the DEPARTURE_CANDIDATE entry.
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, opening.state.state)
        assertTrue(opening.effects.none { it is DetectionEffect.EndActiveParking })
        assertEquals(DetectionState.DRIVING, confirming.state.state)
        val ended = confirming.effects.filterIsInstance<DetectionEffect.EndActiveParking>().single()
        assertEquals(T1 + 130_000, ended.endedAtMillis)
    }

    /**
     * The short hop into an underground garage (C1): fixes stop on the ramp, and the exit
     * arrives while `DEPARTURE_CANDIDATE` is still open. §7's guard became true only through
     * elapsed time, so the exit is what confirms the departure — and it is also the end of
     * the drive it confirmed. Read in `DRIVING` too, as `DRIVING_CANDIDATE` already reads the
     * exit that promoted it; swallowed, the next parking was lost. iOS twin: `DepartureTests`
     * "A vehicle_exit that confirms a departure also ends the drive".
     */
    private fun shortDepartureBeforeTheExit(): DetectionEngineState {
        val departing = idle()
            .handle(DetectionEvent.UserSavedParking(at(0)))
            .handle(DetectionEvent.VehicleEnter(at(600)))
            .handle(DetectionEvent.Location(fix(at(610), accuracyM = 5f, speedMps = 12f)))
            .handle(DetectionEvent.Location(fix(at(700), accuracyM = 5f, speedMps = 12f, north = 600.0)))
        assertEquals("the control: §11's bars, short of §7's guard", DetectionState.DEPARTURE_CANDIDATE, departing.state)
        return departing
    }

    @Test
    fun `a vehicle_exit that confirms a departure also ends the drive`() {
        // Arrange
        val departing = shortDepartureBeforeTheExit()

        // Act — 140 s after the enter: §7's duration is met on the exit itself.
        val exit = engine.handle(departing, DetectionEvent.VehicleExit(at(740)))
        val walk = engine.handle(exit.state, DetectionEvent.WalkingEnter(at(760)))

        // Assert — the old parking ends at the DEPARTURE_CANDIDATE entry, and the exit opens
        // the transition the walk confirms.
        val ended = exit.effects.filterIsInstance<DetectionEffect.EndActiveParking>().single()
        assertEquals(at(700), ended.endedAtMillis)
        assertEquals(DetectionState.PARKING_TRANSITION, exit.state.state)
        assertEquals(at(740), exit.state.stateEnteredAtMillis)
        assertEquals(DetectionState.CANDIDATE_PENDING, walk.state.state)
        assertTrue(EvidenceReasonCode.VEHICLE_EXIT_DETECTED in createCandidate(walk.effects).reasons)
    }

    /**
     * docs/05 §11: an exit while `PARKED` keeps the parking and drops the get-in. iOS twin:
     * `DepartureTests` "A derived exit after getting back in keeps the parking and drops the
     * get-in" — iOS has no exit edge on a device and derives it from the walk; Android's
     * Transition API delivers the exit itself, so the same sequence is this `VehicleExit`.
     */
    @Test
    fun `a vehicle_exit after getting back in keeps the parking and drops the get-in`() {
        // Arrange — PARKED with a get-in session open (+600 enter, +610 fix).
        val gotIn = idle()
            .handle(DetectionEvent.UserSavedParking(at(0)))
            .handle(DetectionEvent.VehicleEnter(at(600)))
            .handle(DetectionEvent.Location(fix(at(610), accuracyM = 5f, speedMps = 12f)))
        assertEquals(DetectionState.PARKED, gotIn.state)

        // Act
        val step = engine.handle(gotIn, DetectionEvent.VehicleExit(at(650)))

        // Assert — the parking stays, the get-in goes, and nothing wants a capture any more.
        assertEquals(DetectionState.PARKED, step.state.state)
        assertNull(step.state.session)
        assertNull(LocationCaptureModePolicy.modeWantedBy(step.state))
        assertTrue(step.effects.none { it is DetectionEffect.EndActiveParking })
    }

    @Test
    fun `a car link disconnect that confirms a departure opens the candidate outright`() {
        // Arrange
        val departing = shortDepartureBeforeTheExit()

        // Act — §3a's link row, reached through the departure the disconnect confirmed.
        val step = engine.handle(departing, DetectionEvent.CarLinkDisconnected(at(740)))

        // Assert — the end first, then the new parking, on the same event.
        val endAt = step.effects.indexOfFirst { it is DetectionEffect.EndActiveParking }
        val createAt = step.effects.indexOfFirst { it is DetectionEffect.CreateCandidate }
        assertEquals(at(700), (step.effects[endAt] as DetectionEffect.EndActiveParking).endedAtMillis)
        assertTrue("the parking ends before the next one is raised", endAt in 0 until createAt)
        assertEquals(DetectionState.CANDIDATE_PENDING, step.state.state)
    }

    @Test
    fun `a vehicle_exit that does not meet the guard still returns to PARKED`() {
        // Arrange
        val departing = shortDepartureBeforeTheExit()

        // Act — 110 s after the enter, 600 m: neither §7 bar.
        val step = engine.handle(departing, DetectionEvent.VehicleExit(at(710)))

        // Assert
        assertEquals(DetectionState.PARKED, step.state.state)
        assertTrue(step.effects.none { it is DetectionEffect.EndActiveParking })
    }

    /**
     * docs/05 §11 "An event that confirms a departure is also read in DRIVING": `walking_enter`
     * has no `DRIVING` row (§3a), so a walk that confirms a departure confirms it and nothing
     * else. iOS twin: `DepartureTests` "A walk that confirms a departure only confirms it".
     */
    @Test
    fun `a walk that confirms a departure only confirms it`() {
        // Arrange
        val departing = shortDepartureBeforeTheExit()

        // Act — 140 s after the enter: §7's duration is met on the walk itself.
        val step = engine.handle(departing, DetectionEvent.WalkingEnter(at(740)))

        // Assert
        assertEquals(DetectionState.DRIVING, step.state.state)
        assertEquals(at(700), step.effects.filterIsInstance<DetectionEffect.EndActiveParking>().single().endedAtMillis)
        assertTrue(step.effects.none { it is DetectionEffect.CreateCandidate })
    }

    /**
     * §11b "Vehicle evidence goes stale `recentVehicleWindow` after the connect" (C2): a
     * connect is vehicle evidence for the departure's lapse, even when an earlier
     * `vehicle_enter` opened the departure's session. iOS twin: `DepartureTests` "A link
     * connect after an earlier vehicle_enter holds the departure from the connect".
     */
    @Test
    fun `a link connect after an earlier vehicle_enter holds the departure from the connect`() {
        // Arrange — sitting in the parked car, then the phone joins it.
        val gotIn = idle()
            .handle(DetectionEvent.UserSavedParking(at(0)))
            .handle(DetectionEvent.VehicleEnter(at(600)))

        // Act — the connect lands after the enter's own window (600 + 300 s) has passed.
        val connected = gotIn.handle(DetectionEvent.CarLinkConnected(at(950)))
        val held = connected.handle(DetectionEvent.TimerTick(at(1_200)))
        val lapsed = held.handle(DetectionEvent.TimerTick(at(1_300)))

        // Assert
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, connected.state)
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, held.state)
        assertEquals(DetectionState.PARKED, lapsed.state)
        assertEquals(at(950) + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS, lapsed.stateEnteredAtMillis)
    }

    /** C2's second twin. iOS twin: "A link connect inside DEPARTURE_CANDIDATE postpones the lapse". */
    @Test
    fun `a link connect inside DEPARTURE_CANDIDATE postpones the lapse`() {
        // Arrange — the departure's only vehicle evidence is the enter at T1: it lapses
        // after T1 + 300 s unless something refreshes it.
        val departing = departureCandidate()
        val lateTick = DetectionEvent.TimerTick(T1 + 350_000)
        val control = engine.handle(departing, lateTick)

        // Act
        val connected = departing.handle(DetectionEvent.CarLinkConnected(T1 + 100_000))
        val step = engine.handle(connected, lateTick)

        // Assert — without the connect the departure lapsed; with it, the evidence is still
        // recent, so the tick meets §7's guard instead.
        assertEquals(DetectionState.PARKED, control.state.state)
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, connected.state)
        assertEquals(DetectionState.DRIVING, step.state.state)
        assertEquals(T1 + SUSTAIN, step.effects.filterIsInstance<DetectionEffect.EndActiveParking>().single().endedAtMillis)
    }

    /**
     * The disconnect half of §11b "The link edge is vehicle evidence for the departure's
     * lapse". iOS twin: `DepartureTests` "A link disconnect inside DEPARTURE_CANDIDATE
     * postpones the lapse".
     */
    @Test
    fun `a link disconnect inside DEPARTURE_CANDIDATE postpones the lapse`() {
        // Arrange — the disconnect at T1 + 100 s leaves §7's guard unmet (100 s, 600 m).
        val departing = departureCandidate()
        val lateTick = DetectionEvent.TimerTick(T1 + 350_000)
        val control = engine.handle(departing, lateTick)

        // Act
        val disconnected = departing.handle(DetectionEvent.CarLinkDisconnected(T1 + 100_000))
        val step = engine.handle(disconnected, lateTick)

        // Assert — the disconnect's evidence is 250 s old at the tick: recent, so the tick
        // meets §7's guard where the control lapsed.
        assertEquals(DetectionState.PARKED, control.state.state)
        assertTrue(control.effects.none { it is DetectionEffect.EndActiveParking })
        assertEquals(DetectionState.DEPARTURE_CANDIDATE, disconnected.state)
        assertEquals(T1 + SUSTAIN, step.effects.filterIsInstance<DetectionEffect.EndActiveParking>().single().endedAtMillis)
    }

    // ── §3a "The car link" ──────────────────────────────────────────────────────────

    @Test
    fun `a car link connecting does not skip the 90-second promotion`() {
        val connected = idle().handle(DetectionEvent.CarLinkConnected(T0))

        assertEquals(
            "people sit in parked cars — connecting reaches DRIVING_CANDIDATE and no further",
            DetectionState.DRIVING_CANDIDATE,
            connected.state,
        )

        // Ninety seconds of Bluetooth is not ninety seconds of driving. Sitting there with
        // the radio on reaches the bar in elapsed time and must still produce nothing —
        // otherwise getting back out disconnects into a candidate for a drive that never
        // happened. iOS has always drawn the line here; Android did not until 2026-09-20.
        val stillSitting = connected.handle(DetectionEvent.StationaryExit(T0 + SUSTAIN))

        assertEquals(DetectionState.DRIVING_CANDIDATE, stillSitting.state)

        // Motion is what arms it, and the bar is measured from motion.
        val driving = connected
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.StationaryExit(T0 + SUSTAIN))

        assertEquals(DetectionState.DRIVING, driving.state)
    }

    @Test
    fun `a connected link holds the drive through a gap with no movement evidence`() {
        // §3a "DECIDED 2026-09-20". Underground there are no fixes, so
        // `lastMovementEvidenceAtMillis` never advances and `movementIdleWindow` would fire
        // on the first tick — not because the car stopped but because the sky is gone. The
        // link is the better witness, and while it holds this row is suppressed.
        //
        // Pinned here rather than in `platform-tests` because §3a forbids a fixture that
        // depends on a link event: iOS cannot observe a classic Bluetooth edge, so such a
        // fixture would call a journey one platform cannot detect a shared contract.
        val driving = idle()
            .handle(DetectionEvent.CarLinkConnected(T0))
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.StationaryExit(T0 + SUSTAIN))
            .also { assertEquals(DetectionState.DRIVING, it.state) }

        val quiet = driving.handle(
            DetectionEvent.TimerTick(T0 + SUSTAIN + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS * 3),
        )

        assertEquals(DetectionState.DRIVING, quiet.state)

        // The disconnect is what ends it, and §3a sends that straight to CANDIDATE_PENDING.
        val parked = quiet.handle(
            DetectionEvent.CarLinkDisconnected(T0 + SUSTAIN + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS * 4),
        )

        assertEquals(DetectionState.CANDIDATE_PENDING, parked.state)
        assertEquals(1, parked.candidatesCreated)
    }

    @Test
    fun `the link does not hold a session that never became a drive`() {
        // The boundary of the rule above. `drivingCandidateWindow` is deliberately *not*
        // gated on the link: sitting in a parked car with the radio on is exactly what that
        // row exists to retire, and suppressing it would leave the session open for ever.
        val state = idle()
            .handle(DetectionEvent.CarLinkConnected(T0))
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.DRIVING_CANDIDATE_WINDOW_MILLIS))

        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(0, state.candidatesCreated)
    }

    @Test
    fun `getting in and changing your mind produces nothing`() {
        val state = idle()
            .handle(DetectionEvent.CarLinkConnected(T0))
            .handle(DetectionEvent.CarLinkDisconnected(T0 + 20_000))

        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(0, state.candidatesCreated)
    }

    @Test
    fun `a car link disconnecting goes straight to CANDIDATE_PENDING`() {
        val state = driving().handle(DetectionEvent.CarLinkDisconnected(T0 + 1_000))

        assertEquals(
            "PARKING_TRANSITION is skipped — underground there is never a walk to wait for",
            DetectionState.CANDIDATE_PENDING,
            state.state,
        )
        assertTrue(
            EvidenceReasonCode.CAR_PROJECTION_DISCONNECTED in checkNotNull(state.candidate).reasons,
        )
    }

    @Test
    fun `a car link reconnecting retires the candidate — the fuel stop`() {
        val pending = driving().handle(DetectionEvent.CarLinkDisconnected(T0 + 1_000))
        val candidateId = checkNotNull(pending.candidate).id

        val step = engine.handle(pending, DetectionEvent.CarLinkConnected(T0 + 120_000))

        assertEquals(DetectionState.DRIVING, step.state.state)
        assertNull(step.state.candidate)
        assertEquals(
            listOf(DetectionEffect.RetireCandidate(candidateId)),
            step.effects.filterIsInstance<DetectionEffect.RetireCandidate>(),
        )
        assertFalse(
            "the retired candidate is given back, so the real parking can still be found",
            checkNotNull(step.state.session).candidateProduced,
        )
    }

    @Test
    fun `the parking after a fuel stop still produces a candidate`() {
        val resumed = driving()
            .handle(DetectionEvent.CarLinkDisconnected(T0 + 1_000))
            .handle(DetectionEvent.CarLinkConnected(T0 + 120_000))

        val parked = resumed.handle(DetectionEvent.CarLinkDisconnected(T0 + 600_000))

        assertEquals(DetectionState.CANDIDATE_PENDING, parked.state)
        assertEquals(2, parked.candidatesCreated)
    }

    // ── §3a prose rules ─────────────────────────────────────────────────────────────

    @Test
    fun `a rejected candidate returns to IDLE and re-arms the next trip`() {
        val pending = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 30_000))
        assertEquals(1, pending.candidatesCreated)

        // §3a: "leaving CANDIDATE_PENDING by rejection or expiry returns to IDLE."
        val idle = pending.handle(DetectionEvent.UserRejectedParking(T0 + 40_000))
        assertEquals(DetectionState.IDLE, idle.state)

        val nextTrip = idle
            .handle(DetectionEvent.VehicleEnter(T1))
            .handle(DetectionEvent.VehicleExit(T1 + 300_000))
            .handle(DetectionEvent.WalkingEnter(T1 + 320_000))

        assertEquals(DetectionState.CANDIDATE_PENDING, nextTrip.state)
        assertEquals("a new session may guess again", 2, nextTrip.candidatesCreated)
    }

    @Test
    fun `a session that already produced a candidate cannot produce a second one`() {
        // Reach CANDIDATE_PENDING, get the candidate retired by a reconnect, then take the
        // session back through PARKING_TRANSITION with the flag deliberately still set.
        val pending = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 30_000))
        val spentSession = pending.copy(
            state = DetectionState.PARKING_TRANSITION,
            stateEnteredAtMillis = T0 + 40_000,
        )

        val step = engine.handle(spentSession, DetectionEvent.WalkingEnter(T0 + 50_000))

        assertEquals(
            "§12: the trip has to pass through IDLE before it may guess again",
            DetectionState.IDLE,
            step.state.state,
        )
        assertEquals(1, step.state.candidatesCreated)
        assertTrue(step.effects.none { it is DetectionEffect.CreateCandidate })
    }

    @Test
    fun `movement evidence does not gate promotion`() {
        // The 2026-09-19 14:26 trip: vehicle_enter, vehicle_exit, walking_enter, and not a
        // single location event. Gated on movement it would never leave DRIVING_CANDIDATE.
        val state = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.VehicleExit(T0 + 7 * 60_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 7 * 60_000 + 20_000))

        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertEquals(1, state.candidatesCreated)
    }

    @Test
    fun `every main-table transition works with no car link at all`() {
        // The same drive as the link tests, driven purely by motion: IDLE -> ... -> PARKED.
        val parked = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .also { assertEquals(DetectionState.DRIVING_CANDIDATE, it.state) }
            .handle(DetectionEvent.Location(fix(T0 + SUSTAIN, accuracyM = 8f, speedMps = 14f)))
            .also { assertEquals(DetectionState.DRIVING, it.state) }
            .handle(DetectionEvent.VehicleExit(T0 + 300_000))
            .also { assertEquals(DetectionState.PARKING_TRANSITION, it.state) }
            .handle(DetectionEvent.WalkingEnter(T0 + 320_000))
            .also { assertEquals(DetectionState.CANDIDATE_PENDING, it.state) }
            .handle(DetectionEvent.UserConfirmedParking(T0 + 400_000))

        assertEquals(DetectionState.PARKED, parked.state)
    }

    @Test
    fun `reason codes come from what the drive recorded, in contract order`() {
        // Arrange — one moving fix at 90 s, a drop with no buckets (read as `poor`) at 200 s,
        // and the exit at 300 s, by which time movement had been quiet for 210 s.
        val transition = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.Location(fix(T0 + SUSTAIN, accuracyM = 8f, speedMps = 14f)))
            .handle(DetectionEvent.LocationQualityDegraded(T0 + 200_000))
            .handle(DetectionEvent.VehicleExit(T0 + 300_000))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(T0 + 320_000))

        // Assert — §4's order, which is the order iOS emits; no code from after the stop.
        assertEquals(
            listOf(
                EvidenceReasonCode.RECENT_VEHICLE_ACTIVITY,
                EvidenceReasonCode.VEHICLE_DURATION_MET,
                EvidenceReasonCode.VEHICLE_EXIT_DETECTED,
                EvidenceReasonCode.WALKING_AFTER_VEHICLE,
                EvidenceReasonCode.LOCATION_STOPPED,
                EvidenceReasonCode.LOCATION_QUALITY_DEGRADED,
                EvidenceReasonCode.RELIABLE_LOCATION_CAPTURED,
            ),
            checkNotNull(state.candidate).reasons,
        )
    }

    @Test
    fun `the candidate carries a confidence bucket and never a raw score across the boundary`() {
        val state = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 30_000))

        val candidate = checkNotNull(state.candidate)
        assertEquals(ParkingConfidencePolicy.bucketOf(candidate.score), candidate.confidence)
        assertTrue(candidate.confidence in ConfidenceBucket.entries)
    }

    @Test
    fun `the checkpoint names the candidate the notification is about`() {
        val step = engine.handle(
            driving().handle(DetectionEvent.VehicleExit(T0 + 1_000)),
            DetectionEvent.WalkingEnter(T0 + 30_000),
        )

        val created = step.effects.filterIsInstance<DetectionEffect.CreateCandidate>().single()
        val checkpoint = step.effects.filterIsInstance<DetectionEffect.PersistCheckpoint>().single().checkpoint
        assertEquals(created.candidateId, checkpoint.candidateId)
        assertEquals(DetectionState.CANDIDATE_PENDING, checkpoint.state)
    }

    // ── Field parity, 2026-09-27 ────────────────────────────────────────────────────
    // Each test below pins one place where replaying the same field draft through both
    // engines produced a different candidate, bucket or reason list.

    @Test
    fun `a new journey after an unanswered candidate carries nothing from the first trip`() {
        // Arrange — trip 1 ends with an exit and a walk; trip 2 has neither.
        val firstTrip = pendingCandidate()
        val secondStart = T0 + 900_000
        val moving = firstTrip
            .handle(DetectionEvent.VehicleEnter(secondStart))
            .handle(DetectionEvent.Location(fix(secondStart + SUSTAIN, accuracyM = 8f, speedMps = 14f)))
            .handle(DetectionEvent.Location(fix(secondStart + SUSTAIN + 60_000, accuracyM = 8f, speedMps = 14f, north = 900.0)))
        assertEquals(DetectionState.DRIVING, moving.state)

        // Act — trip 2 ends on a location stop after the movement window.
        val stopAt = secondStart + SUSTAIN + 60_000 + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS
        val state = moving.handle(DetectionEvent.Location(fix(stopAt, accuracyM = 8f, speedMps = 0.2f, north = 900.0)))

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertEquals(2, state.candidatesCreated)
        val reasons = checkNotNull(state.candidate).reasons
        assertFalse("trip 1's walk is not trip 2's evidence", EvidenceReasonCode.WALKING_AFTER_VEHICLE in reasons)
        assertEquals(
            "the new journey is measured from its own vehicle_enter",
            secondStart,
            checkNotNull(state.session).evidence.vehicleFirstSeenAtMillis,
        )
    }

    @Test
    fun `getting back out of a parked car drops the departure's evidence`() {
        // Arrange
        val parked = idle().handle(DetectionEvent.UserSavedParking(T0))

        // Act — in, 300 m, out.
        val state = parked
            .handle(DetectionEvent.VehicleEnter(T1))
            .handle(DetectionEvent.Location(fix(T1 + 10_000, accuracyM = 5f, speedMps = 15f)))
            .handle(DetectionEvent.Location(fix(T1 + 40_000, accuracyM = 5f, speedMps = 15f, north = 300.0)))
            .handle(DetectionEvent.VehicleExit(T1 + 50_000))

        // Assert — the next episode has to earn §11's 500 m on its own.
        assertEquals(DetectionState.PARKED, state.state)
        assertNull(state.session)
    }

    @Test
    fun `a red light early in the drive does not earn location stopped`() {
        // Arrange — stopped at 10 s, moving until the exit.
        val transition = driving()
            .handle(DetectionEvent.Location(fix(T0 + 10_000, accuracyM = 8f, speedMps = 0.5f)))
            .handle(DetectionEvent.Location(fix(T0 + 60_000, accuracyM = 8f, speedMps = 14f, north = 400.0)))
            .handle(DetectionEvent.Location(fix(T0 + 120_000, accuracyM = 8f, speedMps = 14f, north = 1_200.0)))
            .handle(DetectionEvent.VehicleExit(T0 + 125_000))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(T0 + 150_000))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_STOPPED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a stopped fix after the last moving one earns location stopped`() {
        // Arrange
        val transition = driving()
            .handle(DetectionEvent.Location(fix(T0 + 60_000, accuracyM = 8f, speedMps = 14f, north = 400.0)))
            .handle(DetectionEvent.Location(fix(T0 + 120_000, accuracyM = 8f, speedMps = 14f, north = 1_200.0)))
            .handle(DetectionEvent.Location(fix(T0 + 123_000, accuracyM = 8f, speedMps = 0.3f, north = 1_200.0)))
            .handle(DetectionEvent.VehicleExit(T0 + 125_000))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(T0 + 150_000))

        // Assert
        assertTrue(EvidenceReasonCode.LOCATION_STOPPED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `quality that degraded long before the end is not degradation near the end`() {
        // Arrange — degraded 10 s in, then ten minutes of clean moving fixes.
        var state = driving()
            .handle(DetectionEvent.Location(fix(T0, accuracyM = 8f, speedMps = 14f)))
            .handle(degraded(T0 + 10_000, LocationQualityBucket.GOOD, LocationQualityBucket.POOR))
        for (leg in 1..4) {
            state = state.handle(
                DetectionEvent.Location(fix(T0 + leg * 150_000L, accuracyM = 8f, speedMps = 14f, north = leg * 1_000.0)),
            )
        }

        // Act
        val candidate = state
            .handle(DetectionEvent.VehicleExit(T0 + 620_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 650_000))
            .candidate

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(candidate).reasons)
    }

    @Test
    fun `quality that degraded to poor just before the end counts`() {
        // Arrange
        val transition = driving()
            .handle(DetectionEvent.Location(fix(T0, accuracyM = 8f, speedMps = 14f)))
            .handle(degraded(T0 + 100_000, LocationQualityBucket.GOOD, LocationQualityBucket.POOR))
            .handle(DetectionEvent.Location(fix(T0 + 110_000, accuracyM = 8f, speedMps = 14f, north = 1_000.0)))
            .handle(DetectionEvent.VehicleExit(T0 + 200_000))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(T0 + 230_000))

        // Assert
        assertTrue(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a drop that stays fair is not degradation`() {
        // Arrange — §8 weighs losing the sky, and a fair fix still has it.
        val transition = driving()
            .handle(DetectionEvent.Location(fix(T0, accuracyM = 8f, speedMps = 14f)))
            .handle(DetectionEvent.Location(fix(T0 + 100_000, accuracyM = 30f, speedMps = 14f, north = 1_000.0)))
            .handle(degraded(T0 + 100_000, LocationQualityBucket.GOOD, LocationQualityBucket.FAIR))
            .handle(DetectionEvent.VehicleExit(T0 + 110_000))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(T0 + 130_000))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `duration is measured to the end of the drive, not to the walk after it`() {
        // Arrange — a 100 s drive: promoted at 90 s, out at 100 s.
        val transition = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.StationaryExit(T0 + SUSTAIN))
            .handle(DetectionEvent.VehicleExit(T0 + 100_000))

        // Act — the walk arrives at 130 s, past §7's 120 s.
        val state = transition.handle(DetectionEvent.WalkingEnter(T0 + 130_000))

        // Assert
        val candidate = checkNotNull(state.candidate)
        assertFalse(EvidenceReasonCode.VEHICLE_DURATION_MET in candidate.reasons)
        assertEquals(
            "§8 trip below minimum applies",
            ParkingConfidencePolicy.WEIGHT_RECENT_VEHICLE_SESSION +
                ParkingConfidencePolicy.WEIGHT_VEHICLE_EXIT +
                ParkingConfidencePolicy.WEIGHT_WALKING_AFTER_VEHICLE +
                ParkingConfidencePolicy.WEIGHT_TRIP_BELOW_MINIMUM,
            candidate.score,
        )
    }

    @Test
    fun `a vehicle enter that resumes a movement-idle transition stays driving`() {
        // Arrange
        val idleAt = T0 + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS
        val transition = driving()
            .handle(DetectionEvent.Location(fix(T0, accuracyM = 8f, speedMps = 14f)))
            .handle(DetectionEvent.TimerTick(idleAt))
        assertEquals(DetectionState.PARKING_TRANSITION, transition.state)

        // Act
        val resumed = transition.handle(DetectionEvent.VehicleEnter(idleAt + 20_000))

        // Assert — the idle clock restarts at the resume instead of re-firing at once.
        assertEquals(DetectionState.DRIVING, resumed.state)
        assertEquals(idleAt + 20_000, resumed.stateEnteredAtMillis)
        assertEquals(
            DetectionState.PARKING_TRANSITION,
            resumed.handle(DetectionEvent.TimerTick(idleAt + 20_000 + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS)).state,
        )
    }

    @Test
    fun `a fix the candidate cannot inherit does not earn reliable location captured`() {
        // Arrange — a 90-minute drive whose only good fix came at minute one. The rest of
        // the drive keeps moving on fixes too coarse to be a parking spot, so neither
        // `movementIdleWindow` nor the transition lapse ends it before the exit.
        val endedAt = T0 + 90 * 60_000L
        var moving = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.Location(fix(T0 + 60_000, accuracyM = 9f, speedMps = 14f)))
            .handle(DetectionEvent.StationaryExit(T0 + SUSTAIN))
        for (leg in 1..35) {
            moving = moving.handle(
                DetectionEvent.Location(
                    fix(T0 + 60_000 + leg * 150_000L, accuracyM = 100f, speedMps = 14f, north = leg * 2_000.0),
                ),
            )
        }
        val transition = moving.handle(DetectionEvent.VehicleExit(endedAt))

        // Act
        val step = engine.handle(transition, DetectionEvent.WalkingEnter(endedAt + 30_000))

        // Assert
        assertNull(createCandidate(step.effects).lastReliableLocation)
        assertFalse(EvidenceReasonCode.RELIABLE_LOCATION_CAPTURED in checkNotNull(step.state.candidate).reasons)
    }

    @Test
    fun `an implausible jump never becomes the parking spot`() {
        // Arrange
        val good = driving().handle(DetectionEvent.Location(fix(T0, accuracyM = 5f, speedMps = 14f)))
        val spot = checkNotNull(good.lastReliableLocation)

        // Act — 1 km in one second.
        val state = good.handle(DetectionEvent.Location(fix(T0 + 1_000, accuracyM = 5f, speedMps = 14f, north = 1_000.0)))

        // Assert
        assertEquals(spot, state.lastReliableLocation)
    }

    @Test
    fun `a fix while a candidate is pending does not move the parking spot`() {
        // Arrange
        val pending = pendingCandidate()
        val spot = pending.lastReliableLocation

        // Act — the walk away from the car.
        val state = pending.handle(DetectionEvent.Location(fix(T0 + 40_000, accuracyM = 5f, speedMps = 1.2f, north = 80.0)))

        // Assert
        assertEquals(spot, state.lastReliableLocation)
    }

    @Test
    fun `the car link latch outlives the session it was connected in`() {
        // Arrange — sitting in the car with the radio on retires the session, the link stays.
        val idleAgain = idle()
            .handle(DetectionEvent.CarLinkConnected(T0))
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.DRIVING_CANDIDATE_WINDOW_MILLIS))
        assertEquals(DetectionState.IDLE, idleAgain.state)

        // Act — then the drive, and a gap with no movement evidence.
        val state = idleAgain
            .handle(DetectionEvent.VehicleEnter(T1))
            .handle(DetectionEvent.Location(fix(T1 + SUSTAIN, accuracyM = 8f, speedMps = 14f)))
            .handle(DetectionEvent.TimerTick(T1 + SUSTAIN + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS))

        // Assert
        assertEquals(DetectionState.DRIVING, state.state)
    }

    @Test
    fun `a departure whose guard is met on the lapse boundary is confirmed`() {
        // Arrange — the departure's only vehicle evidence is the vehicle_enter at T1.
        val departing = departureCandidate()

        // Act — exactly RECENT_VEHICLE_WINDOW later, with §7's guard satisfied.
        val step = engine.handle(
            departing,
            DetectionEvent.Location(
                fix(T1 + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS, accuracyM = 5f, speedMps = 15f, north = 1_500.0),
            ),
        )

        // Assert — recent evidence is inclusive (<=), so the lapse must be exclusive (>).
        assertEquals(DetectionState.DRIVING, step.state.state)
    }

    @Test
    fun `a confirmed candidate leaves no snapshot behind`() {
        val state = pendingCandidate().handle(DetectionEvent.UserConfirmedParking(T0 + 40_000))

        assertEquals(DetectionState.PARKED, state.state)
        assertNull(state.candidate)
    }

    @Test
    fun `a candidate left behind by a new journey still expires at 45 minutes`() {
        // Arrange
        val pending = pendingCandidate()
        val candidate = checkNotNull(pending.candidate)
        val newJourney = pending.handle(DetectionEvent.VehicleEnter(T0 + 900_000))

        // Act
        val step = engine.handle(newJourney, DetectionEvent.TimerTick(candidate.expiresAtMillis))

        // Assert — the notification comes down; the new journey is not disturbed by it.
        assertTrue(DetectionEffect.RetireCandidate(candidate.id) in step.effects)
        assertNull(step.state.candidate)
        assertTrue(step.state.state != DetectionState.CANDIDATE_PENDING)
    }

    @Test
    fun `a hand save withdraws a candidate left behind by a new journey`() {
        // Arrange
        val pending = pendingCandidate()
        val candidate = checkNotNull(pending.candidate)
        val newJourney = pending.handle(DetectionEvent.VehicleEnter(T0 + 900_000))

        // Act
        val step = engine.handle(newJourney, DetectionEvent.UserSavedParking(T0 + 1_000_000))

        // Assert
        assertEquals(DetectionState.PARKED, step.state.state)
        assertTrue(DetectionEffect.RetireCandidate(candidate.id) in step.effects)
        assertNull(step.state.candidate)
    }

    // ── §3a PARKING_TRANSITION rows and §8b evidence, twinned with iOS ─────────────
    // Each test below has a twin of the same name and the same numbers in
    // `ParkingPinTests/ParkingTransitionEvidenceTests`. A divergence between the two is a
    // divergence in the product (CLAUDE.md "Cross-platform Behavioral Parity").

    @Test
    fun `a movementIdle ending earns location stopped but no vehicle exit`() {
        // Arrange
        val transition = idleTransition()

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(300)))

        // Assert — 25 + 30 (walk) + 10 (stopped) + 5 (280 s is twice §7's 120 s) = 70.
        val candidate = checkNotNull(state.candidate)
        assertTrue(EvidenceReasonCode.WALKING_AFTER_VEHICLE in candidate.reasons)
        assertTrue(EvidenceReasonCode.LOCATION_STOPPED in candidate.reasons)
        assertFalse(EvidenceReasonCode.VEHICLE_EXIT_DETECTED in candidate.reasons)
        assertEquals(70, candidate.score)
        assertEquals(ConfidenceBucket.MEDIUM, candidate.confidence)
    }

    @Test
    fun `a vehicle exit that arrives inside a movementIdle transition is credited`() {
        // Arrange
        val transition = idleTransition().handle(DetectionEvent.VehicleExit(at(290)))
        assertEquals("an exit is not a confirming signal", DetectionState.PARKING_TRANSITION, transition.state)

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(300)))

        // Assert
        assertTrue(EvidenceReasonCode.VEHICLE_EXIT_DETECTED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a disconnect inside the transition confirms it as a link disconnect and an exit`() {
        // Arrange
        val transition = drivenFromEnter()
            .handle(DetectionEvent.CarLinkConnected(at(95)))
            .handle(DetectionEvent.VehicleExit(at(300)))

        // Act
        val state = transition.handle(DetectionEvent.CarLinkDisconnected(at(320)))

        // Assert
        val reasons = checkNotNull(state.candidate).reasons
        assertTrue(EvidenceReasonCode.CAR_PROJECTION_DISCONNECTED in reasons)
        assertTrue(EvidenceReasonCode.VEHICLE_EXIT_DETECTED in reasons)
    }

    @Test
    fun `a stop long before the end of a drive is not location stopped`() {
        // Arrange — a drive with no moving sample whose one stopped fix came 600 s early.
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 0.5f)))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_STOPPED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a stop near the end of a drive with no moving sample is location stopped`() {
        // Arrange
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(650), accuracyM = 8f, speedMps = 0.5f)))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertTrue(EvidenceReasonCode.LOCATION_STOPPED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a stop the drive then moved on from is not location stopped`() {
        // Arrange
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(600), accuracyM = 8f, speedMps = 0.5f)))
            .handle(DetectionEvent.Location(fix(at(650), accuracyM = 8f, speedMps = 9f, north = 400.0)))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_STOPPED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a speedless fix after the last moving one is not location stopped`() {
        // Arrange — the underground ramp: a moving fix, then a fix with no Doppler speed.
        // §8b: a speedless fix never counts, because silence is not stillness (§7).
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(600), accuracyM = 8f, speedMps = 9f)))
            .handle(DetectionEvent.Location(fix(at(650), accuracyM = 8f, speedMps = null)))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_STOPPED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a stop from before a vehicle enter resume is not how the drive ended`() {
        // Arrange — stopped at 120 s, idled into a transition at 280 s, resumed by a
        // vehicle_enter at 300 s: the resume re-anchors the last movement (§3a "Resuming
        // keeps the drive"), so the 120 s stop belongs to a red light the drive left.
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 9f)))
            .handle(DetectionEvent.Location(fix(at(120), accuracyM = 8f, speedMps = 0.5f)))
            .handle(DetectionEvent.TimerTick(at(290)))
            .also { assertEquals(DetectionState.PARKING_TRANSITION, it.state) }
            .handle(DetectionEvent.VehicleEnter(at(300)))
            .also { assertEquals(DetectionState.DRIVING, it.state) }
            .handle(DetectionEvent.VehicleExit(at(400)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(410)))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_STOPPED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a degradation to poor in the drive's last minutes is credited`() {
        // Arrange — the event lands while still DRIVING.
        val transition = drivenFromEnter()
            .handle(degraded(at(680), LocationQualityBucket.GOOD, LocationQualityBucket.POOR))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertTrue(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `good to fair is not a degradation`() {
        // Arrange
        val transition = drivenFromEnter()
            .handle(degraded(at(680), LocationQualityBucket.GOOD, LocationQualityBucket.FAIR))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a degradation long before the end is not credited`() {
        // Arrange
        val transition = drivenFromEnter()
            .handle(degraded(at(300), LocationQualityBucket.GOOD, LocationQualityBucket.POOR))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a fix-to-fix drop into poor near the end is credited`() {
        // Arrange — no event at all: no adapter sends one on a device, so fixes must say it.
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(650), accuracyM = 10f, speedMps = null)))
            .handle(DetectionEvent.Location(fix(at(670), accuracyM = 80f, speedMps = null)))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertTrue(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a drive that was poor throughout never fell into poor`() {
        // Arrange — §8b weighs a *fall* into poor. A long underground stretch that was poor
        // from its first fix has no drop near the end, however poor its last fix is.
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(600), accuracyM = 80f, speedMps = null)))
            .handle(DetectionEvent.Location(fix(at(650), accuracyM = 90f, speedMps = null)))
            .handle(DetectionEvent.VehicleExit(at(700)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(710)))

        // Assert
        assertFalse(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    @Test
    fun `a fall into poor near the end still counts after the quality recovers`() {
        // Arrange — good, poor at 450 s, fair again at 480 s, exit at 600 s: the fall is
        // inside nearEndHorizon even though the drive did not end on a poor fix.
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(400), accuracyM = 10f, speedMps = null)))
            .handle(DetectionEvent.Location(fix(at(450), accuracyM = 80f, speedMps = null)))
            .handle(DetectionEvent.Location(fix(at(480), accuracyM = 30f, speedMps = null)))
            .handle(DetectionEvent.VehicleExit(at(600)))

        // Act
        val state = transition.handle(DetectionEvent.WalkingEnter(at(610)))

        // Assert
        assertTrue(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in checkNotNull(state.candidate).reasons)
    }

    // ── §3a "location stop" row: accepted, reported, at or after the entry ───────────

    @Test
    fun `an outlier fix that reports a stop confirms nothing`() {
        // Arrange — §3a location stop, condition 1: the drive's §5 gate must accept the fix.
        val transition = driving()
            .handle(DetectionEvent.Location(fix(T0 + 10_000, accuracyM = 8f, speedMps = 12f)))
            .handle(DetectionEvent.VehicleExit(T0 + 20_000))

        // Act — 10 km in 10 s, reporting speed 0: a GPS jump on the way underground.
        val state = transition.handle(
            DetectionEvent.Location(fix(T0 + 30_000, accuracyM = 8f, speedMps = 0f, north = 10_000.0)),
        )

        // Assert
        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
        assertEquals(0, state.candidatesCreated)
    }

    @Test
    fun `a stopped fix timestamped before the transition confirms nothing`() {
        // Arrange — §3a location stop, condition 3: at or after the transition's entry.
        val transition = driving()
            .handle(DetectionEvent.Location(fix(T0 + 10_000, accuracyM = 8f, speedMps = 12f)))
            .handle(DetectionEvent.VehicleExit(T0 + 100_000))

        // Act — a fix from before the exit, delivered late.
        val state = transition.handle(DetectionEvent.Location(fix(T0 + 50_000, accuracyM = 8f, speedMps = 0f)))

        // Assert
        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
        assertEquals(0, state.candidatesCreated)
    }

    @Test
    fun `a fix with an invalid accuracy confirms nothing`() {
        // Arrange
        val transition = driving().handle(DetectionEvent.VehicleExit(T0 + 1_000))

        // Act
        val state = transition.handle(DetectionEvent.Location(fix(T0 + 30_000, accuracyM = -1f, speedMps = 0f)))

        // Assert
        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
    }

    // ── Window rows are stamped at their deadline (§3a, 2026-09-27) ───────────────────

    @Test
    fun `movementIdle enters the transition at its deadline, not when it is noticed`() {
        // Arrange
        val moving = drivenFromEnter().handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 9f)))

        // Act — noticed 120 s late.
        val state = moving.handle(DetectionEvent.TimerTick(at(400)))

        // Assert
        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
        assertEquals(at(280), state.stateEnteredAtMillis)
        assertEquals("the stop §8b measures from", at(280), checkNotNull(state.session?.stop).atMillis)
    }

    @Test
    fun `the transition window runs from the deadline, so a late-noticed idle cannot stretch it`() {
        // Arrange
        val transition = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 9f)))
            .handle(DetectionEvent.TimerTick(at(400)))

        // Act — 301 s after the deadline, 181 s after it was noticed.
        val state = transition.handle(
            DetectionEvent.WalkingEnter(at(280) + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS + 1_000),
        )

        // Assert
        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(0, state.candidatesCreated)
    }

    @Test
    fun `the transition lapse is stamped at its deadline`() {
        // Arrange
        val transition = drivenFromEnter().handle(DetectionEvent.VehicleExit(at(200)))

        // Act
        val state = transition.handle(DetectionEvent.TimerTick(at(900)))

        // Assert
        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(at(200) + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS, state.stateEnteredAtMillis)
    }

    @Test
    fun `the driving candidate lapse is stamped at its deadline`() {
        // Arrange — a link with no vehicle activity: nothing will ever promote it.
        val candidate = idle().handle(DetectionEvent.CarLinkConnected(T0))

        // Act
        val state = candidate.handle(DetectionEvent.TimerTick(at(1_000)))

        // Assert
        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(T0 + ParkingDetectionEngine.DRIVING_CANDIDATE_WINDOW_MILLIS, state.stateEnteredAtMillis)
    }

    @Test
    fun `the session ceiling is stamped at its deadline`() {
        // Act
        val state = drivenFromEnter().handle(
            DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.SESSION_MAXIMUM_DURATION_MILLIS + 600_000),
        )

        // Assert
        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(T0 + ParkingDetectionEngine.SESSION_MAXIMUM_DURATION_MILLIS, state.stateEnteredAtMillis)
    }

    @Test
    fun `the candidate expiry is stamped at its deadline`() {
        // Arrange
        val pending = pendingCandidate()
        val expiresAt = checkNotNull(pending.candidate).expiresAtMillis

        // Act
        val state = pending.handle(DetectionEvent.TimerTick(expiresAt + 600_000))

        // Assert
        assertEquals(DetectionState.IDLE, state.state)
        assertEquals(expiresAt, state.stateEnteredAtMillis)
    }

    @Test
    fun `the departure lapse is stamped at its deadline`() {
        // Arrange — the departure's only vehicle evidence is the vehicle_enter at T1.
        val departing = departureCandidate()

        // Act
        val state = departing.handle(DetectionEvent.TimerTick(T1 + 900_000))

        // Assert
        assertEquals(DetectionState.PARKED, state.state)
        assertEquals(T1 + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS, state.stateEnteredAtMillis)
    }

    // ── What a pending candidate's drive keeps ────────────────────────────────────────

    @Test
    fun `a fix while a candidate is pending is not folded into the finished drive`() {
        // Arrange — iOS holds the pending candidate's drive aside and folds nothing into it;
        // a car-link reconnect resumes it exactly as it ended (§3a, the fuel stop).
        val pending = pendingCandidate()
        val drive = checkNotNull(pending.session)

        // Act — the walk away from the car.
        val state = pending.handle(DetectionEvent.Location(fix(T0 + 40_000, accuracyM = 5f, speedMps = 1.2f, north = 80.0)))

        // Assert
        assertEquals(drive, state.session)
    }

    @Test
    fun `a seeded DRIVING session begins where the replay does`() {
        // Arrange / Act — the iOS runner restores `DRIVING` with the drive starting at the
        // fixture's origin, so a seeded drive here must not be 90 s older than that one.
        val state = DetectionEngineState.startingIn(DetectionState.DRIVING, T0)

        // Assert
        assertEquals(T0, checkNotNull(state.session).evidence.vehicleFirstSeenAtMillis)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private fun degraded(atMillis: Long, from: LocationQualityBucket, to: LocationQualityBucket) =
        DetectionEvent.LocationQualityDegraded(atMillis, fromBucket = from, toBucket = to)

    // ── §5: the fix a candidate inherits ────────────────────────────────────────────

    @Test
    fun `a candidate refuses a fix older than the drive that produced it`() {
        // Arrange — the 2026-09-20 Android trace, in miniature. The last fix good enough to
        // be admitted came at noon; the car was driven and parked five hours later, and
        // nothing underground was ever good enough to replace it.
        val noonFix = ReliableLocation(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE,
            horizontalAccuracyM = 17.7f,
            capturedAtMillis = T0,
        )
        val driveStart = T0 + 5 * 60 * 60 * 1000L
        val transition = idle()
            .copy(lastReliableLocation = noonFix)
            .handle(DetectionEvent.VehicleEnter(driveStart))
            .handle(DetectionEvent.StationaryExit(driveStart + SUSTAIN))
            .handle(DetectionEvent.VehicleExit(driveStart + 20 * 60_000L))

        // Act
        val step = engine.handle(transition, DetectionEvent.WalkingEnter(driveStart + 20 * 60_000L + 30_000L))

        // Assert — a candidate, and no coordinate on it. A wrong one is worse than none:
        // the confirmation screen would have drawn it on a map with its accuracy beside it.
        assertEquals(DetectionState.CANDIDATE_PENDING, step.state.state)
        assertNull(
            "the fix predates the drive, so it is the origin at best and noise at worst",
            createCandidate(step.effects).lastReliableLocation,
        )
        // The running value is untouched: only what the candidate *inherits* is bounded.
        assertEquals(noonFix, step.state.lastReliableLocation)
    }

    @Test
    fun `a fix taken during the drive is inherited`() {
        // The control for the test above: same shape, fix taken after the wheels turned.
        val driveStart = T0
        val duringDrive = ReliableLocation(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE,
            horizontalAccuracyM = 12f,
            capturedAtMillis = driveStart + 19 * 60_000L,
        )
        val transition = idle()
            .handle(DetectionEvent.VehicleEnter(driveStart))
            .handle(DetectionEvent.StationaryExit(driveStart + SUSTAIN))
            .copy(lastReliableLocation = duringDrive)
            .handle(DetectionEvent.VehicleExit(driveStart + 20 * 60_000L))

        val step = engine.handle(transition, DetectionEvent.WalkingEnter(driveStart + 20 * 60_000L + 30_000L))

        assertEquals(DetectionState.CANDIDATE_PENDING, step.state.state)
        assertEquals(duringDrive, createCandidate(step.effects).lastReliableLocation)
    }

    @Test
    fun `a fix from inside the drive but older than the window is refused`() {
        // A ninety-minute motorway run whose only good fix came at minute two. Being on the
        // motorway then says nothing about where the car stopped at minute ninety.
        val driveStart = T0
        val early = ReliableLocation(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE,
            horizontalAccuracyM = 9f,
            capturedAtMillis = driveStart + 2 * 60_000L,
        )
        val endedAt = driveStart + 90 * 60_000L
        val transition = idle()
            .handle(DetectionEvent.VehicleEnter(driveStart))
            .handle(DetectionEvent.StationaryExit(driveStart + SUSTAIN))
            .copy(lastReliableLocation = early)
            .handle(DetectionEvent.VehicleExit(endedAt))

        val step = engine.handle(transition, DetectionEvent.WalkingEnter(endedAt + 30_000L))

        assertEquals(DetectionState.CANDIDATE_PENDING, step.state.state)
        assertNull(createCandidate(step.effects).lastReliableLocation)
    }

    // ── §11c: a parking the user saved themselves ───────────────────────────────────

    @Test
    fun `IDLE to PARKED when the user saves a parking by hand`() {
        // Arrange
        val idle = idle()

        // Act
        val step = engine.handle(idle, DetectionEvent.UserSavedParking(T1))

        // Assert
        assertEquals(DetectionState.PARKED, step.state.state)
        assertEquals(T1, step.state.stateEnteredAtMillis)
        assertNull(step.state.session)
        assertEquals(0, step.state.candidatesCreated)
        assertTrue("§14: the move is checkpointed", step.effects.any { it is DetectionEffect.PersistCheckpoint })
        assertTrue(step.effects.none { it is DetectionEffect.CreateCandidate })
    }

    @Test
    fun `a hand-saved parking from any inferring state lands in PARKED and ends nothing`() {
        // Arrange — every state that holds a travel session the save makes moot.
        val inferring = mapOf(
            "DRIVING_CANDIDATE" to idle().handle(DetectionEvent.VehicleEnter(T0)),
            "DRIVING" to driving(),
            "PARKING_TRANSITION" to driving().handle(DetectionEvent.VehicleExit(T0 + 1_000)),
            "DEPARTURE_CANDIDATE" to departureCandidate(),
        )
        val savedAt = T1 + 3_600_000L

        inferring.forEach { (name, before) ->
            // Act
            val step = engine.handle(before, DetectionEvent.UserSavedParking(savedAt))

            // Assert — §11c: whatever was being inferred is dropped, silently. In particular
            // `EndActiveParking` here would close the record the user just wrote.
            assertEquals(name, DetectionState.PARKED, step.state.state)
            assertEquals(name, savedAt, step.state.stateEnteredAtMillis)
            assertNull("$name: the session and its vehicle activity are gone", step.state.session)
            assertTrue(
                "$name: a save neither creates, retires nor ends anything",
                step.effects.all { it is DetectionEffect.PersistCheckpoint },
            )
        }
    }

    @Test
    fun `CANDIDATE_PENDING to PARKED on a hand save retires the prompt without rejecting it`() {
        // Arrange
        val pending = pendingCandidate()
        val candidateId = checkNotNull(pending.candidate).id

        // Act
        val step = engine.handle(pending, DetectionEvent.UserSavedParking(T0 + 40_000))

        // Assert — §11c: retired as a rejection would be, but it is not one; the effect
        // carries no answer and nothing ends.
        assertEquals(DetectionState.PARKED, step.state.state)
        assertNull(step.state.candidate)
        assertEquals(DetectionEffect.RetireCandidate(candidateId), step.effects.filterIsInstance<DetectionEffect.RetireCandidate>().single())
        assertTrue(step.effects.none { it is DetectionEffect.EndActiveParking || it is DetectionEffect.MarkParkingActive })
    }

    @Test
    fun `the drive a hand save interrupted produces no candidate when it ends`() {
        // Arrange — saved from the driver's seat, then the phone reports the walk away.
        val saved = driving().handle(DetectionEvent.UserSavedParking(T0 + 1_000))

        // Act
        val step = engine.handle(
            saved.handle(DetectionEvent.VehicleExit(T0 + 2_000)),
            DetectionEvent.WalkingEnter(T0 + 30_000),
        )

        // Assert
        assertEquals(DetectionState.PARKED, step.state.state)
        assertEquals(0, step.state.candidatesCreated)
    }

    @Test
    fun `driving away from a hand-saved parking ends it where the car pulled away`() {
        // Arrange — the field report behind §11c: saved by hand, never answered a prompt.
        val parked = idle().handle(DetectionEvent.UserSavedParking(T0))

        // Act — get in, clear §11's two bars, then §7's guard in full.
        var state = parked.handle(DetectionEvent.VehicleEnter(T1))
        var departureEnteredAt: Long? = null
        val effects = mutableListOf<DetectionEffect>()
        for (leg in 1..8) {
            val step = engine.handle(
                state,
                DetectionEvent.Location(fix(T1 + leg * 30_000L, accuracyM = 5f, speedMps = 15f, north = leg * 250.0)),
            )
            state = step.state
            effects += step.effects
            if (state.state == DetectionState.DEPARTURE_CANDIDATE && departureEnteredAt == null) {
                departureEnteredAt = state.stateEnteredAtMillis
            }
        }

        // Assert
        assertEquals(DetectionState.DRIVING, state.state)
        val ended = effects.filterIsInstance<DetectionEffect.EndActiveParking>().single()
        assertEquals(checkNotNull(departureEnteredAt) { "never reached DEPARTURE_CANDIDATE" }, ended.endedAtMillis)
    }

    /**
     * iPhone, 2026-09-26: a parking auto-ended at 20:35, the drive that followed parked at
     * 21:01, and no candidate was raised — iOS moved the departure to DRIVING without
     * marking the drive confirmed. Held here too so the platforms cannot drift on it.
     */
    @Test
    fun `the drive a departure opened can end in the next parking`() {
        // Arrange — parked, then driven away from (the test above).
        var state = idle().handle(DetectionEvent.UserSavedParking(T0)).handle(DetectionEvent.VehicleEnter(T1))
        for (leg in 1..8) {
            state = state.handle(
                DetectionEvent.Location(fix(T1 + leg * 30_000L, accuracyM = 5f, speedMps = 15f, north = leg * 250.0)),
            )
        }
        assertEquals(DetectionState.DRIVING, state.state)

        // Act — the car stops, the driver gets out and walks away.
        val arrive = T1 + 8 * 30_000L
        state = state.handle(DetectionEvent.Location(fix(arrive + 20_000, accuracyM = 5f, speedMps = 0f, north = 2_000.0)))
        state = state.handle(DetectionEvent.VehicleExit(arrive + 40_000))
        val step = engine.handle(state, DetectionEvent.WalkingEnter(arrive + 60_000))

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, step.state.state)
        createCandidate(step.effects)
    }

    private fun createCandidate(effects: List<DetectionEffect>): DetectionEffect.CreateCandidate =
        effects.filterIsInstance<DetectionEffect.CreateCandidate>().single()

    private fun idle() = DetectionEngineState.startingIn(DetectionState.IDLE, T0)

    private fun driving() = DetectionEngineState.startingIn(DetectionState.DRIVING, T0)

    /** Seconds after [T0], the unit the iOS twins are written in. */
    private fun at(seconds: Long): Long = T0 + seconds * 1_000L

    /** iOS `drivingEngine()`: `vehicle_enter` at T0, promoted by the tick at 90 s. */
    private fun drivenFromEnter() = idle()
        .handle(DetectionEvent.VehicleEnter(T0))
        .handle(DetectionEvent.TimerTick(T0 + SUSTAIN))

    /** iOS `idleTransitionEngine()`: moved at 100 s, still since — `movementIdle` at 280 s. */
    private fun idleTransition() = drivenFromEnter()
        .handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 9f)))
        .handle(DetectionEvent.TimerTick(at(280)))

    private fun pendingCandidate() = driving()
        .handle(DetectionEvent.VehicleExit(T0 + 1_000))
        .handle(DetectionEvent.WalkingEnter(T0 + 30_000))

    /**
     * Exactly on §11's two bars — 90 s of vehicle activity and 600 m — and deliberately
     * short of §7's guard, which needs 120 s or 800 m. That gap is what makes
     * `DEPARTURE_CANDIDATE` a state rather than a pass-through.
     */
    private fun departureCandidate(): DetectionEngineState =
        pendingCandidate()
            .handle(DetectionEvent.UserConfirmedParking(T0 + 40_000))
            .handle(DetectionEvent.VehicleEnter(T1))
            .handle(DetectionEvent.Location(fix(T1 + 50_000, accuracyM = 5f, speedMps = 15f)))
            .handle(DetectionEvent.Location(fix(T1 + SUSTAIN, accuracyM = 5f, speedMps = 15f, north = 600.0)))

    private fun DetectionEngineState.handle(event: DetectionEvent): DetectionEngineState =
        engine.handle(this, event).state

    /** A fix [north] metres north of the fixture origin. Coordinates never leave this file. */
    private fun fix(atMillis: Long, accuracyM: Float, speedMps: Float? = null, north: Double = 0.0) = LocationSample(
        atMillis = atMillis,
        latitude = ORIGIN_LATITUDE + north / METERS_PER_DEGREE_LATITUDE,
        longitude = ORIGIN_LONGITUDE,
        horizontalAccuracyM = accuracyM,
        speedMps = speedMps,
    )

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val T1 = T0 + 3_600_000L
        const val SUSTAIN = ParkingDetectionEngine.MINIMUM_VEHICLE_DURATION_MILLIS

        const val ORIGIN_LATITUDE = 37.5
        const val ORIGIN_LONGITUDE = 127.0
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    }
    /**
     * §3a "Leaving a pending candidate behind". Without this row the engine sat in
     * CANDIDATE_PENDING for up to forty-five minutes with detection dead, which is the
     * whole cost of ignoring one prompt.
     */
    @Test
    fun `driving again while a prompt is unanswered starts a new session`() {
        val pending = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 30_000))
        assertEquals(DetectionState.CANDIDATE_PENDING, pending.state)

        val state = pending.handle(DetectionEvent.VehicleEnter(T0 + 900_000))

        assertEquals(DetectionState.DRIVING_CANDIDATE, state.state)
    }

    /**
     * The candidate is not retired at vehicle_enter: that signal is noisy, and §10a
     * supersedes only when the new session produces a candidate of its own.
     */
    @Test
    fun `the unanswered candidate survives the new session`() {
        val pending = driving()
            .handle(DetectionEvent.VehicleExit(T0 + 1_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 30_000))
        val candidateBefore = pending.candidate
        assertNotNull(candidateBefore)

        val state = pending.handle(DetectionEvent.VehicleEnter(T0 + 900_000))

        assertEquals(candidateBefore, state.candidate)
    }

    /**
     * Contract §8: "Engines therefore emit a supersession as withdraw-then-create, adjacent".
     * iOS appends `.withdrawCandidate(old)` directly before `.createCandidate(new)`; the storm
     * counter tells a supersession from a self-withdrawal by exactly that adjacency.
     */
    @Test
    fun `a candidate superseded by the next journey is retired directly before the new one is created`() {
        // Arrange — the first journey's candidate is left unanswered, and a second journey
        // sustains its vehicle activity.
        val pending = pendingCandidate()
        val first = checkNotNull(pending.candidate)
        val secondDrive = pending
            .handle(DetectionEvent.VehicleEnter(T0 + 900_000))
            .handle(DetectionEvent.TimerTick(T0 + 900_000 + SUSTAIN))
            .handle(DetectionEvent.VehicleExit(T0 + 1_100_000))

        // Act
        val step = engine.handle(secondDrive, DetectionEvent.WalkingEnter(T0 + 1_110_000))

        // Assert
        val retireAt = step.effects.indexOf(DetectionEffect.RetireCandidate(first.id))
        val created = createCandidate(step.effects)
        assertTrue("the superseded candidate is withdrawn", retireAt >= 0)
        assertEquals("withdraw-then-create, adjacent", created, step.effects[retireAt + 1])
        assertEquals(1, step.effects.count { it is DetectionEffect.RetireCandidate })
        assertEquals(created.candidateId, step.state.candidate?.id)
    }

    // ── §3a "A stop-only candidate can still be a long light" (DECIDED 2026-09-27) ──────
    // Twins of iOS `ParkingTransitionEvidenceTests` "A silent stop that moves on is a long
    // light": the same events, the same outcomes, so the two engines cannot disagree on
    // `long_stop_in_traffic` again.

    /**
     * iOS `stoppedInTrafficEngine()`: moved at 100 s, then stood still reporting a stop until
     * `movementIdleWindow` opened the transition at 280 s — where that same stopped fix
     * confirms it. Nothing but absence says this drive ended: a jam, or a parking.
     */
    private fun stoppedInTraffic(): Pair<DetectionEngineState, CandidateSnapshot> {
        val state = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 9f)))
            .handle(DetectionEvent.Location(fix(at(200), accuracyM = 8f, speedMps = 0f)))
            .handle(DetectionEvent.Location(fix(at(280), accuracyM = 8f, speedMps = 0f)))
        return state to checkNotNull(state.candidate) { "the stop at 280 s must confirm the transition" }
    }

    private fun movingFix(seconds: Long, north: Double, speedMps: Float = 8f) =
        DetectionEvent.Location(fix(at(seconds), accuracyM = 8f, speedMps = speedMps, north = north))

    @Test
    fun `a candidate confirmed only by a stop keeps its resume window open`() {
        // Arrange / Act
        val (state, candidate) = stoppedInTraffic()

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertEquals(ConfidenceBucket.LOW, candidate.confidence)
        val window = checkNotNull(state.stopOnlyResumeWindow) { "a stop-only candidate opens the window" }
        assertEquals("the drive ended at the transition's entry", at(280), window.driveEndedAtMillis)
        assertEquals(at(280) + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS, window.deadlineMillis)
    }

    @Test
    fun `a reported moving fix after a stop-only candidate retires it and resumes the same drive`() {
        // Arrange
        val (pending, candidate) = stoppedInTraffic()

        // Act — the queue moves. One fix is a spike (§7: one event alone never confirms);
        // the second is the car.
        val firstMoving = pending.handle(movingFix(340, north = 150.0))
        val step = engine.handle(firstMoving, movingFix(355, north = 300.0, speedMps = 9f))

        // Assert — withdrawn, and the trip continues as the same travel session.
        assertEquals(DetectionState.CANDIDATE_PENDING, firstMoving.state)
        assertEquals(DetectionState.DRIVING, step.state.state)
        assertEquals(
            listOf(DetectionEffect.RetireCandidate(candidate.id)),
            step.effects.filterIsInstance<DetectionEffect.RetireCandidate>(),
        )
        assertTrue("one checkpoint, straight to DRIVING", step.effects.last() is DetectionEffect.PersistCheckpoint)
        assertNull(step.state.candidate)
        assertNull(step.state.stopOnlyResumeWindow)
        val session = checkNotNull(step.state.session)
        assertEquals("the trip keeps its own start", T0, session.evidence.vehicleFirstSeenAtMillis)
        assertNull("the stop was a long light, not how the drive ended", session.stop)
        assertFalse("§12's allowance is restored", session.candidateProduced)
        assertEquals("the moving fix anchors the idle clock itself", at(355), session.lastMovementEvidenceAtMillis)
        assertEquals("contract §8: a candidate was created", 1, step.state.candidatesCreated)
    }

    @Test
    fun `vehicle enter after a stop-only candidate resumes the same drive, not a new journey`() {
        // Arrange
        val (pending, candidate) = stoppedInTraffic()

        // Act
        val step = engine.handle(pending, DetectionEvent.VehicleEnter(at(330)))

        // Assert
        assertEquals(DetectionState.DRIVING, step.state.state)
        assertTrue(DetectionEffect.RetireCandidate(candidate.id) in step.effects)
        val session = checkNotNull(step.state.session)
        assertEquals(T0, session.evidence.vehicleFirstSeenAtMillis)
        assertEquals(
            "§3a: a resume on vehicle evidence re-anchors the idle clock at the resume",
            at(330),
            session.lastMovementEvidenceAtMillis,
        )
    }

    @Test
    fun `the resume window closes transitionWindow after the drive ended and the candidate stands`() {
        // Arrange
        val (pending, candidate) = stoppedInTraffic()
        val deadline = 280 + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS / 1_000L
        val oneMoving = pending
            .handle(movingFix(deadline - 30, north = 150.0))
            .handle(DetectionEvent.TimerTick(at(deadline - 1)))
        assertNotNull("still open a second before the deadline", oneMoving.stopOnlyResumeWindow)

        // Act — the second moving fix arrives after the window closed.
        val closed = oneMoving.handle(DetectionEvent.TimerTick(at(deadline)))
        val late = engine.handle(closed, movingFix(deadline + 20, north = 300.0))

        // Assert
        assertNull(closed.stopOnlyResumeWindow)
        assertEquals(DetectionState.CANDIDATE_PENDING, late.state.state)
        assertEquals(candidate, late.state.candidate)
        assertTrue(late.effects.isEmpty())
    }

    @Test
    fun `a speedless fix that clears the distance fallback does not retire a candidate`() {
        // Arrange — the fallback reads the jitter around a parked car as travel (s03's walk
        // away clears it at 2.01 m/s), so only a Doppler speed may take a candidate back.
        val (pending, candidate) = stoppedInTraffic()

        // Act — 500 m and then 1000 m from the last stopped fix, no speed.
        val state = pending
            .handle(DetectionEvent.Location(fix(at(360), accuracyM = 10f, north = 500.0)))
            .handle(DetectionEvent.Location(fix(at(440), accuracyM = 10f, north = 1_000.0)))

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertEquals(candidate, state.candidate)
    }

    @Test
    fun `the walk away from the car does not move the spot a later candidate would inherit`() {
        // Arrange
        val (pending, _) = stoppedInTraffic()
        val spot = pending.lastReliableLocation

        // Act — a clean fix 40 m away, at walking pace.
        val step = engine.handle(pending, DetectionEvent.Location(fix(at(320), accuracyM = 5f, speedMps = 1.3f, north = 40.0)))

        // Assert
        assertTrue(step.effects.isEmpty())
        assertEquals(spot, step.state.lastReliableLocation)
    }

    @Test
    fun `a stop-only candidate's drive keeps recording fixes inside the window`() {
        // Arrange — §3a rule 1: anchors and distance keep running, so a resume continues the
        // drive where the car actually is.
        val (pending, _) = stoppedInTraffic()
        val distanceAtStop = checkNotNull(pending.session).evidence.travelDistanceMeters

        // Act
        val state = pending.handle(movingFix(340, north = 150.0))

        // Assert
        assertTrue(checkNotNull(state.session).evidence.travelDistanceMeters > distanceAtStop)
    }

    @Test
    fun `a walk-confirmed candidate is not retired by movement`() {
        // Arrange
        val pending = idleTransition().handle(DetectionEvent.WalkingEnter(at(300)))
        assertEquals(DetectionState.CANDIDATE_PENDING, pending.state)

        // Act
        val state = pending
            .handle(movingFix(330, north = 300.0))
            .handle(movingFix(345, north = 450.0, speedMps = 9f))

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertNull(state.stopOnlyResumeWindow)
    }

    @Test
    fun `a candidate after an explicit exit is not retired by movement`() {
        // Arrange
        val pending = drivenFromEnter()
            .handle(DetectionEvent.Location(fix(at(100), accuracyM = 8f, speedMps = 9f)))
            .handle(DetectionEvent.VehicleExit(at(200)))
            .handle(DetectionEvent.Location(fix(at(230), accuracyM = 8f, speedMps = 0.3f)))
        assertEquals(DetectionState.CANDIDATE_PENDING, pending.state)

        // Act
        val state = pending
            .handle(movingFix(260, north = 300.0))
            .handle(movingFix(275, north = 450.0, speedMps = 9f))

        // Assert
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
        assertNull(state.stopOnlyResumeWindow)
    }

    @Test
    fun `a walk after a stop-only candidate closes its resume window`() {
        // Arrange — the walk is the strongest evidence the person left the car; vehicle
        // evidence after it is a new journey that leaves the candidate answerable.
        val (pending, candidate) = stoppedInTraffic()

        // Act
        val walking = pending.handle(DetectionEvent.WalkingEnter(at(300)))
        val boarding = engine.handle(walking, DetectionEvent.VehicleEnter(at(330)))

        // Assert
        assertNull(walking.stopOnlyResumeWindow)
        assertTrue(boarding.effects.none { it is DetectionEffect.RetireCandidate })
        assertEquals(DetectionState.DRIVING_CANDIDATE, boarding.state.state)
        assertEquals(candidate, boarding.state.candidate)
    }

    @Test
    fun `a vehicle exit after a stop-only candidate closes its resume window`() {
        // Arrange
        val (pending, _) = stoppedInTraffic()

        // Act
        val state = pending
            .handle(DetectionEvent.VehicleExit(at(300)))
            .handle(movingFix(320, north = 150.0))
            .handle(movingFix(335, north = 300.0, speedMps = 9f))

        // Assert
        assertNull(state.stopOnlyResumeWindow)
        assertEquals(DetectionState.CANDIDATE_PENDING, state.state)
    }

    @Test
    fun `a stationary enter after a stop-only candidate leaves the resume window open`() {
        // Arrange — the Transition API can report STILL inside a car at a light.
        val (pending, candidate) = stoppedInTraffic()

        // Act
        val still = pending.handle(DetectionEvent.StationaryEnter(at(300)))
        val step = engine.handle(still.handle(movingFix(320, north = 150.0)), movingFix(335, north = 300.0, speedMps = 9f))

        // Assert
        assertNotNull(still.stopOnlyResumeWindow)
        assertTrue(DetectionEffect.RetireCandidate(candidate.id) in step.effects)
        assertEquals(DetectionState.DRIVING, step.state.state)
    }

    @Test
    fun `a drive resumed from a stop-only candidate can still produce the real parking`() {
        // Arrange
        val resumed = stoppedInTraffic().first
            .handle(movingFix(340, north = 150.0))
            .handle(movingFix(355, north = 300.0, speedMps = 9f))

        // Act
        val step = engine.handle(resumed.handle(DetectionEvent.VehicleExit(at(500))), DetectionEvent.WalkingEnter(at(510)))

        // Assert — the same trip: its duration runs from T0.
        assertEquals(DetectionState.CANDIDATE_PENDING, step.state.state)
        assertEquals(500_000L, createCandidate(step.effects).vehicleSessionDurationMillis)
        assertEquals(2, step.state.candidatesCreated)
    }

    @Test
    fun `answering a stop-only candidate closes its window`() {
        // Arrange
        val (pending, _) = stoppedInTraffic()

        // Act
        val confirmed = pending.handle(DetectionEvent.UserConfirmedParking(at(300)))
        val rejected = pending.handle(DetectionEvent.UserRejectedParking(at(300)))
        val saved = pending.handle(DetectionEvent.UserSavedParking(at(300)))

        // Assert
        assertNull(confirmed.stopOnlyResumeWindow)
        assertNull(rejected.stopOnlyResumeWindow)
        assertNull(saved.stopOnlyResumeWindow)
    }
}

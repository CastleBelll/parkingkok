package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.DrivingConfirmationGuard
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
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
    fun `reason codes accumulate as evidence arrives`() {
        val state = idle()
            .handle(DetectionEvent.VehicleEnter(T0))
            .handle(DetectionEvent.Location(fix(T0 + SUSTAIN, accuracyM = 8f, speedMps = 14f)))
            .handle(DetectionEvent.LocationQualityDegraded(T0 + 200_000))
            .handle(DetectionEvent.VehicleExit(T0 + 300_000))
            .handle(DetectionEvent.WalkingEnter(T0 + 320_000))

        val reasons = checkNotNull(state.candidate).reasons
        assertEquals(
            "arrival order, not a set rebuilt at the end",
            listOf(
                EvidenceReasonCode.RECENT_VEHICLE_ACTIVITY,
                EvidenceReasonCode.RELIABLE_LOCATION_CAPTURED,
                EvidenceReasonCode.LOCATION_QUALITY_DEGRADED,
                EvidenceReasonCode.VEHICLE_DURATION_MET,
                EvidenceReasonCode.VEHICLE_EXIT_DETECTED,
                EvidenceReasonCode.WALKING_AFTER_VEHICLE,
            ),
            reasons,
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

    // ── helpers ─────────────────────────────────────────────────────────────────────

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

    private fun createCandidate(effects: List<DetectionEffect>): DetectionEffect.CreateCandidate =
        effects.filterIsInstance<DetectionEffect.CreateCandidate>().single()

    private fun idle() = DetectionEngineState.startingIn(DetectionState.IDLE, T0)

    private fun driving() = DetectionEngineState.startingIn(DetectionState.DRIVING, T0)

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

}

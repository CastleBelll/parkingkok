package com.parkingkok.app.domain.detection

import com.parkingkok.app.domain.location.LocationSample
import com.parkingkok.app.domain.parking.ConfidenceBucket
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

        // The same tick a full window later promotes and then, in `DRIVING`, notices it has
        // had no movement evidence at all — which is `PARKING_TRANSITION`, a silent state.
        // What matters for §3a is that the session was not discarded.
        val atWindow = candidate
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.DRIVING_CANDIDATE_WINDOW_MILLIS))

        assertEquals(DetectionState.PARKING_TRANSITION, atWindow.state)
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
        val state = driving()
            .handle(DetectionEvent.TimerTick(T0 + ParkingDetectionEngine.MOVEMENT_IDLE_WINDOW_MILLIS))

        assertEquals(DetectionState.PARKING_TRANSITION, state.state)
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

        val sustained = connected.handle(DetectionEvent.StationaryExit(T0 + SUSTAIN))

        assertEquals(DetectionState.DRIVING, sustained.state)
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

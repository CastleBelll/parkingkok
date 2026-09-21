package com.parkingkok.app.domain.detection

import com.parkingkok.app.domain.location.DrivingConfirmationGuard
import com.parkingkok.app.domain.location.DrivingSessionEvidence
import com.parkingkok.app.domain.location.LocationSample
import com.parkingkok.app.domain.location.MovementEvidencePolicy
import com.parkingkok.app.domain.location.ReliableLocationDecision
import com.parkingkok.app.domain.location.ReliableLocationSelector
import com.parkingkok.app.domain.parking.ConfidenceBucket
import com.parkingkok.app.domain.trace.LocationQualityBucket
import kotlinx.serialization.Serializable

/**
 * What the engine accumulated about one travel session.
 *
 * A *travel session* is the unit §12 counts candidates in: it opens on the first vehicle
 * evidence and closes when the state machine returns to `IDLE` or `PARKED`. Everything the
 * §8 score reads lives here, so scoring a candidate never has to look at the device's
 * current situation — which is what §3a forbids when it says reason codes are never
 * recomputed at the end from the final state.
 */
@Serializable
data class TravelSession(
    /**
     * When the current, uninterrupted stretch of vehicle activity began.
     *
     * Not the same as when the session opened: §3a's promotion bar asks whether vehicle
     * activity has been *sustained*, so a session that dipped out of the vehicle and back
     * in has to earn the 90 s again from the later entry.
     */
    val vehicleActivityStartedAtMillis: Long,
    /** The §7 evidence object, reused unchanged — see [DrivingSessionEvidence]. */
    val evidence: DrivingSessionEvidence,
    /** §4 codes in arrival order. Appended to, never rebuilt. */
    val reasons: List<EvidenceReasonCode> = emptyList(),
    /** When vehicle activity ended, by exit or by car-link disconnect. */
    val vehicleEndedAtMillis: Long? = null,
    /** Last moment a fix cleared §7's movement bar. Feeds `movementIdleWindow`. */
    val lastMovementEvidenceAtMillis: Long,
    /**
     * §3a "DECIDED 2026-09-20: a connected car link suppresses every timeout row".
     *
     * True between a `projection_connected`/`bluetooth_car_connected` and the matching
     * disconnect. While it holds, no elapsed-time row may end or downgrade this session: a
     * phone still attached to the car's audio during a 180-second gap is at a red light, in
     * a tunnel or on a ramp, not in a car that has been left.
     *
     * **It must not outlive the link.** The engine is pure and cannot poll, so §3a makes it
     * the adapter's obligation to re-assert the real state on process start and feed a
     * disconnect when the link is gone. A latch stuck at `true` would suppress timeouts for
     * ever and kill detection outright.
     */
    val carLinkConnected: Boolean = false,
    /**
     * When motion last said this device is *in a vehicle*, or null if it has not said so.
     *
     * Separate from [vehicleActivityStartedAtMillis] because a car link opens a session
     * too, and §3a is explicit that it must not buy the promotion bar: "Connecting does
     * **not** promote straight to `DRIVING`: people sit in parked cars." Without this,
     * 90 seconds of Bluetooth in a stationary car promoted the session, and the disconnect
     * on getting back out produced a candidate for a drive that never happened. iOS has
     * always had it as `vehicleActiveSince`; this is the Android half of that pair.
     */
    val vehicleActiveSinceMillis: Long? = null,
    /**
     * §12 / §3a "One candidate per travel session".
     *
     * Cleared again when a candidate is *retired* rather than answered — §3a's reconnect
     * row withdraws the candidate, so the session is back to having produced none and the
     * real parking at the end of the trip is still allowed to be found. That is what makes
     * the fuel stop in §17 fixture #3 work rather than silently costing the user the
     * parking they actually did.
     */
    val candidateProduced: Boolean = false,
    /**
     * The §2 quality bucket of the last valid fix, so a drop to a worse one can be seen.
     *
     * Android has no adapter that emits `LocationQualityDegraded`: Fused Location reports
     * accuracies, not transitions between them, and the one place that already derives the
     * event derives it for the *trace* file. Deriving it here too, from the same fixed §2
     * edges, is what makes §8's "GPS quality degraded near end" reachable on a real device
     * rather than only in a fixture that spells the event out.
     */
    val lastQualityBucket: LocationQualityBucket? = null,
) {

    fun plusReason(code: EvidenceReasonCode): TravelSession =
        if (code in reasons) this else copy(reasons = reasons + code)

    fun plusReasons(codes: Iterable<EvidenceReasonCode>): TravelSession =
        codes.fold(this) { session, code -> session.plusReason(code) }
}

/**
 * The candidate the engine last created, in the terms the contract checks.
 *
 * Held beside the state rather than only in the candidate store so a fixture replay — and
 * the diagnostics screen — can read what the engine decided without a DataStore.
 */
@Serializable
data class CandidateSnapshot(
    val id: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val confidence: ConfidenceBucket,
    /** §5: internal. Carried for diagnostics and tuning, never across an API boundary. */
    val score: Int,
    val reasons: List<EvidenceReasonCode>,
)

/**
 * Everything [ParkingDetectionEngine] needs to keep between two events.
 *
 * Serializable in full, because §14 requires a checkpoint on every meaningful transition
 * and a process started by a broadcast has to be able to pick the trip up mid-drive.
 */
@Serializable
data class DetectionEngineState(
    val state: DetectionState = DetectionState.IDLE,
    val stateEnteredAtMillis: Long = 0L,
    val session: TravelSession? = null,
    /** §7 of the domain contract. Survives the session that captured it. */
    val lastReliableLocation: ReliableLocation? = null,
    val candidate: CandidateSnapshot? = null,
    /**
     * How many candidates this engine has created, ever.
     *
     * The parity fixtures' `candidate` field asks whether a candidate was *created* during
     * the replay, which is not the same question as whether one is still pending: §3a's
     * reconnect row retires one, and a fixture that ends after a retirement still saw a
     * candidate created.
     */
    val candidatesCreated: Int = 0,
) {

    /**
     * The §14 checkpoint view, which is the field-for-field parity structure iOS also
     * writes (docs/04_IOS_IMPLEMENTATION.md §6).
     *
     * `candidateId` is populated here and nowhere else — it is the one field that says a
     * process restarting mid-prompt is looking at the same candidate the notification
     * names.
     */
    fun toCheckpoint(previousRevision: Long = 0L): DetectionCheckpoint = DetectionCheckpoint(
        state = state,
        stateEnteredAtMillis = stateEnteredAtMillis,
        lastAutomotiveAtMillis = session?.evidence?.lastVehicleEvidenceAtMillis,
        lastReliableLocation = lastReliableLocation,
        lastLocationAtMillis = lastReliableLocation?.capturedAtMillis,
        travelDistanceEstimateMeters = session?.evidence?.travelDistanceMeters ?: 0.0,
        candidateId = candidate?.id,
        revision = previousRevision + 1,
    )

    companion object {

        /**
         * A state to start a replay — or a restored process — in.
         *
         * A fixture whose `initialState` is `DRIVING` is asserting that a vehicle session
         * already happened, so one is seeded: without it the very first `vehicle_exit`
         * would have no session to end and no evidence to score, and every `initialState:
         * DRIVING` fixture would describe a trip the engine never believed in. The seeded
         * session is deliberately the weakest one consistent with the claim — vehicle
         * activity that started exactly [ParkingDetectionEngine.MINIMUM_VEHICLE_DURATION_MILLIS]
         * ago, so the state is legitimately reachable, and nothing more.
         */
        fun startingIn(state: DetectionState, atMillis: Long): DetectionEngineState {
            val session = when (state) {
                DetectionState.IDLE, DetectionState.PARKED -> null
                else -> seededSession(atMillis)
            }
            return DetectionEngineState(
                state = state,
                stateEnteredAtMillis = atMillis,
                session = session,
            )
        }

        private fun seededSession(atMillis: Long): TravelSession {
            val vehicleStartedAt = atMillis - ParkingDetectionEngine.MINIMUM_VEHICLE_DURATION_MILLIS
            return TravelSession(
                vehicleActivityStartedAtMillis = vehicleStartedAt,
                evidence = DrivingSessionEvidence(
                    vehicleFirstSeenAtMillis = vehicleStartedAt,
                    lastVehicleEvidenceAtMillis = atMillis,
                ),
                lastMovementEvidenceAtMillis = atMillis,
            )
        }
    }
}

/**
 * What the engine wants done about the world, in the vocabulary of
 * docs/05_PARKING_DETECTION_ENGINE.md §15.
 *
 * The engine performs none of it. Effects are values so the state machine stays a pure
 * function of (state, event) and can be replayed from JSON on either platform; the SDK
 * calls live in [com.parkingkok.app.detection.ParkingDetectionRuntime].
 *
 * `startBoundedLocationCapture` / `stopLocationCapture` are **not** here on purpose:
 * [com.parkingkok.app.domain.location.LocationCaptureModePolicy] already decides the shape
 * of the Fused Location request from the same motion events, and two owners of one
 * registration is the bug that leaks a session.
 */
sealed interface DetectionEffect {

    /** §14. Written on every transition that changed something worth rehydrating. */
    data class PersistCheckpoint(val checkpoint: DetectionCheckpoint) : DetectionEffect

    /** §15 `createCandidate` + `issueCandidateNotification`, which §10a makes one decision. */
    data class CreateCandidate(
        val candidateId: String,
        val confidence: ConfidenceBucket,
        val score: Int,
        val reasons: List<EvidenceReasonCode>,
        val lastReliableLocation: ReliableLocation?,
        val vehicleSessionDurationMillis: Long,
        val travelDistanceMeters: Double,
        val walkingEvidence: Boolean,
        val gpsDegradation: Boolean,
        val optionalVehicleSignal: Boolean,
    ) : DetectionEffect

    /**
     * Take the candidate down without recording an answer.
     *
     * §3a's reconnect row and the 45-minute expiry both land here. Neither is a rejection:
     * the user said nothing, and reporting one would poison the single distribution §10a
     * calls the event that pays for the whole feature.
     */
    data class RetireCandidate(val candidateId: String) : DetectionEffect

    /** §15 `markParkingActive`. The record itself is written by the confirmation flow. */
    data class MarkParkingActive(val candidateId: String) : DetectionEffect

    /**
     * §11 departure, confirmed: close the open parking record.
     *
     * [endedAtMillis] is when the car **started moving**, not when the engine finished
     * deciding — `DrivingConfirmationGuard` needs a meaningful driving session before it
     * will say so, which is minutes of driving after the fact. Stamping "now" would put the
     * end of the parking somewhere down the road.
     *
     * Only emitted from `DEPARTURE_CANDIDATE → DRIVING`, which is §7's guard in full. §11's
     * "if uncertain → suggestion, not destructive silent end" is honoured by the state
     * *below* it: reaching `DEPARTURE_CANDIDATE` and not confirming ends nothing.
     */
    data class EndActiveParking(val endedAtMillis: Long) : DetectionEffect
}

/** One turn of the reducer. */
data class EngineStep(
    val state: DetectionEngineState,
    val effects: List<DetectionEffect> = emptyList(),
)

/**
 * The §3a transition table, as a pure reducer.
 *
 * ### What it is
 * `(state, event) -> (state, effects)` and nothing else. No clock, no store, no SDK, no
 * coroutine — every `atMillis` comes from the event. That is what lets the same five JSON
 * fixtures in `platform-tests/` drive this engine and the Swift one and be compared
 * (`ParityFixtureTest`), which is the only evidence of parity this project has.
 *
 * ### The one rule the table does not state
 * §3a mixes conditions that *become true by holding* with conditions that are *timeouts*,
 * and they fire at different moments. [DetectionEvent.TimerTick] documents which is which
 * and why the distinction is load bearing.
 *
 * ### What movement evidence is for
 * Not promotion. §3a "Movement evidence does not gate promotion" is a correction made
 * against three real drives: the textbook 14:26 trip carries zero location events, and an
 * engine that required movement to reach `DRIVING` never detects it. Movement feeds the
 * `movementIdleWindow` row and the §9 confidence bucket; it is never a condition of
 * reaching `CANDIDATE_PENDING`.
 *
 * ### The car link is optional
 * Every row of the main table stands on motion and location alone. The three car-link rows
 * add accuracy where a link exists and remove nothing where it does not — on iOS, §3a
 * says, it mostly does not.
 */
class ParkingDetectionEngine(
    /** Injected so a replay is reproducible; the runtime passes a UUID factory. */
    private val newCandidateId: () -> String,
) {

    fun handle(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        val folded = fold(state, event)
        return transition(before = state, state = folded, event = event)
    }

    // ── Evidence folding ────────────────────────────────────────────────────────────
    // Runs before the transition and independently of it: §3a says codes accumulate as
    // evidence arrives, so what a fix or a transition *means* cannot depend on which state
    // happens to be current when it lands.

    private fun fold(state: DetectionEngineState, event: DetectionEvent): DetectionEngineState {
        return when (event) {
            is DetectionEvent.VehicleEnter -> state.copy(
                session = openOrExtendSession(state.session, event.atMillis).let {
                    it.copy(vehicleActiveSinceMillis = it.vehicleActiveSinceMillis ?: event.atMillis)
                },
            )
            is DetectionEvent.VehicleExit -> state.copy(
                session = endVehicleActivity(state.session, event.atMillis)?.copy(vehicleActiveSinceMillis = null),
            )
            is DetectionEvent.WalkingEnter -> state.copy(
                session = state.session?.takeIf { it.isShortlyAfterVehicleEnd(event.atMillis) }
                    ?.plusReason(EvidenceReasonCode.WALKING_AFTER_VEHICLE)
                    ?: state.session,
            )

            is DetectionEvent.StationaryEnter -> state.copy(
                session = state.session?.takeIf { it.isShortlyAfterVehicleEnd(event.atMillis) }
                    ?.plusReason(EvidenceReasonCode.STATIONARY_AFTER_VEHICLE)
                    ?: state.session,
            )

            is DetectionEvent.StationaryExit -> state
            is DetectionEvent.Location -> foldLocation(state, event.sample)
            is DetectionEvent.LocationQualityDegraded -> state.copy(
                session = state.session?.plusReason(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED),
            )

            // §3a: opens a session, but does **not** arm the promotion bar. People sit in
            // parked cars, and 90 s of Bluetooth in a stationary one used to promote the
            // session here — so getting back out produced a candidate for a drive that
            // never happened.
            is DetectionEvent.CarLinkConnected -> state.copy(
                session = openOrExtendSession(state.session, event.atMillis)
                    .copy(carLinkConnected = true),
            )

            is DetectionEvent.CarLinkDisconnected -> state.copy(
                session = endVehicleActivity(state.session, event.atMillis)
                    ?.plusReason(EvidenceReasonCode.CAR_PROJECTION_DISCONNECTED)
                    ?.copy(carLinkConnected = false, vehicleActiveSinceMillis = null),
            )

            is DetectionEvent.TimerTick,
            is DetectionEvent.UserConfirmedParking,
            is DetectionEvent.UserRejectedParking,
            -> state
        }.withGuardReasons(event.atMillis)
    }

    /**
     * Vehicle evidence arrived.
     *
     * A session already in vehicle activity keeps its [TravelSession.vehicleActivityStartedAtMillis]
     * — a second `vehicle_enter` mid-drive is the OS repeating itself, not the driver
     * getting in again, and restarting the 90 s clock on it would postpone every promotion
     * indefinitely on a chatty device.
     */
    private fun openOrExtendSession(session: TravelSession?, atMillis: Long): TravelSession {
        if (session == null) {
            return TravelSession(
                vehicleActivityStartedAtMillis = atMillis,
                evidence = DrivingSessionEvidence(
                    vehicleFirstSeenAtMillis = atMillis,
                    lastVehicleEvidenceAtMillis = atMillis,
                ),
                lastMovementEvidenceAtMillis = atMillis,
            )
        }
        return session.copy(
            vehicleActivityStartedAtMillis =
                if (session.vehicleEndedAtMillis == null) session.vehicleActivityStartedAtMillis else atMillis,
            vehicleEndedAtMillis = null,
            evidence = session.evidence.copy(
                lastVehicleEvidenceAtMillis = maxOf(session.evidence.lastVehicleEvidenceAtMillis, atMillis),
            ),
        )
    }

    /**
     * Vehicle activity ended — by `vehicle_exit` or by the car link dropping.
     *
     * An exit is still vehicle *evidence*: §7's "recent vehicle evidence" clause is what
     * says a parking guess is about a drive that just happened, and dropping the timestamp
     * on the way out would make the candidate look stale at the moment it is created.
     */
    private fun endVehicleActivity(session: TravelSession?, atMillis: Long): TravelSession? {
        if (session == null) return null
        return session.copy(
            vehicleEndedAtMillis = atMillis,
            evidence = session.evidence.copy(
                lastVehicleEvidenceAtMillis = maxOf(session.evidence.lastVehicleEvidenceAtMillis, atMillis),
            ),
        ).plusReason(EvidenceReasonCode.VEHICLE_EXIT_DETECTED)
    }

    private fun foldLocation(state: DetectionEngineState, sample: LocationSample): DetectionEngineState {
        // §6 / §5, reused verbatim: the fixture replay feeds each fix at its own timestamp,
        // so `nowMillis` is the fix's own time and the freshness guards behave exactly as
        // they do for a live single-fix delivery.
        val decision = ReliableLocationSelector.select(
            current = state.lastReliableLocation,
            sample = sample,
            nowMillis = sample.atMillis,
        )
        val reliable = (decision as? ReliableLocationDecision.Accepted)?.location

        val session = state.session ?: return state.copy(
            lastReliableLocation = reliable ?: state.lastReliableLocation,
        )

        val before = session.evidence.movement.movingSampleCount
        val evidence = session.evidence.recordingFix(sample)
        val moved = evidence.movement.movingSampleCount > before

        var updated = session.copy(
            evidence = evidence,
            lastMovementEvidenceAtMillis =
                if (moved) sample.atMillis else session.lastMovementEvidenceAtMillis,
        )
        if (reliable != null) updated = updated.plusReason(EvidenceReasonCode.RELIABLE_LOCATION_CAPTURED)
        updated = updated.recordingQuality(LocationQualityBucket.of(sample.horizontalAccuracyM))
        // §8 "location movement stopped". Only a fix that actually reported a speed can say
        // this: underground there is no Doppler speed at all (§7), and reading silence as
        // stillness would hand every underground drive a weight it did not earn.
        val speed = sample.speedMps
        if (sample.quality.isValid && speed != null && speed < DrivingConfirmationGuard.MOVING_SPEED_THRESHOLD_MPS) {
            updated = updated.plusReason(EvidenceReasonCode.LOCATION_STOPPED)
        }
        return state.copy(session = updated, lastReliableLocation = reliable ?: state.lastReliableLocation)
    }

    /**
     * §8 "GPS quality degraded near end", from the §2 buckets.
     *
     * A drop only — §2 names the event for the direction it reports, so a recovery says
     * nothing. A fix with no bucket at all is an invalid accuracy, which §5 calls not a fix;
     * it leaves the previous bucket standing rather than reading as a degradation.
     */
    private fun TravelSession.recordingQuality(bucket: LocationQualityBucket?): TravelSession {
        if (bucket == null) return this
        val previous = lastQualityBucket ?: return copy(lastQualityBucket = bucket)
        val degraded = if (bucket.isWorseThan(previous)) {
            plusReason(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED)
        } else {
            this
        }
        return degraded.copy(lastQualityBucket = bucket)
    }

    /**
     * Folds in whatever the §7 guard can say at this instant.
     *
     * The guard is reused rather than reimplemented, and only for its **reason codes** —
     * `DrivingConfirmation.confirmed` deliberately never reaches a transition, because it
     * requires movement evidence and §3a forbids movement from gating promotion.
     */
    private fun DetectionEngineState.withGuardReasons(atMillis: Long): DetectionEngineState {
        val session = session ?: return this
        val confirmation = DrivingConfirmationGuard.evaluate(session.evidence, atMillis)
        return copy(session = session.plusReasons(confirmation.reasonCodes.map(EvidenceReasonCode::of)))
    }

    // ── §3a transitions ─────────────────────────────────────────────────────────────

    private fun transition(
        /** Pre-fold, so a rule can ask what *this* event changed rather than what is true now. */
        before: DetectionEngineState,
        state: DetectionEngineState,
        event: DetectionEvent,
    ): EngineStep = when (state.state) {
        DetectionState.IDLE -> fromIdle(state, event)
        DetectionState.DRIVING_CANDIDATE -> fromDrivingCandidate(before, state, event)
        DetectionState.DRIVING -> fromDriving(state, event)
        DetectionState.PARKING_TRANSITION -> fromParkingTransition(before, state, event)
        DetectionState.CANDIDATE_PENDING -> fromCandidatePending(state, event)
        DetectionState.PARKED -> fromParked(state, event)
        DetectionState.DEPARTURE_CANDIDATE -> fromDepartureCandidate(state, event)
    }

    private fun fromIdle(state: DetectionEngineState, event: DetectionEvent): EngineStep = when (event) {
        // §3a row 1, and the car-link table's row 1. Connecting is vehicle evidence; it is
        // not yet a drive, which is why both land in the same place.
        is DetectionEvent.VehicleEnter,
        is DetectionEvent.CarLinkConnected,
        -> state.moveTo(DetectionState.DRIVING_CANDIDATE, event.atMillis)

        else -> EngineStep(state)
    }

    private fun fromDrivingCandidate(
        before: DetectionEngineState,
        state: DetectionEngineState,
        event: DetectionEvent,
    ): EngineStep {
        // Promotion is asked of the session as it stood **before** this event, and it is
        // asked first. Both halves matter, and the textbook drive in §3a's "Movement
        // evidence does not gate promotion" needs both: `vehicle_enter`, then nothing at
        // all for seven minutes, then `vehicle_exit`. The 90 s was earned four minutes
        // before that exit arrived, so an exit — which is also what tells the fold the
        // vehicle activity is over — must not be allowed to erase a promotion it was far
        // too late to prevent. The same is true of the candidate-window expiry, which is
        // why it sits below this and why `subway_commute_underground` survives a 303 s
        // silence.
        if (before.session?.hasSustainedVehicleActivity(event.atMillis) == true) {
            val promoted = state.copy(state = DetectionState.DRIVING, stateEnteredAtMillis = event.atMillis)
            // The same event is now read in `DRIVING`, because it may say something there
            // too: the exit that promoted the session also ends it.
            val next = fromDriving(promoted, event)
            return if (next.effects.isEmpty()) EngineStep(promoted).withCheckpoint() else next
        }

        val session = state.session ?: return state.moveTo(DetectionState.IDLE, event.atMillis)
        return when {
            // A disconnect here is the driver getting in and changing their mind, which
            // §3a says must produce nothing. Same outcome as `vehicle_exit`.
            event is DetectionEvent.VehicleExit || event is DetectionEvent.CarLinkDisconnected ->
                state.endSession(event.atMillis)

            // The sustain reached exactly on this event — a `vehicle_enter` repeated after
            // 90 s, or a tick.
            session.hasSustainedVehicleActivity(event.atMillis) ->
                state.moveTo(DetectionState.DRIVING, event.atMillis)

            // Not gated on the link: a link connected with no drive is someone sitting in
            // a parked car with the radio on, which is exactly what this row is for.
            event is DetectionEvent.TimerTick &&
                event.atMillis - state.stateEnteredAtMillis >= DRIVING_CANDIDATE_WINDOW_MILLIS ->
                state.endSession(event.atMillis)

            else -> EngineStep(state)
        }
    }

    private fun fromDriving(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        val session = state.session ?: return state.moveTo(DetectionState.IDLE, event.atMillis)
        return when {
            // The car-link table: a disconnect skips `PARKING_TRANSITION` outright. Waiting
            // for a walk would lose the underground car park §3a was corrected for, and the
            // link has already said the engine stopped and the phone left the car.
            event is DetectionEvent.CarLinkDisconnected -> state.openCandidateOrEndSession(event.atMillis)

            event is DetectionEvent.VehicleExit -> state.moveTo(DetectionState.PARKING_TRANSITION, event.atMillis)

            // §3a: suppressed while the phone is still attached to the car, and this is
            // the only row that is. Without it the row fires on every underground drive the
            // moment anything ticks, because `lastMovementEvidenceAtMillis` only advances on
            // a location fix and there are none down there — "no sky" would read as "not
            // moving".
            event is DetectionEvent.TimerTick &&
                !session.carLinkConnected &&
                event.atMillis - session.lastMovementEvidenceAtMillis >= MOVEMENT_IDLE_WINDOW_MILLIS ->
                state.moveTo(DetectionState.PARKING_TRANSITION, event.atMillis)

            else -> EngineStep(state)
        }
    }

    private fun fromParkingTransition(
        before: DetectionEngineState,
        state: DetectionEngineState,
        event: DetectionEvent,
    ): EngineStep {
        val session = state.session ?: return state.moveTo(DetectionState.IDLE, event.atMillis)
        return when {
            // §3a's confirming signals, plus the link disconnect, which is the strongest of
            // them: it is an event rather than an inference from absence.
            event is DetectionEvent.WalkingEnter ||
                event is DetectionEvent.StationaryEnter ||
                event is DetectionEvent.CarLinkDisconnected ->
                state.openCandidateOrEndSession(event.atMillis)

            // "location stop" — a fix that reports the device is no longer moving is the
            // third confirming signal §3a names.
            event is DetectionEvent.Location && event.sample.reportsStopped() ->
                state.openCandidateOrEndSession(event.atMillis)

            // "movement evidence returns before transitionWindow elapses": the red light.
            // Entering this state is silent, so leaving it again costs the user nothing.
            // Asked as "did *this* fix clear §7's movement bar", not "is there any movement
            // evidence", which would be true of every fix earlier in the drive.
            event is DetectionEvent.Location &&
                session.lastMovementEvidenceAtMillis != before.session?.lastMovementEvidenceAtMillis ->
                state.moveTo(DetectionState.DRIVING, event.atMillis)

            // Vehicle activity resumed outright — the same return, on a motion event.
            event is DetectionEvent.VehicleEnter || event is DetectionEvent.CarLinkConnected ->
                state.moveTo(DetectionState.DRIVING, event.atMillis)

            event is DetectionEvent.TimerTick &&
                event.atMillis - state.stateEnteredAtMillis >= TRANSITION_WINDOW_MILLIS ->
                state.endSession(event.atMillis)

            else -> EngineStep(state)
        }
    }

    private fun fromCandidatePending(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        val candidate = state.candidate ?: return state.moveTo(DetectionState.IDLE, event.atMillis)
        return when {
            event is DetectionEvent.UserConfirmedParking -> EngineStep(
                state.copy(state = DetectionState.PARKED, stateEnteredAtMillis = event.atMillis, session = null),
                listOf(DetectionEffect.MarkParkingActive(candidate.id)),
            ).withCheckpoint()

            event is DetectionEvent.UserRejectedParking -> state.endSession(event.atMillis, clearCandidate = true)

            // The car-link table's third row, and the reason `CANDIDATE_PENDING -> DRIVING`
            // exists at all: disconnect, pump fuel, get back in — the candidate is retired
            // and its notification withdrawn before it is worth anything (§17 fixture #3).
            event is DetectionEvent.CarLinkConnected -> EngineStep(
                state.copy(
                    state = DetectionState.DRIVING,
                    stateEnteredAtMillis = event.atMillis,
                    candidate = null,
                    // The session may produce a candidate again: the one it produced is
                    // being taken back, not answered.
                    session = state.session?.copy(candidateProduced = false),
                ),
                listOf(DetectionEffect.RetireCandidate(candidate.id)),
            ).withCheckpoint()

            // §3a "Leaving a pending candidate behind". Without this the engine sat here
            // for up to forty-five minutes with detection dead, which is the whole cost of
            // ignoring one prompt.
            //
            // The candidate is deliberately kept. `vehicle_enter` is a noisy signal — a bus
            // passing, a passenger seat, the OS guessing — and retiring on it would delete
            // the answer to a question the user is still holding. §10a supersedes it when
            // this new session produces a candidate of its own; the reconnect row above is
            // different because getting back in the *same* car means the parking did not
            // happen.
            event is DetectionEvent.VehicleEnter -> EngineStep(
                state.copy(
                    state = DetectionState.DRIVING_CANDIDATE,
                    stateEnteredAtMillis = event.atMillis,
                    session = state.session?.copy(candidateProduced = false),
                ),
            ).withCheckpoint()

            event is DetectionEvent.TimerTick && event.atMillis >= candidate.expiresAtMillis ->
                state.expireCandidate(event.atMillis, candidate)

            else -> EngineStep(state)
        }
    }

    private fun fromParked(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        val session = state.session ?: return EngineStep(state)
        // §11: vehicle >= 90s **and** movement >= 500m. Both, because a phone that woke up
        // in a parked car satisfies the first on its own.
        val departing = session.hasSustainedVehicleActivity(event.atMillis) &&
            session.evidence.travelDistanceMeters >= DEPARTURE_MOVEMENT_METERS
        return if (departing) state.moveTo(DetectionState.DEPARTURE_CANDIDATE, event.atMillis) else EngineStep(state)
    }

    private fun fromDepartureCandidate(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        val session = state.session ?: return state.moveTo(DetectionState.PARKED, event.atMillis)
        // "departure confirmed" is §7's guard in full — the one bar this project has for
        // "a meaningful driving session", movement clause included. Leaving a parking
        // record open is recoverable; ending one the user is still inside is not, which is
        // why departure is the one place the stricter guard is the right guard.
        if (DrivingConfirmationGuard.evaluate(session.evidence, event.atMillis).confirmed) {
            // The parking ended when the car pulled away — `stateEnteredAtMillis` is when
            // §11's two bars were first cleared — not now, which is however long the strict
            // guard took to be satisfied afterwards.
            val step = state.moveTo(DetectionState.DRIVING, event.atMillis)
            return EngineStep(
                step.state,
                listOf(DetectionEffect.EndActiveParking(state.stateEnteredAtMillis)) + step.effects,
            )
        }
        val lapsed = event is DetectionEvent.VehicleExit ||
            (
                event is DetectionEvent.TimerTick &&
                    event.atMillis - session.evidence.lastVehicleEvidenceAtMillis >=
                    DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS
                )
        return if (lapsed) {
            EngineStep(
                state.copy(state = DetectionState.PARKED, stateEnteredAtMillis = event.atMillis, session = null),
            ).withCheckpoint()
        } else {
            EngineStep(state)
        }
    }

    // ── Shared moves ────────────────────────────────────────────────────────────────

    private fun DetectionEngineState.moveTo(next: DetectionState, atMillis: Long): EngineStep =
        EngineStep(copy(state = next, stateEnteredAtMillis = atMillis)).withCheckpoint()

    /** Back to `IDLE`, dropping the travel session — which is what re-arms §12's counter. */
    private fun DetectionEngineState.endSession(atMillis: Long, clearCandidate: Boolean = false): EngineStep =
        EngineStep(
            copy(
                state = DetectionState.IDLE,
                stateEnteredAtMillis = atMillis,
                session = null,
                candidate = if (clearCandidate) null else candidate,
            ),
        ).withCheckpoint()

    private fun DetectionEngineState.expireCandidate(atMillis: Long, candidate: CandidateSnapshot): EngineStep =
        EngineStep(
            copy(
                state = DetectionState.IDLE,
                stateEnteredAtMillis = atMillis,
                session = null,
                candidate = null,
            ),
            // §10a: the notification comes down, no record is created, and nothing is
            // reported — an unanswered guess is not an event.
            listOf(DetectionEffect.RetireCandidate(candidate.id)),
        ).withCheckpoint()

    /**
     * Enter `CANDIDATE_PENDING`, or end the session when §12 already spent its one candidate.
     *
     * §3a: "a `DRIVING` session that has already produced a candidate cannot produce a
     * second one — the trip must pass through `IDLE` first." Ending the session *is*
     * passing through `IDLE`, so the next vehicle evidence starts a clean trip. This is
     * what stops a bus with repeated stops becoming a notification storm.
     */
    private fun DetectionEngineState.openCandidateOrEndSession(atMillis: Long): EngineStep {
        val session = session ?: return endSession(atMillis)
        if (session.candidateProduced) return endSession(atMillis)

        val durationMillis = (session.vehicleEndedAtMillis ?: atMillis) - session.evidence.vehicleFirstSeenAtMillis
        val score = ParkingConfidencePolicy.score(
            reasons = session.reasons,
            durationMillis = durationMillis,
            distanceMeters = session.evidence.travelDistanceMeters,
        )
        val confidence = ParkingConfidencePolicy.bucketOf(score)
        val inheritedLocation = lastReliableLocation?.takeIf { it.belongsToDrive(session, atMillis) }
        val id = newCandidateId()
        val snapshot = CandidateSnapshot(
            id = id,
            createdAtMillis = atMillis,
            expiresAtMillis = atMillis + ParkingCandidate.LIFETIME_MILLIS,
            confidence = confidence,
            score = score,
            reasons = session.reasons,
        )
        val next = copy(
            state = DetectionState.CANDIDATE_PENDING,
            stateEnteredAtMillis = atMillis,
            session = session.copy(candidateProduced = true),
            candidate = snapshot,
            candidatesCreated = candidatesCreated + 1,
        )
        return EngineStep(
            next,
            listOf(
                DetectionEffect.CreateCandidate(
                    candidateId = id,
                    confidence = confidence,
                    score = score,
                    reasons = session.reasons,
                    lastReliableLocation = inheritedLocation,
                    vehicleSessionDurationMillis = durationMillis,
                    travelDistanceMeters = session.evidence.travelDistanceMeters,
                    walkingEvidence = EvidenceReasonCode.WALKING_AFTER_VEHICLE in session.reasons,
                    gpsDegradation = EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in session.reasons,
                    optionalVehicleSignal = EvidenceReasonCode.CAR_PROJECTION_DISCONNECTED in session.reasons,
                ),
            ),
        ).withCheckpoint()
    }

    /** §14: a checkpoint accompanies every transition, so a process death mid-trip resumes. */
    private fun EngineStep.withCheckpoint(): EngineStep = copy(effects = effects + state.checkpointEffect())

    /**
     * The checkpoint as the engine sees it. [DetectionCheckpoint.revision] is left at 1
     * here and rewritten against the stored value by
     * [com.parkingkok.app.detection.ParkingDetectionRuntime] — the counter belongs to the
     * store that serializes writers, not to a pure function that cannot read one.
     */
    private fun DetectionEngineState.checkpointEffect(): DetectionEffect =
        DetectionEffect.PersistCheckpoint(toCheckpoint())

    /**
     * §5 "The fix a candidate inherits must belong to the drive that just ended".
     *
     * Both conditions, because they catch different things. The session bound refuses a fix
     * from a previous trip or from the origin of this one — the origin is not the
     * destination. The age bound refuses a long drive's only good fix when it came near the
     * start, because being on the motorway at minute two says nothing about minute ninety.
     *
     * Measured on 2026-09-20: without this, a candidate created at 17:32 carried a fix from
     * 12:00, and the confirmation screen would have drawn it on a map with its accuracy
     * printed beside it. A wrong coordinate is worse than none; `위치 없음` is a state that
     * screen already renders properly.
     */
    private fun ReliableLocation.belongsToDrive(session: TravelSession, atMillis: Long): Boolean =
        capturedAtMillis >= session.vehicleActivityStartedAtMillis &&
            atMillis - capturedAtMillis <= STALE_LOCATION_WINDOW_MILLIS

    private fun TravelSession.hasSustainedVehicleActivity(atMillis: Long): Boolean {
        val since = vehicleActiveSinceMillis ?: return false
        return vehicleEndedAtMillis == null && atMillis - since >= MINIMUM_VEHICLE_DURATION_MILLIS
    }

    private fun TravelSession.isShortlyAfterVehicleEnd(atMillis: Long): Boolean {
        val endedAt = vehicleEndedAtMillis ?: return false
        return atMillis >= endedAt && atMillis - endedAt <= TRANSITION_WINDOW_MILLIS
    }

    /** A fix that says the device stopped: §3a's "location stop" confirming signal. */
    private fun LocationSample.reportsStopped(): Boolean {
        val speed = speedMps ?: return false
        return quality.isValid && speed < DrivingConfirmationGuard.MOVING_SPEED_THRESHOLD_MPS
    }

    companion object {

        /**
         * §3a constant `minimumVehicleDuration`, 90 s.
         *
         * The same bar §11 uses for departure, on purpose: "entry uses the same bar so one
         * direction cannot be laxer than the other".
         */
        const val MINIMUM_VEHICLE_DURATION_MILLIS: Long = 90_000L

        /**
         * §5 `staleLocationWindow`, 600 s. **unvalidated.**
         *
         * The budget: the descent into a garage where the sky is lost (0–5 min), the stop
         * and the walk that confirms it (1–3 min), and the platform's transition delivery
         * delay — 17 s on the device that produced the trace this rule came from.
         */
        const val STALE_LOCATION_WINDOW_MILLIS: Long = 600_000L

        /**
         * §3a constant `drivingCandidateWindow`, 300 s.
         *
         * Sourced from §7's `vehicleEvidenceMaxAge` rather than restated, because §3a names
         * that as its source — evidence older than this is already not counted, so a
         * candidate window that outlived it would be waiting on evidence that had expired.
         */
        const val DRIVING_CANDIDATE_WINDOW_MILLIS: Long = DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS

        /**
         * §3a constant `movementIdleWindow`, 180 s. **unvalidated.**
         *
         * §7's `maximumBaseline`, which is the horizon past which an average speed stops
         * describing travel at all — so it is also the point past which "no movement
         * evidence" stops being a gap and starts being a statement.
         */
        const val MOVEMENT_IDLE_WINDOW_MILLIS: Long = MovementEvidencePolicy.MAX_BASELINE_MILLIS

        /**
         * §3a constant `transitionWindow`, 300 s. **unvalidated.**
         *
         * The §7 vehicle window again, reused so a walk that starts late still counts.
         */
        const val TRANSITION_WINDOW_MILLIS: Long = DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS

        /** §11 departure: movement >= 500 m alongside the 90 s vehicle bar. */
        const val DEPARTURE_MOVEMENT_METERS: Double = 500.0
    }
}

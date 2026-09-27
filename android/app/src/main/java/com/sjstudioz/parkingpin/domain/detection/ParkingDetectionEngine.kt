package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.DrivingConfirmationGuard
import com.sjstudioz.parkingpin.domain.location.DrivingSessionEvidence
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.location.MovementAnchor
import com.sjstudioz.parkingpin.domain.location.MovementEvidencePolicy
import com.sjstudioz.parkingpin.domain.location.ReliableLocationDecision
import com.sjstudioz.parkingpin.domain.location.ReliableLocationSelector
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import com.sjstudioz.parkingpin.domain.trace.LocationQualityBucket
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
     * The §7 evidence object. Its `vehicleFirstSeenAtMillis` is when this journey began, and
     * is what §7's duration, the 2 h ceiling and §5's inheritance bound are measured from.
     * Reused unchanged — see [DrivingSessionEvidence].
     */
    val evidence: DrivingSessionEvidence,
    /** When vehicle activity ended, by exit or by car-link disconnect. */
    val vehicleEndedAtMillis: Long? = null,
    /**
     * The `movementIdleWindow` anchor: the last moment a fix cleared §7's movement bar, or
     * the moment a stopped drive resumed — or `null` if neither has happened.
     *
     * **Null is not "long ago", and the difference is the underground car park.** A drive
     * with no fix at all has no movement that could have stopped, so `movementIdleWindow`
     * must not fire on it — seeded with the session start it would, 180 s into every
     * underground drive, because "no sky" is not "not moving". iOS has always read it this
     * way (`isMovementIdle` returns false for a nil sample).
     *
     * A resume re-anchors it (see [ParkingDetectionEngine]'s resume move) because the window
     * measures "stopped for 180 s", and a car that has just pulled away from a red light has
     * not been: left at the old value, the settle after a `vehicle_enter` resume re-fired the
     * row at once and bounced the drive straight back to `PARKING_TRANSITION`.
     *
     * It is also §8b's "the drive's last moving sample" for `location_stopped`, re-anchor
     * included: iOS's `lastMovingSampleAt` is one field serving both rules, and a stop from
     * before a resume is a red light the drive left, not how it ended.
     */
    val lastMovementEvidenceAtMillis: Long? = null,
    /**
     * The newest accepted fix that **reported** a speed below §7's movement threshold —
     * docs/05 §8b `location_stopped`, iOS's `lastStoppedFixAt`.
     *
     * Only a reported speed counts: underground there is no Doppler speed, and reading a
     * speedless fix as stillness would hand every underground drive a weight it did not earn.
     */
    val lastStoppedFixAtMillis: Long? = null,
    /**
     * When motion last said this device is *in a vehicle*, or null if it has not said so.
     *
     * Separate from the session's first vehicle evidence because a car link opens a session
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
     * The newest moment the fix quality fell **into** `poor` — a `location_quality_degraded`
     * event ending in `poor` (or naming no bucket), or an accepted fix in `poor` whose
     * accepted predecessor was `good` or `fair` (docs/05 §8b `location_quality_degraded`).
     *
     * Kept as a time rather than a flag because "near end" is judged against the stop, which
     * has not happened yet when the drop arrives: a drop ten minutes before the car stopped
     * is a tunnel on the way, not the car park at the end of it. A drive that was `poor` from
     * its first fix never *fell* into it, so it has none — that is iOS's reading too.
     */
    val lastDegradedToPoorAtMillis: Long? = null,
    /**
     * How the vehicle session ended or stopped, captured at that moment and cleared when the
     * drive resumes. `null` while driving.
     *
     * Captured rather than recomputed at candidate time, because a candidate can be
     * confirmed minutes later and §3a forbids reading the device's situation then: the
     * duration, the distance and the ending fix belong to the moment the car stopped.
     */
    val stop: VehicleStop? = null,
)

/**
 * The moment a drive ended or stopped — §8b's "end" — in the terms §8 scores it.
 *
 * Contract §6 asks for "evidence that vehicle session ended/stopped", and every way into
 * `PARKING_TRANSITION` is that evidence. *How* it ended is what §8b weighs, and the two
 * inferences must not be counted twice: an explicit exit earns `vehicle_exit_detected`, while
 * `movementIdleWindow` — an inference from absence — earns `location_stopped` instead.
 */
@Serializable
data class VehicleStop(
    /** §8b's end: the transition's entry, stamped at the window's deadline for `movementIdle`. */
    val atMillis: Long,
    /** Entered through `movementIdleWindow`, which is itself §8's "location movement stopped". */
    val byMovementIdle: Boolean,
    /** §7's accumulated distance at the stop; later jitter in the car park is not the drive. */
    val distanceMeters: Double,
    /**
     * docs/05 §8b `vehicle_exit_detected`: an explicit exit ended this drive — the transition
     * was entered by `vehicle_exit` or a car-link disconnect, or one of them arrived while it
     * was open. A `movementIdle` entry alone is not one (field drafts s16, s33: crediting it
     * scored MEDIUM here and LOW on iOS, and only this platform notified).
     */
    val exitDetected: Boolean = false,
)

/**
 * docs/05 §3a "A stop-only candidate can still be a long light" (DECIDED 2026-09-27): the
 * window in which a candidate that nothing but absence produced can still turn out to be a
 * long red light or a jam. iOS's `CandidateResume`.
 *
 * Open only while `CANDIDATE_PENDING` holds a *stop-only* candidate — its transition entered
 * by `movementIdle`, no exit, no link disconnect and no walk before it was created — from the
 * candidate's creation until [deadlineMillis]. While it is open the candidate's drive keeps
 * recording fixes and the bounded capture keeps running
 * ([com.sjstudioz.parkingpin.domain.location.LocationCaptureModePolicy.modeWantedBy]), because
 * both ways the drive can resume are location or motion rows.
 *
 * **Lives exactly as long as its capture** (docs/05 §3a, round 4). It is not part of the §14
 * [DetectionCheckpoint], but it is on the engine state and persisted with it: the Android
 * capture is a Fused Location `PendingIntent` that Play services keeps delivering to a new
 * process, and every broadcast reloads the state from disk, so a window dropped at process
 * start would almost never fire here. Where the capture does not survive — reboot,
 * force-stop, app update, a revoked permission — the adapter closes the window before the new
 * process handles anything ([com.sjstudioz.parkingpin.detection.ParkingDetectionRuntime]),
 * which is rule 4's lost capture: the window goes, the candidate stays. So on this platform an
 * open window always holds its capture, and iOS's separate `isCapturing` bit has no state to
 * describe.
 */
@Serializable
data class StopOnlyResumeWindow(
    /** The drive's end — the transition's entry. A fix older than this says nothing. */
    val driveEndedAtMillis: Long,
    /** `driveEndedAtMillis + transitionWindow`: the deadline the transition itself had. */
    val deadlineMillis: Long,
    /**
     * Accepted fixes at or after [driveEndedAtMillis] that *reported* a speed at or above §7's
     * threshold. §7's "one event alone never confirms": a single Doppler spike under a slab
     * must not withdraw a parking, so the resume needs [DrivingConfirmationGuard.MIN_MOVING_SAMPLES].
     */
    val reportedMovingFixes: Int = 0,
)

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
 * Serializable in full, because §14 requires a checkpoint on every meaningful transition and a
 * process started by a broadcast has to be able to pick the trip up mid-drive.
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
    /**
     * §3a "DECIDED 2026-09-20: a connected car link suppresses `movementIdleWindow`".
     *
     * True between a `projection_connected`/`bluetooth_car_connected` and the matching
     * disconnect. It suppresses that one row only — `drivingCandidateWindow`, the transition
     * window and the 2 h ceiling still fire. It lives on the state rather than on the travel
     * session because the link outlives sessions: sitting in the car with the radio on lets
     * `drivingCandidateWindow` retire a session while the link stays up, and a latch dropped
     * with it made the next drive idle out at the first long red light. iOS keeps
     * `connectedCarLinks` across sessions for the same reason.
     *
     * **It must not outlive the link.** The engine is pure and cannot poll, so §3a makes it
     * the adapter's obligation to re-assert the real state on process start and feed a
     * disconnect when the link is gone.
     */
    val carLinkConnected: Boolean = false,
    /**
     * Non-null only in `CANDIDATE_PENDING`, while a stop-only candidate may still be retired.
     * Persisted: see [StopOnlyResumeWindow] for why it survives exactly when its capture does.
     */
    val stopOnlyResumeWindow: StopOnlyResumeWindow? = null,
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
         * session is the one iOS's runner restores for the same fixture: vehicle activity and
         * the drive both begin at [atMillis], and nothing more. It used to start
         * [ParkingDetectionEngine.MINIMUM_VEHICLE_DURATION_MILLIS] earlier, which made every
         * seeded drive 90 s longer here than on iOS — a different §8 duration code for the
         * same bytes.
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

        private fun seededSession(atMillis: Long): TravelSession = TravelSession(
            evidence = DrivingSessionEvidence(
                vehicleFirstSeenAtMillis = atMillis,
                lastVehicleEvidenceAtMillis = atMillis,
            ),
            vehicleActiveSinceMillis = atMillis,
        )
    }
}

/**
 * What the engine wants done about the world, in the vocabulary of
 * docs/05_PARKING_DETECTION_ENGINE.md §15.
 *
 * The engine performs none of it. Effects are values so the state machine stays a pure
 * function of (state, event) and can be replayed from JSON on either platform; the SDK
 * calls live in [com.sjstudioz.parkingpin.detection.ParkingDetectionRuntime].
 *
 * `startBoundedLocationCapture` / `stopLocationCapture` are **not** here on purpose: the
 * capture is derived from the engine's *state* instead
 * ([com.sjstudioz.parkingpin.domain.location.LocationCaptureModePolicy.modeWantedBy]), which the
 * runtime reports to the one owner of the Fused Location request whenever a batch changes
 * it. Two owners of one registration is the bug that leaks a session.
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
     * §3a's reconnect row and the 45-minute expiry both land here, and so does §11c's hand
     * save. None is a rejection: the user said nothing — or, for the save, said "parked" —
     * and reporting one would poison the single distribution §10a calls the event that pays
     * for the whole feature.
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
 * §3a mixes conditions that *become true by holding* with conditions that are *timeouts*.
 * Both are judged on every event, against that event's own timestamp, in the order ingest,
 * windows, edge, windows — see [handle] for why that order is the one that survived the
 * field traces, and [DetectionEvent.TimerTick] for the drive that produces no events.
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

    /**
     * One event, then every §3a row whose condition is a timeout the event's own timestamp
     * has already carried past.
     *
     * ### Why the timeouts are settled here and not only on a [DetectionEvent.TimerTick]
     * They used to fire only on an explicit tick and **nothing in the app produced one**, so
     * `drivingCandidateWindow`, `movementIdleWindow` and `transitionWindow` were dead in the
     * shipped build while passing fixtures that supply ticks of their own. A drive that
     * ended with no `vehicle_exit` stayed in `DRIVING` for ever and the trip was lost.
     *
     * Settling **after** the edge is the ordering, and it is the one that survived
     * measurement. Opening a batch with a tick instead was tried on 2026-09-20 and flipped
     * `subway_commute_underground` to `IDLE`: the windows were judged before the fix that
     * would have advanced them. An edge is also evidence, and evidence is folded first.
     *
     * The windows are judged **before** the edge too — ingest, windows, edge, windows — so
     * a `walking_enter` that arrives after `transitionWindow` has already closed finds
     * `IDLE` and confirms nothing, exactly as on iOS (docs/05 §3a "The windows are judged
     * before the edge too").
     */
    fun handle(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        // §3a's `any -> PARKED` row is answered before any evidence is folded or any window
        // judged: the user has said where the car is, and nothing the engine was inferring
        // — including a window that happened to close at this instant — outranks that.
        if (event is DetectionEvent.UserSavedParking) return userSavedParking(state, event.atMillis)

        val effects = mutableListOf<DetectionEffect>()

        // The evidence this event carries, folded **before** the windows are judged. This is
        // the half that is easy to get wrong: a fix that lands more than `movementIdleWindow`
        // after the last one is a drive continuing, and judging the window first would read
        // it as a parking. Measured on 2026-09-20 flipping `subway_commute_underground`.
        val ingested = ingestEvidence(state, event)
        val settled = settleTimeouts(ingested, event.atMillis, effects)

        // Then the edge, against the state the windows left behind. `before` stays the state
        // as it was on entry, because both rules that read it ask "what did *this event*
        // change" — the promotion bar in `DRIVING_CANDIDATE` and the returning-movement row
        // in `PARKING_TRANSITION` — and a settle in between changes neither answer.
        val folded = fold(settled, event)
        val edge = transition(before = state, state = folded, event = event)
        effects += edge.effects

        return EngineStep(settleTimeouts(edge.state, event.atMillis, effects), effects)
    }

    /**
     * The evidence half of the fold, and the only part that runs before the windows.
     *
     * Mirrors iOS's `ingest`, which folds a location fix and leaves every motion edge to
     * `applyEdge`. The split is what lets the windows be judged with this event's evidence in
     * hand but without its transition already applied — so a `walking_enter` that arrives
     * after `transitionWindow` has closed finds `IDLE` and confirms nothing, which is what
     * iOS has always done and Android did not.
     */
    private fun ingestEvidence(state: DetectionEngineState, event: DetectionEvent): DetectionEngineState {
        // A pending candidate's drive is finished: iOS holds it aside and folds nothing into
        // it, so a car-link reconnect (§3a, the fuel stop) resumes the drive exactly as it
        // ended rather than with the walk around the pump added to its distance and anchors.
        //
        // Except a stop-only candidate's, inside its resume window (§3a rule 1): that drive
        // may still be a long light, so it keeps recording fixes — §5 gate, anchors, distance
        // — and a resume continues from where the car actually is. `foldLocation` does not run
        // §6's selection in this state. Judged on the window as it stands before the settle,
        // as iOS folds before its windows close.
        if (state.state == DetectionState.CANDIDATE_PENDING) {
            val foldsFix = event is DetectionEvent.Location && state.stopOnlyResumeWindow != null
            return if (foldsFix) foldLocation(state, event.sample) else state
        }
        return when (event) {
            is DetectionEvent.Location -> foldLocation(state, event.sample)
            // Only a fall into `poor` is §8b's "GPS quality degraded"; whether it was *near
            // the end* is judged against the stop when the candidate is scored.
            is DetectionEvent.LocationQualityDegraded -> if (event.reachedPoor) {
                state.copy(session = state.session?.notingFallIntoPoor(event.atMillis))
            } else {
                state
            }
            else -> state
        }
    }

    /**
     * Runs [timeoutRow] to a fixed point, because one event can be minutes after the last
     * and has to catch up more than one boundary: a drive that promoted, went quiet and then
     * let its transition window close is three rows in a single moment.
     *
     * The cap is bug containment rather than a rule — every row moves the state, so a loop
     * that does not settle is a defect and not a slow case.
     */
    private fun settleTimeouts(
        state: DetectionEngineState,
        atMillis: Long,
        effects: MutableList<DetectionEffect>,
    ): DetectionEngineState {
        var current = state
        repeat(MAX_TIMEOUT_CASCADE) {
            val step = timeoutRow(current, atMillis) ?: return current
            current = step.state
            effects += step.effects
        }
        return current
    }

    /**
     * §3a's elapsed-time rows, and **the only place they live** — an event-driven copy of
     * any of them would be a second definition of the same deadline.
     *
     * `null` means no row applies, which is the ordinary answer.
     */
    private fun timeoutRow(state: DetectionEngineState, atMillis: Long): EngineStep? {
        leftBehindCandidateExpiry(state, atMillis)?.let { return it }
        val session = state.session
        return when (state.state) {
            DetectionState.DRIVING_CANDIDATE -> when {
                // Promotion is read first, and that ordering is a decision: both conditions
                // can be true on the same late moment, and promotion's became true first
                // (`minimumVehicleDuration` 90 s against `drivingCandidateWindow` 300 s).
                // `subway_commute_underground` is exactly that case — the first event after
                // `vehicle_enter` is 303 s later — and reading the timeout first would send
                // a 45-minute ride back to `IDLE`.
                session?.hasSustainedVehicleActivity(atMillis) == true ->
                    state.moveTo(DetectionState.DRIVING, atMillis)

                // Not gated on the link: a link connected with no drive is someone sitting
                // in a parked car with the radio on, which is what this row is for.
                atMillis - state.stateEnteredAtMillis >= DRIVING_CANDIDATE_WINDOW_MILLIS ->
                    state.endSession(state.stamp(state.stateEnteredAtMillis + DRIVING_CANDIDATE_WINDOW_MILLIS, atMillis))

                else -> null
            }

            DetectionState.DRIVING -> when {
                session == null -> null

                // The ceiling, and the one row a connected car link does not suppress: it
                // is a bound on the session itself rather than an inference about the car.
                // Straight to `IDLE` with no candidate, exactly as iOS's
                // `maximumDurationReached` does — two hours in, nothing here knows where the
                // car was left, and guessing would be worse than saying nothing.
                //
                // Without it a drive that never sees another fix keeps the location
                // foreground service up for ever, which is the battery cost §19 exists to
                // bound.
                atMillis - session.evidence.vehicleFirstSeenAtMillis >= SESSION_MAXIMUM_DURATION_MILLIS ->
                    state.endSession(
                        state.stamp(session.evidence.vehicleFirstSeenAtMillis + SESSION_MAXIMUM_DURATION_MILLIS, atMillis),
                    )

                // Entered when the window closed, which is also §8b's end for this drive:
                // the stop the candidate's duration, near-end horizon and inheritance bound
                // are all measured from.
                isMovementIdle(state, session, atMillis) ->
                    state.enterParkingTransition(
                        state.stamp(checkNotNull(session.lastMovementEvidenceAtMillis) + MOVEMENT_IDLE_WINDOW_MILLIS, atMillis),
                        byMovementIdle = true,
                    )

                else -> null
            }

            DetectionState.PARKING_TRANSITION ->
                if (atMillis - state.stateEnteredAtMillis >= TRANSITION_WINDOW_MILLIS) {
                    state.endSession(state.stamp(state.stateEnteredAtMillis + TRANSITION_WINDOW_MILLIS, atMillis))
                } else {
                    null
                }

            // The stop-only resume window lapsing moves nothing — the candidate stands and only
            // the capture goes (§3a rule 3). It is judged first and is its own step, so an
            // expiry due on the same late event is still seen on the next pass. No checkpoint:
            // nothing §14 records changed.
            DetectionState.CANDIDATE_PENDING -> when {
                state.stopOnlyResumeWindow?.let { atMillis >= it.deadlineMillis } == true ->
                    EngineStep(state.copy(stopOnlyResumeWindow = null))

                else -> state.candidate
                    ?.takeIf { atMillis >= it.expiresAtMillis }
                    ?.let { state.expireCandidate(state.stamp(it.expiresAtMillis, atMillis), it) }
            }

            // §11: the vehicle evidence that opened the departure went stale before §7's
            // guard was ever satisfied. The car never actually left.
            //
            // Strictly *past* the window: §7 counts evidence exactly `RECENT_VEHICLE_WINDOW`
            // old as still recent (`<=`), so at that instant the guard can still confirm, and
            // a lapse that fired first — the settle runs before the edge — would drop a real
            // departure that iOS, which asks the guard first, confirms.
            DetectionState.DEPARTURE_CANDIDATE ->
                if (session != null &&
                    atMillis - session.evidence.lastVehicleEvidenceAtMillis >
                    DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS
                ) {
                    val lapse = session.evidence.lastVehicleEvidenceAtMillis + DrivingConfirmationGuard.RECENT_VEHICLE_WINDOW_MILLIS
                    EngineStep(
                        state.copy(
                            state = DetectionState.PARKED,
                            stateEnteredAtMillis = state.stamp(lapse, atMillis),
                            session = null,
                        ),
                    ).withCheckpoint()
                } else {
                    null
                }

            DetectionState.IDLE, DetectionState.PARKED -> null
        }
    }

    /**
     * docs/05 §3a "A window row is stamped at its deadline, not at the event that noticed it"
     * (2026-09-27): no earlier than the current state's own entry, no later than now.
     *
     * Stamped at observation, the next window started late by however long the device
     * happened to be quiet, so the same trace replayed differently with and without an
     * unrelated tick — and differently from iOS, which stamps at the deadline (field drafts
     * s25 and s27 ended `IDLE` there and in a candidate or a transition here).
     */
    private fun DetectionEngineState.stamp(deadlineMillis: Long, nowMillis: Long): Long =
        minOf(nowMillis, maxOf(deadlineMillis, stateEnteredAtMillis))

    /**
     * §3a `movementIdleWindow`, suppressed while the phone is still attached to the car.
     *
     * Two guards, and both are about the same mistake — inferring a parking from an absence.
     * A drive with no fix at all (`null`) has no movement that could have stopped, and a
     * phone still on the car's audio during a 180-second gap is at a red light, in a tunnel
     * or on a ramp.
     */
    private fun isMovementIdle(state: DetectionEngineState, session: TravelSession, atMillis: Long): Boolean {
        if (state.carLinkConnected) return false
        val lastMovement = session.lastMovementEvidenceAtMillis ?: return false
        return atMillis - lastMovement >= MOVEMENT_IDLE_WINDOW_MILLIS
    }

    /**
     * §10's 45-minute expiry for a candidate left behind by a new journey.
     *
     * `CANDIDATE_PENDING -> DRIVING_CANDIDATE` keeps the candidate so the user can still
     * answer it, and the expiry used to be judged only in `CANDIDATE_PENDING` — so a prompt
     * whose new journey produced nothing outlived its 45 minutes in the engine. The state is
     * not moved: the journey in progress has nothing to do with an old prompt going stale.
     */
    private fun leftBehindCandidateExpiry(state: DetectionEngineState, atMillis: Long): EngineStep? {
        if (state.state == DetectionState.CANDIDATE_PENDING) return null
        val candidate = state.candidate?.takeIf { atMillis >= it.expiresAtMillis } ?: return null
        return EngineStep(
            state.copy(candidate = null),
            listOf(DetectionEffect.RetireCandidate(candidate.id)),
        ).withCheckpoint()
    }

    // ── Evidence folding ────────────────────────────────────────────────────────────
    // Records what each event observed, as facts with their timestamps. The §4 codes are
    // derived from these facts once, when a candidate is created (see [candidateReasons]),
    // which is how iOS has always built them — and the reason is parity rather than taste:
    // codes appended per event credited evidence the drive never had (a red light early in
    // the drive as "location stopped", a walk after a 100 s drive as "duration met"), and the
    // same field draft scored HIGH here and MEDIUM on iOS.

    private fun fold(state: DetectionEngineState, event: DetectionEvent): DetectionEngineState {
        return when (event) {
            // §3a "`CANDIDATE_PENDING -> DRIVING_CANDIDATE` — a new journey starts": a new
            // travel session, not the old one extended. Extending it carried trip 1's walk,
            // exit, distance and first-sighting time into trip 2's candidate (field draft
            // s03 scored HIGH on evidence that belonged to the previous parking). The
            // pending candidate itself stays on the state, answerable, until §10a
            // supersedes it.
            //
            // Inside a stop-only candidate's resume window it is the opposite: the vehicle
            // never ended, and its evidence returning is the long light ending — the same
            // drive, resumed by the edge (§3a "A stop-only candidate can still be a long light").
            is DetectionEvent.VehicleEnter -> {
                val newJourney = state.state == DetectionState.CANDIDATE_PENDING && state.stopOnlyResumeWindow == null
                val previous = state.session.takeUnless { newJourney }
                state.copy(
                    session = openOrExtendSession(previous, event.atMillis).let {
                        it.copy(vehicleActiveSinceMillis = it.vehicleActiveSinceMillis ?: event.atMillis)
                    },
                )
            }
            is DetectionEvent.VehicleExit -> state.copy(
                session = endVehicleActivity(state.session, event.atMillis)?.copy(vehicleActiveSinceMillis = null),
            )

            // Motion confirming signals carry no evidence of their own outside
            // `PARKING_TRANSITION`; there they are the edge, and the edge names them.
            is DetectionEvent.WalkingEnter,
            is DetectionEvent.StationaryEnter,
            is DetectionEvent.StationaryExit,
            -> state

            // Already folded by `ingestEvidence`, before the windows were judged. Folding
            // again here would count one fix twice.
            is DetectionEvent.Location,
            is DetectionEvent.LocationQualityDegraded,
            -> state

            // §3a: opens a session, but does **not** arm the promotion bar. People sit in
            // parked cars, and 90 s of Bluetooth in a stationary one used to promote the
            // session here — so getting back out produced a candidate for a drive that
            // never happened.
            is DetectionEvent.CarLinkConnected -> state.copy(
                carLinkConnected = true,
                session = openOrExtendSession(state.session, event.atMillis),
            )

            is DetectionEvent.CarLinkDisconnected -> state.copy(
                carLinkConnected = false,
                session = endVehicleActivity(state.session, event.atMillis)
                    ?.copy(vehicleActiveSinceMillis = null),
            )

            is DetectionEvent.TimerTick,
            is DetectionEvent.UserConfirmedParking,
            is DetectionEvent.UserRejectedParking,
            -> state

            // Answered in [handle] before any fold, so it never reaches here.
            is DetectionEvent.UserSavedParking -> state
        }
    }

    /**
     * Vehicle evidence arrived: open a journey, or extend the one in progress.
     *
     * Extending keeps the first-sighting time — a second `vehicle_enter` mid-drive is the OS
     * repeating itself, and the journey (its duration, its 2 h ceiling) began at the first.
     * The 90 s promotion clock is [TravelSession.vehicleActiveSinceMillis], which the caller
     * only arms when it is not already running.
     */
    private fun openOrExtendSession(session: TravelSession?, atMillis: Long): TravelSession {
        if (session == null) {
            return TravelSession(
                evidence = DrivingSessionEvidence(
                    vehicleFirstSeenAtMillis = atMillis,
                    lastVehicleEvidenceAtMillis = atMillis,
                ),
            )
        }
        return session.copy(
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
        )
    }

    private fun foldLocation(state: DetectionEngineState, sample: LocationSample): DetectionEngineState {
        // Every fix outside a session is ignored, as on iOS, whose fixes reach the engine only
        // through an open drive: a fix nobody is driving has nothing to say about a parking.
        val session = state.session ?: return state
        // Both asked before the fold, because both answers are about the fix this one follows.
        val admitted = session.evidence.movement.admits(sample)
        val fellIntoPoor = admitted && fallsIntoPoor(session.evidence.movement.lastFix, sample)

        val evidence = session.evidence.recordingFix(sample)
        val moved = evidence.movement.movingSampleCount > session.evidence.movement.movingSampleCount
        val updated = session.copy(
            evidence = evidence,
            lastMovementEvidenceAtMillis = if (moved) sample.atMillis else session.lastMovementEvidenceAtMillis,
            lastStoppedFixAtMillis = if (admitted && !moved && sample.reportsStopped()) {
                sample.atMillis
            } else {
                session.lastStoppedFixAtMillis
            },
        ).let { if (fellIntoPoor) it.notingFallIntoPoor(sample.atMillis) else it }
        return state.copy(
            session = updated,
            lastReliableLocation =
                if (admitted && state.selectsParkingSpot()) selectReliable(state.lastReliableLocation, sample) else state.lastReliableLocation,
        )
    }

    /**
     * Whether a fix in this state may become the parking spot (§6).
     *
     * Not while a candidate is pending: the candidate already carries its location, and the
     * fixes then are the walk away from the car. iOS has no drive open in that state and so
     * never selects there either.
     */
    private fun DetectionEngineState.selectsParkingSpot(): Boolean = state != DetectionState.CANDIDATE_PENDING

    /**
     * §6 selection, reused verbatim, and only for a fix §5's outlier check admitted — a
     * teleporting fix is not a parking spot however accurate it claims to be. The fixture
     * replay feeds each fix at its own timestamp, so `nowMillis` is the fix's own time and
     * the freshness guards behave exactly as they do for a live single-fix delivery.
     */
    private fun selectReliable(current: ReliableLocation?, sample: LocationSample): ReliableLocation? {
        val decision = ReliableLocationSelector.select(current = current, sample = sample, nowMillis = sample.atMillis)
        return (decision as? ReliableLocationDecision.Accepted)?.location ?: current
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
        DetectionState.CANDIDATE_PENDING -> fromCandidatePending(before, state, event)
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

            else -> EngineStep(state)
        }
    }

    private fun fromDriving(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        val session = state.session ?: return state.moveTo(DetectionState.IDLE, event.atMillis)
        return when {
            // The car-link table: a disconnect skips `PARKING_TRANSITION` outright. Waiting
            // for a walk would lose the underground car park §3a was corrected for, and the
            // link has already said the engine stopped and the phone left the car.
            event is DetectionEvent.CarLinkDisconnected ->
                state.withStop(event.atMillis, byMovementIdle = false)
                    .openCandidateOrEndSession(event.atMillis, ConfirmingSignal.CAR_LINK_DISCONNECT)

            event is DetectionEvent.VehicleExit -> state.enterParkingTransition(event.atMillis, byMovementIdle = false)

            else -> EngineStep(state)
        }
    }

    private fun fromParkingTransition(
        before: DetectionEngineState,
        state: DetectionEngineState,
        event: DetectionEvent,
    ): EngineStep {
        val session = state.session ?: return state.moveTo(DetectionState.IDLE, event.atMillis)
        return when (event) {
            // §3a's confirming signals, plus the link disconnect, which is the strongest of
            // them: it is an event rather than an inference from absence.
            is DetectionEvent.WalkingEnter ->
                state.openCandidateOrEndSession(event.atMillis, ConfirmingSignal.WALKING)
            is DetectionEvent.StationaryEnter ->
                state.openCandidateOrEndSession(event.atMillis, ConfirmingSignal.STATIONARY)
            is DetectionEvent.CarLinkDisconnected ->
                state.openCandidateOrEndSession(event.atMillis, ConfirmingSignal.CAR_LINK_DISCONNECT)

            is DetectionEvent.VehicleExit -> state.creditingExit()

            is DetectionEvent.Location -> fromTransitionOnFix(before, state, session, event.sample)

            // Vehicle activity resumed outright — the same return as a moving fix, on a
            // motion or link event.
            is DetectionEvent.VehicleEnter, is DetectionEvent.CarLinkConnected ->
                state.resumeDriving(event.atMillis)

            else -> EngineStep(state)
        }
    }

    /**
     * The two location rows of §3a's `PARKING_TRANSITION` ("The `PARKING_TRANSITION` rows,
     * exactly", 2026-09-27). Both need a fix that
     *
     * 1. the drive's §5 gate accepted — valid accuracy, not an outlier step: a GPS jump on the
     *    way underground is neither a stop nor a drive, whatever speed it reports;
     * 2. is timestamped at or after the transition's entry — the rule every confirming signal
     *    shares; a fix from before the drive ended says nothing about this parking.
     *
     * Then **movement returns** when it cleared §7's movement bar (the red light is over), and
     * a **location stop** confirms when it *reported* a speed below the threshold. A fix cannot
     * be both, so the order is immaterial.
     *
     * The fix that revealed `movementIdleWindow` counts: the entry is stamped at the window's
     * deadline, which that fix is past.
     */
    private fun fromTransitionOnFix(
        before: DetectionEngineState,
        state: DetectionEngineState,
        session: TravelSession,
        sample: LocationSample,
    ): EngineStep {
        val drive = before.session?.evidence?.movement ?: return EngineStep(state)
        if (!drive.admits(sample) || sample.atMillis < state.stateEnteredAtMillis) return EngineStep(state)
        val moved = session.evidence.movement.movingSampleCount > drive.movingSampleCount
        return when {
            moved -> state.resumeDriving(sample.atMillis)
            sample.reportsStopped() -> state.openCandidateOrEndSession(sample.atMillis, ConfirmingSignal.LOCATION_STOP)
            else -> EngineStep(state)
        }
    }

    private fun fromCandidatePending(
        before: DetectionEngineState,
        state: DetectionEngineState,
        event: DetectionEvent,
    ): EngineStep {
        val candidate = state.candidate ?: return state.moveTo(DetectionState.IDLE, event.atMillis)
        state.stopOnlyResumeWindow?.let { window ->
            fromStopOnlyWindow(before, state, window, event)?.let { return it }
        }
        return when {
            // The snapshot goes with the answer, as it does on rejection. Kept, it was
            // indistinguishable from a live prompt outside `CANDIDATE_PENDING`, which is why a
            // hand save or the expiry could not safely retire a candidate left behind there.
            event is DetectionEvent.UserConfirmedParking -> EngineStep(
                state.copy(
                    state = DetectionState.PARKED,
                    stateEnteredAtMillis = event.atMillis,
                    session = null,
                    candidate = null,
                    stopOnlyResumeWindow = null,
                ),
                listOf(DetectionEffect.MarkParkingActive(candidate.id)),
            ).withCheckpoint()

            event is DetectionEvent.UserRejectedParking -> state.endSession(event.atMillis, clearCandidate = true)

            // The car-link table's third row, and the reason `CANDIDATE_PENDING -> DRIVING`
            // exists at all: disconnect, pump fuel, get back in — the candidate is retired
            // and its notification withdrawn before it is worth anything (§17 fixture #3).
            event is DetectionEvent.CarLinkConnected -> {
                val resumed = state.resumeDriving(event.atMillis)
                EngineStep(
                    resumed.state.copy(
                        candidate = null,
                        // The session may produce a candidate again: the one it produced is
                        // being taken back, not answered.
                        session = resumed.state.session?.copy(candidateProduced = false),
                    ),
                    listOf(DetectionEffect.RetireCandidate(candidate.id)),
                ).withCheckpoint()
            }

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
            // The fold has already opened the new journey's own session.
            event is DetectionEvent.VehicleEnter -> state.moveTo(DetectionState.DRIVING_CANDIDATE, event.atMillis)

            else -> EngineStep(state)
        }
    }

    /**
     * docs/05 §3a "A stop-only candidate can still be a long light" (DECIDED 2026-09-27), the
     * rows that exist only while [StopOnlyResumeWindow] is open. `null` hands the event to the
     * ordinary `CANDIDATE_PENDING` rows.
     *
     * - the **second** accepted fix at or after the drive's end that *reports* ≥ 2.0 m/s, or a
     *   `vehicle_enter`, resumes the drive (rule 2);
     * - a `vehicle_exit` or a `walking_enter` closes the window and nothing else (rule 4): the
     *   vehicle ended after all, or the person left it — movement after either is somebody
     *   else's journey. iOS's engine closes it on the same two, so every fixture reads alike.
     *
     * A `stationary_enter` leaves it open: the Transition API can report STILL inside a car at
     * a light.
     */
    private fun fromStopOnlyWindow(
        before: DetectionEngineState,
        state: DetectionEngineState,
        window: StopOnlyResumeWindow,
        event: DetectionEvent,
    ): EngineStep? = when (event) {
        is DetectionEvent.VehicleEnter -> state.resumeFromStopOnlyCandidate(event.atMillis, reanchorIdleClock = true)
        is DetectionEvent.VehicleExit, is DetectionEvent.WalkingEnter -> EngineStep(state.copy(stopOnlyResumeWindow = null))
        is DetectionEvent.Location -> onFixInsideStopOnlyWindow(before, state, window, event.sample)
        else -> null
    }

    /**
     * §3a rule 2's location half. Only a **reported** speed counts: the §7 distance fallback
     * reads the jitter around a parked car as travel — after s03's real parking the walk-away
     * fixes clear it at 2.01 m/s on the replay — and here a false resume would withdraw a real
     * parking, where in the transition it only delays a decision.
     */
    private fun onFixInsideStopOnlyWindow(
        before: DetectionEngineState,
        state: DetectionEngineState,
        window: StopOnlyResumeWindow,
        sample: LocationSample,
    ): EngineStep {
        val drive = before.session?.evidence?.movement ?: return EngineStep(state)
        val counts = drive.admits(sample) && sample.atMillis >= window.driveEndedAtMillis && sample.reportsMoving()
        if (!counts) return EngineStep(state)
        val seen = window.copy(reportedMovingFixes = window.reportedMovingFixes + 1)
        if (seen.reportedMovingFixes < DrivingConfirmationGuard.MIN_MOVING_SAMPLES) {
            return EngineStep(state.copy(stopOnlyResumeWindow = seen))
        }
        // The moving fix already anchored the idle clock when it was folded.
        return state.resumeFromStopOnlyCandidate(sample.atMillis, reanchorIdleClock = false)
    }

    /**
     * `CANDIDATE_PENDING → DRIVING` for a stop-only candidate — the twin of
     * `PARKING_TRANSITION → DRIVING` — in one checkpoint.
     *
     * The candidate is withdrawn the way §10 withdraws an expired one (no record, no report),
     * and the drive it came from continues as the **same** travel session: start, distance and
     * anchors, exactly as "Resuming keeps the drive" keeps them. §12's allowance is restored so
     * the trip can still produce the real parking. The vehicle level is on again, as iOS sets
     * `isVehicleActive`. On vehicle evidence the idle clock is re-anchored at the resume; a
     * resume on a moving fix is anchored by that fix.
     */
    private fun DetectionEngineState.resumeFromStopOnlyCandidate(atMillis: Long, reanchorIdleClock: Boolean): EngineStep {
        val session = session ?: return moveTo(DetectionState.IDLE, atMillis)
        val withdrawn = candidate
        val resumed = copy(
            candidate = null,
            session = session.copy(
                stop = null,
                candidateProduced = false,
                vehicleEndedAtMillis = null,
                vehicleActiveSinceMillis = session.vehicleActiveSinceMillis ?: atMillis,
                lastMovementEvidenceAtMillis = if (reanchorIdleClock) {
                    session.lastMovementEvidenceAtMillis?.let { maxOf(it, atMillis) }
                } else {
                    session.lastMovementEvidenceAtMillis
                },
            ),
        ).moveTo(DetectionState.DRIVING, atMillis)
        return EngineStep(
            resumed.state,
            listOfNotNull(withdrawn?.let { DetectionEffect.RetireCandidate(it.id) }) + resumed.effects,
        )
    }

    private fun fromParked(state: DetectionEngineState, event: DetectionEvent): EngineStep {
        val session = state.session ?: return EngineStep(state)
        // §11b. The mirror of §3a's disconnect row: the phone rejoining the car is the
        // strongest departure signal there is, and waiting for 500 m of GPS to say the same
        // thing is waiting for evidence that is already in.
        //
        // It opens the candidate and nothing more — `DEPARTURE_CANDIDATE` shows nothing and
        // ends nothing until §7's guard is satisfied, so sitting in a parked car with the
        // radio on costs the record nothing.
        if (event is DetectionEvent.CarLinkConnected) {
            return state.moveTo(DetectionState.DEPARTURE_CANDIDATE, event.atMillis)
        }
        // Got in, got out again without clearing §11's bars. The episode's evidence goes with
        // it, as on iOS: kept, the next get-in inherited its metres and its first-sighting
        // time, and two short sits in a parked car could add up to a departure.
        if (event is DetectionEvent.VehicleExit) {
            return EngineStep(state.copy(session = null)).withCheckpoint()
        }
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
            val ended = DetectionEffect.EndActiveParking(state.stateEnteredAtMillis)
            val confirmed = state.moveTo(DetectionState.DRIVING, event.atMillis)
            // docs/05 §11: the event that confirmed the departure is then read in `DRIVING`,
            // as `DRIVING_CANDIDATE` reads the exit that promoted it. A `vehicle_exit` or a
            // link disconnect whose arrival met the guard only through elapsed time is also
            // the end of that drive — the short hop into an underground garage — and
            // swallowing it lost the next parking.
            val next = fromDriving(confirmed.state, event)
            val step = if (next.effects.isEmpty()) confirmed else next
            return EngineStep(step.state, listOf(ended) + step.effects)
        }
        // The stale-evidence half of §11's lapse is a timeout and lives in [timeoutRow];
        // an explicit exit is the event half.
        return if (event is DetectionEvent.VehicleExit) {
            EngineStep(
                state.copy(state = DetectionState.PARKED, stateEnteredAtMillis = event.atMillis, session = null),
            ).withCheckpoint()
        } else {
            EngineStep(state)
        }
    }

    /**
     * §11c: the user saved a parking themselves, so the car is parked — from any state.
     *
     * The travel session goes, whole and silently: its driving evidence, a parking
     * transition, a departure's evidence and the vehicle activity with them. No candidate,
     * because the user just answered the question the session was building toward; and no
     * [DetectionEffect.EndActiveParking], because the save flow closes the previous record
     * itself and ending one here would close the record just written. Dropping the session
     * is also what makes the next `vehicle_enter` open a *departure's* evidence, which
     * §11's two bars and §7's guard then have to earn as before.
     *
     * A live prompt is retired, as §10's rejection would retire it, but through
     * [DetectionEffect.RetireCandidate] so it is not reported as one: the user did not say
     * "not parked", they said "parked, here". That includes a prompt left behind by a new
     * journey, exactly as iOS withdraws it: an answered candidate no longer leaves a snapshot
     * on the state, so any candidate still held is one nobody has answered.
     *
     * The car-link latch is left alone: saving a parking does not unplug the phone.
     *
     * Location capture is not stopped here: the engine does not own the request (see
     * [DetectionEffect]). [com.sjstudioz.parkingpin.detection.ParkingDetectionRuntime.handleUserSavedParking]
     * does it beside this call.
     */
    private fun userSavedParking(state: DetectionEngineState, atMillis: Long): EngineStep {
        val live = state.candidate
        return EngineStep(
            state.copy(
                state = DetectionState.PARKED,
                stateEnteredAtMillis = atMillis,
                session = null,
                candidate = null,
                stopOnlyResumeWindow = null,
            ),
            listOfNotNull(live?.let { DetectionEffect.RetireCandidate(it.id) }),
        ).withCheckpoint()
    }

    // ── Shared moves ────────────────────────────────────────────────────────────────

    /** The stop-only window belongs to `CANDIDATE_PENDING` and never outlives it. */
    private fun DetectionEngineState.moveTo(next: DetectionState, atMillis: Long): EngineStep =
        EngineStep(
            copy(
                state = next,
                stateEnteredAtMillis = atMillis,
                stopOnlyResumeWindow = stopOnlyResumeWindow.takeIf { next == DetectionState.CANDIDATE_PENDING },
            ),
        ).withCheckpoint()

    /** `DRIVING -> PARKING_TRANSITION`, recording the stop §8 will score. */
    private fun DetectionEngineState.enterParkingTransition(atMillis: Long, byMovementIdle: Boolean): EngineStep =
        withStop(atMillis, byMovementIdle).moveTo(DetectionState.PARKING_TRANSITION, atMillis)

    /**
     * Captures [VehicleStop] at the moment the drive ended or stopped. Only the first stop of
     * an open transition counts: an exit that arrives after `movementIdleWindow` already
     * opened it is the same stop, reported late.
     */
    private fun DetectionEngineState.withStop(atMillis: Long, byMovementIdle: Boolean): DetectionEngineState {
        val session = session ?: return this
        if (session.stop != null) return this
        return copy(
            session = session.copy(
                stop = VehicleStop(
                    atMillis = atMillis,
                    byMovementIdle = byMovementIdle,
                    distanceMeters = session.evidence.travelDistanceMeters,
                    // Every entry but `movementIdle` is an explicit exit or a link
                    // disconnect (§8b).
                    exitDetected = !byMovementIdle,
                ),
            ),
        )
    }

    /**
     * docs/05 §8b: a `vehicle_exit` that arrives while a transition is open is still this
     * drive's exit and earns `vehicle_exit_detected`. It is not a confirming signal — §3a
     * names walking, stationary and a location stop — so the state does not move.
     */
    private fun DetectionEngineState.creditingExit(): EngineStep {
        val session = session ?: return EngineStep(this)
        val stop = session.stop?.takeUnless { it.exitDetected } ?: return EngineStep(this)
        return EngineStep(copy(session = session.copy(stop = stop.copy(exitDetected = true))))
    }

    /**
     * Back to `DRIVING` from a stop that turned out not to be a parking.
     *
     * The drive's evidence is kept — duration and distance are the whole trip's — and the
     * stop is forgotten. The idle clock restarts at the resume: `movementIdleWindow` means
     * "stopped for 180 s", and a car that has just pulled away has not been. A drive that
     * never had movement evidence keeps `null`, because §3a does not let silence underground
     * read as a stop.
     */
    private fun DetectionEngineState.resumeDriving(atMillis: Long): EngineStep {
        val session = session ?: return moveTo(DetectionState.DRIVING, atMillis)
        return copy(
            session = session.copy(
                stop = null,
                lastMovementEvidenceAtMillis = session.lastMovementEvidenceAtMillis?.let { maxOf(it, atMillis) },
            ),
        ).moveTo(DetectionState.DRIVING, atMillis)
    }

    /** Back to `IDLE`, dropping the travel session — which is what re-arms §12's counter. */
    private fun DetectionEngineState.endSession(atMillis: Long, clearCandidate: Boolean = false): EngineStep =
        EngineStep(
            copy(
                state = DetectionState.IDLE,
                stateEnteredAtMillis = atMillis,
                session = null,
                candidate = if (clearCandidate) null else candidate,
                stopOnlyResumeWindow = null,
            ),
        ).withCheckpoint()

    private fun DetectionEngineState.expireCandidate(atMillis: Long, candidate: CandidateSnapshot): EngineStep =
        EngineStep(
            copy(
                state = DetectionState.IDLE,
                stateEnteredAtMillis = atMillis,
                session = null,
                candidate = null,
                stopOnlyResumeWindow = null,
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
     *
     * Contract §6 holds by construction rather than by a check here: the only callers are
     * `PARKING_TRANSITION`, reachable only from a promoted `DRIVING` (a meaningful vehicle
     * session), and the `DRIVING` disconnect row; both have recorded the stop (the session
     * ended), and each passes the confirming signal that brought it here.
     */
    private fun DetectionEngineState.openCandidateOrEndSession(atMillis: Long, signal: ConfirmingSignal): EngineStep {
        val session = session ?: return endSession(atMillis)
        if (session.candidateProduced) return endSession(atMillis)
        val stop = session.stop ?: VehicleStop(
            atMillis = atMillis,
            byMovementIdle = false,
            distanceMeters = session.evidence.travelDistanceMeters,
        )

        val durationMillis = stop.atMillis - session.evidence.vehicleFirstSeenAtMillis
        val inheritedLocation = lastReliableLocation?.takeIf { it.belongsToDrive(session, atMillis) }
        val reasons = candidateReasons(session, stop, signal, inheritedLocation)
        val score = ParkingConfidencePolicy.score(
            reasons = reasons,
            durationMillis = durationMillis,
            distanceMeters = stop.distanceMeters,
        )
        val confidence = ParkingConfidencePolicy.bucketOf(score)
        val id = newCandidateId()
        val snapshot = CandidateSnapshot(
            id = id,
            createdAtMillis = atMillis,
            expiresAtMillis = atMillis + ParkingCandidate.LIFETIME_MILLIS,
            confidence = confidence,
            score = score,
            reasons = reasons,
        )
        val next = copy(
            state = DetectionState.CANDIDATE_PENDING,
            stateEnteredAtMillis = atMillis,
            session = session.copy(candidateProduced = true),
            candidate = snapshot,
            candidatesCreated = candidatesCreated + 1,
            // §3a "A stop-only candidate can still be a long light": when nothing but absence
            // ended the drive, the car moving on can still take the candidate back until the
            // transition's own deadline.
            stopOnlyResumeWindow = if (stop.isStopOnly(signal)) {
                StopOnlyResumeWindow(
                    driveEndedAtMillis = stop.atMillis,
                    deadlineMillis = stop.atMillis + TRANSITION_WINDOW_MILLIS,
                )
            } else {
                null
            },
        )
        return EngineStep(
            next,
            // §10a: a new travel session's candidate retires the one an earlier trip left
            // unanswered. Contract §8 fixes the shape — withdraw-then-create, adjacent, as iOS
            // emits it — because the storm counter tells a supersession from a self-withdrawal
            // by exactly that adjacency.
            listOfNotNull(
                candidate?.let { DetectionEffect.RetireCandidate(it.id) },
                DetectionEffect.CreateCandidate(
                    candidateId = id,
                    confidence = confidence,
                    score = score,
                    reasons = reasons,
                    lastReliableLocation = inheritedLocation,
                    vehicleSessionDurationMillis = durationMillis,
                    travelDistanceMeters = stop.distanceMeters,
                    walkingEvidence = EvidenceReasonCode.WALKING_AFTER_VEHICLE in reasons,
                    gpsDegradation = EvidenceReasonCode.LOCATION_QUALITY_DEGRADED in reasons,
                    optionalVehicleSignal = EvidenceReasonCode.CAR_PROJECTION_DISCONNECTED in reasons,
                ),
            ),
        ).withCheckpoint()
    }

    /**
     * The §4 codes a candidate carries, from the facts the session recorded as they arrived.
     *
     * In §4's own order, which is iOS's `reasonCodes(for:)` order; the parity fields compare
     * them as a set. Each line is the single cross-platform meaning of its code:
     *
     * - `recent_vehicle_activity` — a meaningful vehicle session (§6); every caller follows a
     *   promoted `DRIVING`.
     * - `vehicle_duration_met` / `vehicle_distance_met` — §7's bars, measured from the first
     *   vehicle evidence **to the stop**, never to whatever event arrived after it.
     * - `vehicle_exit_detected` — an explicit exit ended this drive: the transition was
     *   entered by `vehicle_exit` or a link disconnect, or one arrived while it was open.
     *   `movementIdleWindow` alone is not one — it earns `location_stopped` instead.
     * - `walking_after_vehicle` / `stationary_after_vehicle` — the signal that confirmed the
     *   transition.
     * - `location_stopped` — the stop came from `movementIdleWindow`, or the newest fix that
     *   *reported* a stopped speed is later than the drive's last movement and no earlier
     *   than [NEAR_END_HORIZON_MILLIS] before the stop. A speedless fix never counts.
     * - `location_quality_degraded` — the quality fell into `poor` no earlier than
     *   [NEAR_END_HORIZON_MILLIS] before the stop, or since.
     * - `reliable_location_captured` — the candidate actually carries a location (§5).
     * - `car_projection_disconnected` — a link disconnect ended the drive or confirmed the
     *   transition. Either way it is the signal that created the candidate: a disconnect in
     *   `DRIVING` skips the transition, and one inside it confirms at once.
     *
     * These are docs/05 §8b's definitions, which iOS's `scoredEvidence(for:)` implements.
     */
    private fun candidateReasons(
        session: TravelSession,
        stop: VehicleStop,
        signal: ConfirmingSignal,
        inheritedLocation: ReliableLocation?,
    ): List<EvidenceReasonCode> = buildList {
        add(EvidenceReasonCode.RECENT_VEHICLE_ACTIVITY)
        if (stop.atMillis - session.evidence.vehicleFirstSeenAtMillis >= DrivingConfirmationGuard.MIN_DURATION_MILLIS) {
            add(EvidenceReasonCode.VEHICLE_DURATION_MET)
        }
        if (stop.distanceMeters >= DrivingConfirmationGuard.MIN_DISTANCE_METERS) {
            add(EvidenceReasonCode.VEHICLE_DISTANCE_MET)
        }
        if (stop.exitDetected || signal == ConfirmingSignal.CAR_LINK_DISCONNECT) {
            add(EvidenceReasonCode.VEHICLE_EXIT_DETECTED)
        }
        if (signal == ConfirmingSignal.WALKING) add(EvidenceReasonCode.WALKING_AFTER_VEHICLE)
        if (signal == ConfirmingSignal.STATIONARY) add(EvidenceReasonCode.STATIONARY_AFTER_VEHICLE)
        if (stop.byMovementIdle || session.stoppedNearEnd(stop.atMillis)) add(EvidenceReasonCode.LOCATION_STOPPED)
        if (session.degradedNearEnd(stop.atMillis)) add(EvidenceReasonCode.LOCATION_QUALITY_DEGRADED)
        if (inheritedLocation != null) add(EvidenceReasonCode.RELIABLE_LOCATION_CAPTURED)
        if (signal == ConfirmingSignal.CAR_LINK_DISCONNECT) add(EvidenceReasonCode.CAR_PROJECTION_DISCONNECTED)
    }

    /**
     * §8b `location_stopped`'s location half: a reported stop after the drive's last movement
     * and no earlier than [NEAR_END_HORIZON_MILLIS] before the end. A drive with no movement
     * at all qualifies on the stop alone, exactly as iOS's `stoppedNearEnd(endedAt:)` reads it.
     */
    private fun TravelSession.stoppedNearEnd(endMillis: Long): Boolean {
        val stopped = lastStoppedFixAtMillis ?: return false
        val lastMovement = lastMovementEvidenceAtMillis
        if (lastMovement != null && stopped <= lastMovement) return false
        return stopped >= endMillis - NEAR_END_HORIZON_MILLIS
    }

    /** §8b `location_quality_degraded`: a fall into `poor` no earlier than the horizon, or since. */
    private fun TravelSession.degradedNearEnd(endMillis: Long): Boolean {
        val degraded = lastDegradedToPoorAtMillis ?: return false
        return degraded >= endMillis - NEAR_END_HORIZON_MILLIS
    }

    /** §14: a checkpoint accompanies every transition, so a process death mid-trip resumes. */
    private fun EngineStep.withCheckpoint(): EngineStep = copy(effects = effects + state.checkpointEffect())

    /**
     * The checkpoint as the engine sees it. [DetectionCheckpoint.revision] is left at 1
     * here and rewritten against the stored value by
     * [com.sjstudioz.parkingpin.detection.ParkingDetectionRuntime] — the counter belongs to the
     * store that serializes writers, not to a pure function that cannot read one.
     */
    private fun DetectionEngineState.checkpointEffect(): DetectionEffect =
        DetectionEffect.PersistCheckpoint(toCheckpoint())

    /**
     * §5 "The fix a candidate inherits must belong to the drive that just ended".
     *
     * Both conditions, because they catch different things. The session bound refuses a fix
     * from a previous trip or from the origin of this one — the origin is not the
     * destination. It is the journey's first vehicle evidence, which a red-light resume does
     * not move: a fix taken before the stop is still this drive's (iOS bounds by the same
     * drive start). The age bound refuses a long drive's only good fix when it came near the
     * start, because being on the motorway at minute two says nothing about minute ninety.
     *
     * Measured on 2026-09-20: without this, a candidate created at 17:32 carried a fix from
     * 12:00, and the confirmation screen would have drawn it on a map with its accuracy
     * printed beside it. A wrong coordinate is worse than none; `위치 없음` is a state that
     * screen already renders properly.
     */
    private fun ReliableLocation.belongsToDrive(session: TravelSession, atMillis: Long): Boolean =
        capturedAtMillis >= session.evidence.vehicleFirstSeenAtMillis &&
            atMillis - capturedAtMillis <= STALE_LOCATION_WINDOW_MILLIS

    private fun TravelSession.hasSustainedVehicleActivity(atMillis: Long): Boolean {
        val since = vehicleActiveSinceMillis ?: return false
        return vehicleEndedAtMillis == null && atMillis - since >= MINIMUM_VEHICLE_DURATION_MILLIS
    }

    /**
     * The fix *reported* a speed below §7's movement threshold. A fix with no speed says
     * nothing: underground there is no Doppler, and silence is not stillness (§7).
     */
    private fun LocationSample.reportsStopped(): Boolean {
        val speed = speedMps ?: return false
        return quality.isValid && speed < DrivingConfirmationGuard.MOVING_SPEED_THRESHOLD_MPS
    }

    /** The fix *reported* a speed at or above §7's movement threshold. A missing speed is not movement. */
    private fun LocationSample.reportsMoving(): Boolean {
        val speed = speedMps ?: return false
        return quality.isValid && speed >= DrivingConfirmationGuard.MOVING_SPEED_THRESHOLD_MPS
    }

    /**
     * docs/05 §3a: a candidate nothing but absence produced — the transition was entered by
     * `movementIdle`, no exit (explicit or a link disconnect) arrived, and the confirming signal
     * was a location stop or `stationary_enter`, not a walk. The vehicle level never ended, so
     * the car moving on is this drive continuing. A walk is excluded because it is the strongest
     * evidence the person left the car. iOS's `isStopOnly`.
     */
    private fun VehicleStop.isStopOnly(signal: ConfirmingSignal): Boolean =
        byMovementIdle && !exitDetected &&
            (signal == ConfirmingSignal.LOCATION_STOP || signal == ConfirmingSignal.STATIONARY)

    /**
     * §8b's fix-derived degradation: an accepted fix in `poor` whose accepted predecessor was
     * `good` or `fair`. The same test iOS's `DrivingEvidence.noteQualityDrop` makes.
     */
    private fun fallsIntoPoor(previous: MovementAnchor?, fix: LocationSample): Boolean {
        val before = previous?.let { LocationQualityBucket.of(it.horizontalAccuracyM) } ?: return false
        return before != LocationQualityBucket.POOR &&
            LocationQualityBucket.of(fix.horizontalAccuracyM) == LocationQualityBucket.POOR
    }

    /** Records a fall into `poor`, keeping the newest one when drops arrive out of order. */
    private fun TravelSession.notingFallIntoPoor(atMillis: Long): TravelSession =
        copy(lastDegradedToPoorAtMillis = maxOf(lastDegradedToPoorAtMillis ?: atMillis, atMillis))

    companion object {

        /**
         * Enough to walk `DRIVING_CANDIDATE -> DRIVING -> PARKING_TRANSITION -> IDLE` and
         * stop. Matches the iOS engine's cascade cap, which is the same number for the same
         * reason.
         */
        private const val MAX_TIMEOUT_CASCADE: Int = 6

        /**
         * §3a's hard ceiling on one driving session, 2 h.
         *
         * Longer than any ordinary commute and far shorter than a day. iOS has always had
         * it (`DrivingSessionTimeoutPolicy.maximumDuration`); Android did not, which was
         * invisible while no timeout row fired at all.
         */
        const val SESSION_MAXIMUM_DURATION_MILLIS: Long = 2 * 60 * 60 * 1000L

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

        /**
         * §3a constant `nearEndHorizon`, 300 s. **unvalidated.**
         *
         * §8b: how far before the drive's end "near end" evidence — `location_stopped`'s
         * reported stop and `location_quality_degraded`'s fall into `poor` — may lie.
         * `transitionWindow` reused, the same budget §5 gives the descent into a garage.
         */
        const val NEAR_END_HORIZON_MILLIS: Long = TRANSITION_WINDOW_MILLIS

        /** §11 departure: movement >= 500 m alongside the 90 s vehicle bar. */
        const val DEPARTURE_MOVEMENT_METERS: Double = 500.0
    }
}

/** What confirmed a stop as a parking — §3a's confirming signals and the link disconnect. */
private enum class ConfirmingSignal { WALKING, STATIONARY, LOCATION_STOP, CAR_LINK_DISCONNECT }

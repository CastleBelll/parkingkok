package com.sjstudioz.parkingpin.detection

import android.util.Log
import com.sjstudioz.parkingpin.analytics.DetectionProperties
import com.sjstudioz.parkingpin.analytics.DistanceBucket
import com.sjstudioz.parkingpin.analytics.DriveDurationBucket
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.domain.detection.DetectionEffect
import com.sjstudioz.parkingpin.domain.detection.DetectionEngineState
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.location.LocationCaptureModePolicy
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.location.LocationSessionMode
import com.sjstudioz.parkingpin.domain.trace.LocationQualityBucket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The application layer around [ParkingDetectionEngine]: restore, feed, persist, act.
 *
 * This is docs/05_PARKING_DETECTION_ENGINE.md §16's Android shape — `restore(checkpoint)`
 * plus `handle(event): List<DetectionEffect>`, with the "actor-like isolation" §16 asks for
 * provided by a [Mutex]. A location batch, a transition broadcast and a Bluetooth ACL
 * broadcast all reach the same process from different receivers, and without it two of them
 * could each read the same state and each decide to create the same candidate.
 *
 * ### Why the engine is not this class
 * Everything that decides is a pure reducer one layer down. That is what lets
 * the JSON fixtures in `platform-tests` be replayed against it on the JVM with no
 * DataStore, no Play services and no clock — which is the only mechanism this project has
 * for showing the two platforms agree.
 *
 * ### Ordering: effects first, then persist
 * A crash between the two must not leave a checkpoint naming a candidate that was never
 * stored — the notification would name a candidate the confirmation screen cannot find.
 * Running the effect first means a crash instead leaves an orphaned candidate that the next
 * `create` supersedes by §10a's own rule, which is the recoverable failure of the two.
 *
 * ### The coordinator is a provider, not a value
 * Constructing [ParkingCandidateCoordinator] pulls in the analytics recorder, and that
 * spins up AppMeasurement. Most events this runtime handles produce no candidate effect at
 * all, and it runs in whatever process a broadcast happened to start — the same bargain
 * [com.sjstudioz.parkingpin.AppContainer] makes for Room and for photos. Only an effect that
 * actually reaches the user calls it.
 *
 * ### What it does not own
 * The Fused Location request. [FusedLocationSessionController] owns it, and two owners of one
 * registration is what leaks a session. The runtime only *reports* what the engine wants of
 * it after every batch ([followLocationCapture]) — docs/05 §19 "after every event batch the
 * adapter releases any capture the engine no longer wants", which also covers the edges no
 * motion event marks — and a hand save ([handleUserSavedParking]) asks for a stop.
 *
 * ### The stop-only resume window lives exactly as long as its capture
 * docs/05 §3a (round 4). The window is stored with the engine state, because the capture it
 * holds is a Play services `PendingIntent` that outlives this process, and every broadcast
 * reloads the state. What must not outlive the capture is the window: before any batch —
 * and before the capture question a motion event asks — a window whose capture is gone
 * ([captureRunning] false: revoked permission, an expired or failed request) is closed, the
 * candidate kept. That is rule 4's lost capture, and the state §19 forbids ("still holds the
 * capture") never reaches the engine or the follow. A reboot, force-stop or app update is the
 * one exception (docs/05 §14 "The capture did not survive"): the system dropped the capture,
 * so [resumeAfterSystemReset] reopens it and the window rides on the reopened capture, as it
 * does on every iOS relaunch; it closes only when that capture cannot be reopened.
 */
class ParkingDetectionRuntime(
    private val store: DetectionStateStore,
    private val candidates: () -> ParkingCandidateCoordinator,
    private val engine: ParkingDetectionEngine = ParkingDetectionEngine { UUID.randomUUID().toString() },
    /**
     * §11a: a confirmed departure asks whether the open parking ended — it never closes it
     * (DECIDED 2026-09-29). Called with the departure time; a provider-shaped hook for the
     * reason [candidates] is one: most events this runtime handles never touch Room.
     *
     * Null in the tests that only care about state transitions; a departure then moves the
     * machine and asks nothing.
     */
    private val proposeParkingEnd: (suspend (departedAtMillis: Long) -> Unit)? = null,
    /**
     * Ends the bounded Fused Location capture when the user saves a parking by hand
     * (docs/05 §11c). Null in the tests that only care about state transitions, and in a
     * build with no location session: the save then parks the machine and stops nothing.
     */
    private val stopLocationCapture: (suspend () -> Unit)? = null,
    /**
     * Told what the engine wants of the capture after every batch — before, then after, each
     * null for none (docs/05 §3a / §19; see
     * [LocationCaptureModePolicy.modeWantedBy]). Null in the tests that only care about
     * state transitions, and in a build with no location session.
     */
    private val followLocationCapture: (suspend (LocationSessionMode?, LocationSessionMode?) -> Unit)? = null,
    /**
     * Whether the bounded capture is actually running now (docs/05 §3a "The window lives
     * exactly as long as its capture"). Null in the tests that only care about state
     * transitions, and in a build with no location session: the window is then taken to hold
     * its capture, which is what the engine alone assumes.
     */
    private val captureRunning: (suspend () -> Boolean)? = null,
    /**
     * Whether the user has Smart Detection switched on (docs/05 §19: "nothing may open a
     * capture the user switched off"). Null in the tests that only care about state
     * transitions: detection is then always on, which is what the engine alone assumes.
     */
    private val detectionEnabled: (suspend () -> Boolean)? = null,
) {

    private val mutex = Mutex()

    /**
     * Previews an event for the capture decision, and nothing else: its state is discarded,
     * so the candidate id it would mint does not matter and must not consume one of [engine]'s.
     */
    private val previewEngine = ParkingDetectionEngine { PREVIEW_CANDIDATE_ID }

    /**
     * §16 `restore`. Reads what the last process left, or starts from `IDLE`.
     *
     * Exposed so process start and boot can see the state machine's own view without
     * feeding it an event — a restart mid-drive has to know it was driving before the next
     * fix arrives, or the fix opens a new session and the trip is split in two.
     */
    suspend fun restore(): DetectionEngineState = mutex.withLock { current() }

    /**
     * The capture the engine would want once it has taken [event] — settled against the
     * event's own timestamp, exactly as [handleMotion] will settle it — without taking it.
     *
     * docs/05 §19 "a motion event the engine did not act on opens nothing": the stored state
     * is not the engine's view. A `DRIVING_CANDIDATE` whose window lapsed, or a stop-only
     * window whose deadline passed or that this very exit closes, still reads as wanting a
     * capture there, and a kerb capture opened on that answer is 300 s of HIGH accuracy the
     * engine drops fix by fix. Asking after the event is what the capture actually serves.
     */
    suspend fun captureAfter(event: MotionDomainEvent): MotionCaptureAnswer = mutex.withLock {
        if (!isDetectionEnabled()) return@withLock MotionCaptureAnswer(wanted = null, continuesSession = false)
        val before = current()
        val after = previewEngine.handle(before, event.toDetectionEvent()).state
        MotionCaptureAnswer(
            wanted = LocationCaptureModePolicy.modeWantedBy(after),
            continuesSession = after.continuesSessionOf(before),
        )
    }

    /**
     * Whether Smart Detection is on. Every sensor entry point below reads it, so a transition,
     * a fix or a car-link edge that arrives after the opt-out feeds nothing and opens nothing.
     */
    suspend fun isDetectionEnabled(): Boolean = detectionEnabled?.invoke() ?: true

    /** One normalized motion transition (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2). */
    suspend fun handleMotion(event: MotionDomainEvent): List<DetectionEffect> =
        handle(listOfNotNull(event.toDetectionEvent()), sensor = true)

    /** One Fused Location delivery, oldest fix first so the engine sees the drive in order. */
    suspend fun handleLocations(samples: List<LocationSample>): List<DetectionEffect> =
        handle(samples.sortedBy { it.atMillis }.map(DetectionEvent::Location), sensor = true)

    /**
     * §3a "The car link". [CarLinkReceiver] is the only caller on this platform. Gated on
     * Smart Detection like every sensor entry: a car connect while the user has detection off
     * would otherwise open a capture and the location foreground service on every drive.
     */
    suspend fun handleCarLink(event: DetectionEvent): List<DetectionEffect> = handle(listOf(event), sensor = true)

    /**
     * The user switched Smart Detection off (docs/05 §11 / §19). Called by
     * [DetectionRegistrationCoordinator.setDetectionEnabled] once the flag is already off, so
     * no sensor batch can reopen what this closes, and again by every reconcile while it is
     * off — the relaunch that ends what a process dying mid-opt-out left behind (§3a).
     *
     * The engine drops whatever it was inferring — iOS's `.smartDetectionDisabled`, the same
     * resulting state — and the capture is stopped outright, as a hand save stops it: the
     * follow already releases what the engine wanted, and the stop covers anything else a
     * drive had open. Not gated: it is what the gate is for.
     */
    suspend fun handleSmartDetectionDisabled(atMillis: Long): List<DetectionEffect> {
        val event = DetectionEvent.SmartDetectionDisabled(atMillis)
        // Idempotent, because every relaunch while opted out runs it again (docs/05 §3a: it
        // ends whatever a process that died mid-opt-out left behind): a state the opt-out
        // would leave as it is is not rewritten. The capture is stopped either way — a
        // Play services request outlives the process that asked for it.
        val changes = mutex.withLock {
            val stored = storedState()
            previewEngine.handle(current(), event).state != stored
        }
        val effects = if (changes) handle(listOf(event)) else emptyList()
        stopLocationCapture?.invoke()
        return effects
    }

    /** The user answered the prompt. Fed back so the state machine leaves `CANDIDATE_PENDING`. */
    suspend fun handleUserAnswer(event: DetectionEvent): List<DetectionEffect> = handle(listOf(event))

    /**
     * The user saved a parking themselves, not by answering a prompt (docs/05 §11c).
     *
     * Its own entry rather than [handleUserAnswer], because it is the one user event that
     * also has to reach the location capture. The engine moves to `PARKED` — which is what
     * lets §11 end this parking on the next drive away — and the capture a drive may have
     * started is stopped, since the question it was gathering fixes for has been answered.
     * The capture is stopped after the engine, outside the lock: it has a lock of its own,
     * and a failure there must not cost the state machine its `PARKED`.
     */
    suspend fun handleUserSavedParking(atMillis: Long): List<DetectionEffect> =
        handleParkedByUser(DetectionEvent.UserSavedParking(atMillis))

    /**
     * `아직 주차 중` answered a departure proposal (docs/05 §11a, contract §2
     * `user_kept_parking`). The engine returns to `PARKED` and drops the drive the departure
     * opened, exactly as a hand save does, so the capture that drive started is stopped too.
     */
    suspend fun handleUserKeptParking(atMillis: Long): List<DetectionEffect> =
        handleParkedByUser(DetectionEvent.UserKeptParking(atMillis))

    private suspend fun handleParkedByUser(event: DetectionEvent): List<DetectionEffect> {
        // The follow already releases a capture the engine had asked for; the stop is for one
        // it had not, since the answer settles whatever was gathering fixes for it.
        val effects = handle(listOf(event))
        stopLocationCapture?.invoke()
        return effects
    }

    /**
     * Nothing happened, and that is the point (docs/05 §3a).
     *
     * The engine settles §3a's elapsed-time rows against each event's own timestamp, so on a
     * phone that keeps producing events — a fix, a transition, a link edge — the timeouts
     * take care of themselves. A phone that produces **none** is the case this exists for: a
     * drive that ends underground with no `vehicle_exit`, no fixes because there is no sky,
     * and no walk transition delivered. The session stays open, and with it the location
     * foreground service.
     *
     * [DrivingLocationService] is the only caller, which is the whole design: it is alive
     * exactly while a bounded capture is, so the tick exists precisely while the silence
     * would cost something, and a parked phone ticks never.
     */
    suspend fun handleTick(atMillis: Long): List<DetectionEffect> =
        handle(listOf(DetectionEvent.TimerTick(atMillis)), sensor = true)

    /**
     * The first batch after a reboot or an app update (docs/05 §14), sent by
     * [RegistrationRecoveryReceiver] once the dropped registrations are cleared.
     *
     * A tick at [atMillis] settles §3a's windows exactly as iOS's `restore(_:now:)` does — a
     * departure already past §11's lapse returns to `PARKED` — and the follow that ends every
     * batch reopens the bounded capture the engine still wants, which the reboot took with it.
     * Without it a restored `DEPARTURE_CANDIDATE` got no fixes until some motion edge happened
     * to arrive. A session the session timeout already ends is dropped first
     * ([ParkingDetectionEngine.dropsStaleSession], iOS `settleRelaunch`): a `PARKED` get-in
     * whose vehicle evidence went silent long ago keeps the parking, and a stale
     * `DRIVING_CANDIDATE` / `DRIVING` ends in `IDLE` — so the reset reopens no capture for either.
     *
     * A stop-only resume window is carried through the reset (docs/05 §3a "The window lives
     * exactly as long as its capture", §14 step 3), as iOS's relaunch carries it: the capture
     * the system dropped is one to reopen, not one that failed, so the batch starts from the
     * stored window rather than [current]'s lost-capture close. The tick settles a window
     * that lapsed while the phone was down, the follow reopens the capture an open one wants,
     * and only a window whose capture could not be reopened (a revoked or "only while using"
     * permission) is closed afterwards, the candidate kept.
     */
    suspend fun resumeAfterSystemReset(atMillis: Long): List<DetectionEffect> =
        handle(
            listOf(DetectionEvent.TimerTick(atMillis)),
            sensor = true,
            reopensDroppedCapture = true,
        ) { engine.dropsStaleSession(it, atMillis) }

    /**
     * One batch of events, in order, under the lock.
     *
     * **§3a's timeout rows need no caller here.** They used to: they fired only on an
     * explicit `TimerTick`, nothing in the app produced one, and `drivingCandidateWindow`,
     * `movementIdleWindow` and `transitionWindow` were dead in the shipped build — a drive
     * that ended with no `vehicle_exit` stayed in `DRIVING` for ever. The engine now settles
     * them against each event's own timestamp, after folding that event's evidence, which is
     * the ordering iOS has always used and the one the shared fixtures agree with.
     *
     * What is left is the phone that produces **no event at all** after a drive ends. The
     * 45-minute candidate expiry is covered from the app side by
     * [ParkingCandidateCoordinator.expireIfDue] and the notification's own `setTimeoutAfter`;
     * the driving rows are not, and a scheduled tick is the remaining half (docs/05 §3a).
     */
    private suspend fun handle(
        events: List<DetectionEvent>,
        /**
         * A sensor batch (motion, fix, car link, tick), which Smart Detection being off
         * discards. User events and the opt-out itself are never gated.
         */
        sensor: Boolean = false,
        /**
         * The system dropped the capture and this batch's follow reopens what the engine still
         * wants: a stop-only window is kept for it, and closed after the follow only if the
         * capture could not be reopened ([closeWindowWithoutCapture]).
         */
        reopensDroppedCapture: Boolean = false,
        /** What the stored state must become before the batch — a system reset's cleanup. */
        prepare: (DetectionEngineState) -> DetectionEngineState = { it },
    ): List<DetectionEffect> {
        if (events.isEmpty()) return emptyList()
        var wantedBefore: LocationSessionMode? = null
        var wantedAfter: LocationSessionMode? = null
        var windowOpenAfter = false
        val effects = mutex.withLock {
            val stored = if (reopensDroppedCapture) storedState() else current()
            // The want before the cleanup: a get-in dropped here is a capture given up, and
            // the follow releases whatever of it is still running.
            wantedBefore = LocationCaptureModePolicy.modeWantedBy(stored)
            // Read under the lock, so a batch either runs wholly before the opt-out's own
            // batch or sees the flag off. Off: the engine is not fed, and the follow below
            // releases any capture still running rather than trusting the stored want.
            if (sensor && !isDetectionEnabled()) {
                Log.i(TAG, "sensor batch ignored: smart detection off")
                return@withLock emptyList()
            }
            var state = prepare(stored)
            val effects = mutableListOf<DetectionEffect>()
            for (event in events) {
                val step = engine.handle(state, event)
                state = step.state
                effects += step.effects
            }
            // State names only. No coordinate exists on this path.
            Log.i(TAG, "engine -> ${state.state} effects=${effects.size}")
            apply(effects)
            store.writeEngineStateAndCheckpoint(state, state.toCheckpoint())
            wantedAfter = LocationCaptureModePolicy.modeWantedBy(state)
            windowOpenAfter = state.stopOnlyResumeWindow != null
            effects
        }
        // Outside the lock, after the state is durable, as the hand save's stop is: the
        // capture has a lock of its own, and a failure there must not cost the engine its
        // transition. Every batch, not only when the want changed (docs/05 §19): a capture
        // whose owner went away with no state change has no edge to be released on. Asked
        // again here because the opt-out may have landed since the lock was released: a want
        // read before it must not reopen what it stopped.
        if (!isDetectionEnabled()) wantedAfter = null
        followLocationCapture?.invoke(wantedBefore, wantedAfter)
        if (windowOpenAfter) closeWindowWithoutCapture(wantedAfter)
        return effects
    }

    /**
     * docs/05 §3a "The window lives exactly as long as its capture", made durable the moment
     * the follow shows the capture is not there: a stop-only candidate whose transition had no
     * capture (a drive that lost it, docs/05 §11 "A lost capture stays lost for its session")
     * opens no window. Closing it only at the next batch's [current] is too late on Android:
     * `TransitionEventIngestor` shapes the capture before the engine takes the event, so the
     * `vehicle_enter` of a new journey opens a capture first and would find the window it
     * never held looking backed by one — resuming `DRIVING` and withdrawing the candidate,
     * where iOS keeps it.
     */
    private suspend fun closeWindowWithoutCapture(wanted: LocationSessionMode?) {
        val holdsCapture = captureRunning ?: return
        val closed = mutex.withLock {
            val stored = store.readEngineStateOnce() ?: return@withLock false
            if (stored.stopOnlyResumeWindow == null || holdsCapture()) return@withLock false
            val withoutWindow = stored.copy(stopOnlyResumeWindow = null)
            store.writeEngineStateAndCheckpoint(withoutWindow, withoutWindow.toCheckpoint())
            Log.i(TAG, "stop-only window closed: no capture holds it")
            true
        }
        // Whatever of a capture is left (a record whose background permission went) is no
        // longer the engine's, as the next batch's follow would say.
        if (closed) followLocationCapture?.invoke(wanted, null)
    }

    /**
     * The stored state, less a stop-only window whose capture is gone (docs/05 §3a rule 4's
     * lost capture: the window closes, the candidate stays). Read at the start of every batch
     * and every capture question, so a new process handles nothing against a window that no
     * longer holds its capture; the closure is written with the batch's own state.
     */
    private suspend fun current(): DetectionEngineState {
        val stored = storedState()
        if (stored.stopOnlyResumeWindow == null) return stored
        val holdsCapture = captureRunning?.invoke() ?: true
        return if (holdsCapture) stored else stored.copy(stopOnlyResumeWindow = null)
    }

    /** The stored state exactly as the last batch wrote it, or `IDLE` for a first launch. */
    private suspend fun storedState(): DetectionEngineState = store.readEngineStateOnce() ?: DetectionEngineState()

    private suspend fun apply(effects: List<DetectionEffect>) {
        for (effect in effects) {
            when (effect) {
                is DetectionEffect.CreateCandidate -> candidates().create(
                    evidence = effect.toDetectionProperties(),
                    lastReliableLocation = effect.lastReliableLocation,
                    candidateId = effect.candidateId,
                )

                is DetectionEffect.RetireCandidate -> candidates().retire(effect.candidateId)

                // The record is written by the confirmation flow, which is the only thing
                // that holds the floor the user typed. Reaching `PARKED` is the engine's
                // half and it is already in the checkpoint written below.
                is DetectionEffect.MarkParkingActive -> Unit

                // §11a departure: asked, never ended here. The record is closed only by the
                // user's answer or by the next parking, at the time carried here.
                is DetectionEffect.ProposeParkingEnd -> proposeParkingEnd?.invoke(effect.departedAtMillis)

                // Written together with the engine state at the end of the batch, not once
                // per effect: §14 wants a durable checkpoint after a transition, not a file
                // rewrite per event in a batch of forty fixes.
                is DetectionEffect.PersistCheckpoint -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "PkDetection"
        const val PREVIEW_CANDIDATE_ID = "capture-preview"
    }
}

/**
 * What the engine would make of a motion event, for the capture it shapes before the engine
 * takes it ([ParkingDetectionRuntime.captureAfter]).
 */
data class MotionCaptureAnswer(
    /** The capture the engine wants once it has taken the event, or null for none. */
    val wanted: LocationSessionMode?,
    /**
     * Whether the engine is still on the session it was on before the event — the one a lost
     * capture belongs to (docs/05 §11 "A lost capture stays lost for its session"). False when
     * the event opens a new journey or ends the session.
     */
    val continuesSession: Boolean,
)

/**
 * The same travel session on both sides: a session's identity is when its journey began,
 * which extending it keeps and a new journey replaces (docs/05 §3a).
 */
private fun DetectionEngineState.continuesSessionOf(before: DetectionEngineState): Boolean {
    val previous = before.session ?: return false
    val next = session ?: return false
    return next.evidence.vehicleFirstSeenAtMillis == previous.evidence.vehicleFirstSeenAtMillis
}

/**
 * The §2 event this platform's motion transition means.
 *
 * `null` for a transition with no product meaning, which is the same filter
 * [com.sjstudioz.parkingpin.domain.detection.ActivityTransitionMapper] already applies one layer
 * out — kept here as a total `when` so a new [MotionEventKind] cannot be forgotten.
 */
private fun MotionDomainEvent.toDetectionEvent(): DetectionEvent = when (kind) {
    MotionEventKind.ENTERED_VEHICLE -> DetectionEvent.VehicleEnter(atMillis)
    MotionEventKind.EXITED_VEHICLE -> DetectionEvent.VehicleExit(atMillis)
    MotionEventKind.STARTED_WALKING -> DetectionEvent.WalkingEnter(atMillis)
    MotionEventKind.BECAME_STATIONARY -> DetectionEvent.StationaryEnter(atMillis)
    MotionEventKind.STOPPED_BEING_STATIONARY -> DetectionEvent.StationaryExit(atMillis)
}

/**
 * Everything docs/17 §3 lets the three `parking_candidate_*` events say, and nothing else.
 *
 * The reason codes are deliberately **not** copied into it. [DetectionProperties] has no
 * field for them and adding one would put a list of strings into an analytics payload that
 * docs/17 §3 fixes; the codes travel with the candidate locally, where tuning reads them.
 */
private fun DetectionEffect.CreateCandidate.toDetectionProperties(): DetectionProperties = DetectionProperties(
    confidenceBucket = confidence,
    driveDurationBucket = DriveDurationBucket.of(vehicleSessionDurationMillis),
    distanceBucket = DistanceBucket.of(travelDistanceMeters),
    accuracyBucket = lastReliableLocation?.let { LocationQualityBucket.of(it.horizontalAccuracyM) },
    walkingEvidence = walkingEvidence,
    gpsDegradation = gpsDegradation,
    optionalVehicleSignal = optionalVehicleSignal,
)

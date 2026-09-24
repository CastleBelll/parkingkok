package com.sjstudioz.parkingpin.detection

import android.util.Log
import com.sjstudioz.parkingpin.analytics.AnalyticsEvent
import com.sjstudioz.parkingpin.analytics.AnalyticsRecording
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
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
import com.sjstudioz.parkingpin.domain.location.LocationSample
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
 * The Fused Location request. [com.sjstudioz.parkingpin.domain.location.LocationCaptureModePolicy]
 * and [FusedLocationSessionController] already decide that from the same motion events, and
 * two owners of one registration is what leaks a session. The one exception is a stop, never
 * a start: a hand save ([handleUserSavedParking]) asks the controller to end its capture.
 */
class ParkingDetectionRuntime(
    private val store: DetectionStateStore,
    private val candidates: () -> ParkingCandidateCoordinator,
    private val engine: ParkingDetectionEngine = ParkingDetectionEngine { UUID.randomUUID().toString() },
    /**
     * §11 departure closes the open record. A provider for the same reason [candidates] is
     * one: most events this runtime handles never touch Room, and it runs in whatever
     * process a broadcast happened to start.
     *
     * Null in the tests that only care about state transitions; a departure then moves the
     * machine and closes nothing, which is exactly what a build without a record store
     * should do.
     */
    private val endParking: (suspend (Long) -> ParkingRecord?)? = null,
    private val analytics: (() -> AnalyticsRecording)? = null,
    /**
     * Ends the bounded Fused Location capture when the user saves a parking by hand
     * (docs/05 §11c). Null in the tests that only care about state transitions, and in a
     * build with no location session: the save then parks the machine and stops nothing.
     */
    private val stopLocationCapture: (suspend () -> Unit)? = null,
) {

    private val mutex = Mutex()

    /**
     * §16 `restore`. Reads what the last process left, or starts from `IDLE`.
     *
     * Exposed so process start and boot can see the state machine's own view without
     * feeding it an event — a restart mid-drive has to know it was driving before the next
     * fix arrives, or the fix opens a new session and the trip is split in two.
     */
    suspend fun restore(): DetectionEngineState = mutex.withLock { current() }

    /** One normalized motion transition (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2). */
    suspend fun handleMotion(event: MotionDomainEvent): List<DetectionEffect> =
        handle(listOfNotNull(event.toDetectionEvent()))

    /** One Fused Location delivery, oldest fix first so the engine sees the drive in order. */
    suspend fun handleLocations(samples: List<LocationSample>): List<DetectionEffect> =
        handle(samples.sortedBy { it.atMillis }.map(DetectionEvent::Location))

    /** §3a "The car link". [CarLinkReceiver] is the only caller on this platform. */
    suspend fun handleCarLink(event: DetectionEvent): List<DetectionEffect> = handle(listOf(event))

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
    suspend fun handleUserSavedParking(atMillis: Long): List<DetectionEffect> {
        val effects = handle(listOf(DetectionEvent.UserSavedParking(atMillis)))
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
        handle(listOf(DetectionEvent.TimerTick(atMillis)))

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
    private suspend fun handle(events: List<DetectionEvent>): List<DetectionEffect> {
        if (events.isEmpty()) return emptyList()
        return mutex.withLock {
            var state = current()
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
            effects
        }
    }

    private suspend fun current(): DetectionEngineState = store.readEngineStateOnce() ?: DetectionEngineState()

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

                // §11 departure. The record is closed at the moment the car pulled away,
                // and the report goes out only if a record was actually open — a departure
                // detected for a parking the user already ended by hand is not an auto-end.
                is DetectionEffect.EndActiveParking ->
                    if (endParking?.invoke(effect.endedAtMillis) != null) {
                        analytics?.invoke()?.record(AnalyticsEvent.ParkingAutoEnd)
                    }

                // Written together with the engine state at the end of the batch, not once
                // per effect: §14 wants a durable checkpoint after a transition, not a file
                // rewrite per event in a batch of forty fixes.
                is DetectionEffect.PersistCheckpoint -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "PkDetection"
    }
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

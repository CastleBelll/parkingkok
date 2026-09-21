package com.parkingpin.app.detection

import android.util.Log
import com.parkingpin.app.analytics.AnalyticsEvent
import com.parkingpin.app.analytics.AnalyticsRecording
import com.parkingpin.app.domain.parking.ParkingRecord
import com.parkingpin.app.analytics.DetectionProperties
import com.parkingpin.app.analytics.DistanceBucket
import com.parkingpin.app.analytics.DriveDurationBucket
import com.parkingpin.app.data.DetectionStateStore
import com.parkingpin.app.domain.detection.DetectionEffect
import com.parkingpin.app.domain.detection.DetectionEngineState
import com.parkingpin.app.domain.detection.DetectionEvent
import com.parkingpin.app.domain.detection.MotionDomainEvent
import com.parkingpin.app.domain.detection.MotionEventKind
import com.parkingpin.app.domain.detection.ParkingDetectionEngine
import com.parkingpin.app.domain.location.LocationSample
import com.parkingpin.app.domain.trace.LocationQualityBucket
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
 * [com.parkingpin.app.AppContainer] makes for Room and for photos. Only an effect that
 * actually reaches the user calls it.
 *
 * ### What it does not own
 * The Fused Location request. [com.parkingpin.app.domain.location.LocationCaptureModePolicy]
 * and [FusedLocationSessionController] already decide that from the same motion events, and
 * two owners of one registration is what leaks a session.
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
     * A timeout moment.
     *
     * §3a's four timeout rows fire from here and nowhere else — see [DetectionEvent.TimerTick]
     * for why an ordinary event must not stand in for one.
     *
     * **Nothing in the shipped app calls this, and that is an open defect, not a design.**
     * `drivingCandidateWindow`, `movementIdleWindow` and `transitionWindow` are therefore
     * dead in production on this platform: a drive that ends underground with no
     * `vehicle_exit` stays in `DRIVING` for ever and the trip is lost. The 45-minute expiry
     * is the one row that is covered from the app side, by
     * [ParkingCandidateCoordinator.expireIfDue] and the notification's own `setTimeoutAfter`.
     *
     * The obvious fix — open every batch with a tick — was tried on 2026-09-20 and
     * **measured to be wrong**: it flips `subway_commute_underground` from
     * `CANDIDATE_PENDING` with a candidate to `IDLE` with none. Not through
     * `drivingCandidateWindow`, which sits below the promotion check, but because
     * `movementIdleWindow` fires the instant anything ticks underground — there are no
     * location fixes there, so there is no movement evidence to advance, and "no sky" reads
     * as "not moving". `transitionWindow` then retires it 300s later.
     *
     * A deadline-scheduled tick does exactly the same thing, so the question is a contract
     * one and not an implementation one: see docs/05 §3a "OPEN: nothing on Android produces
     * one, and firing them breaks the subway trace".
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
 * [com.parkingpin.app.domain.detection.ActivityTransitionMapper] already applies one layer
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

package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.domain.detection.DetectionEffect
import com.sjstudioz.parkingpin.domain.detection.DetectionEngineState
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParityFixtureFiles
import com.sjstudioz.parkingpin.domain.detection.ParkingCandidate
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.detection.ReplayInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/05 §14 / §16: a process death between two events changes nothing. For every fixture
 * and every draft in `platform-tests`, and for every point in it — before the first event and
 * after each one — the events up to that point go through one [ParkingDetectionRuntime], the
 * process dies, and the rest go through a new runtime, a new engine and a new coordinator over
 * the **same** [DetectionStateStore], which is all a real death leaves behind.
 *
 * Each of those runs must end exactly where the uninterrupted run ends: the same engine state,
 * the same effects in the same order, and the same stored candidate. "Uninterrupted" is the
 * pure engine with its state held in memory ([uninterrupted]) — the golden's own semantics —
 * because this runtime reads its state back from the store before every event, so a runtime
 * that never dies already round-trips the state once per event. Comparing against the engine
 * alone is what makes a lossy store field fail here rather than on a phone.
 *
 * Android's twin of iOS's "restore equals uninterrupted" property. A process death keeps the
 * Play services registrations and the running capture, so the new process is fed the next
 * event and nothing else; a reboot is a different path ([ParkingDetectionRuntime.resumeAfterSystemReset]).
 */
class ParkingDetectionRuntimeRestoreTest {

    @Test
    fun `a process death after any event of any fixture or draft changes nothing`() = runBlocking {
        // Arrange
        val inputs = ParityFixtureFiles.allReplayInputs()
        assertTrue("the property must cover the whole suite", inputs.isNotEmpty())

        // Act
        val divergences = inputs.flatMap { (key, input) ->
            val events = ParityFixtureFiles.detectionEvents(input.events)
            val expected = uninterrupted(input, events)
            (0..events.size).mapNotNull { deathAfter ->
                val actual = replayThroughDeath(input, events, deathAfter)
                describeDivergence(expected, actual)?.let { "$key, death after event $deathAfter: $it" }
            }
        }

        // Assert
        assertTrue(divergences.take(MAX_REPORTED).joinToString("\n"), divergences.isEmpty())
    }

    @Test
    fun `a process death relaunched halfway to the next event changes nothing`() = runBlocking {
        // Arrange — the relaunch is not the next event: the new process starts (and reads its
        // state) halfway between the death and the event that follows, on a clock that has
        // moved on. The capture survived the death, so nothing is settled at the relaunch.
        val inputs = ParityFixtureFiles.allReplayInputs()

        // Act
        val divergences = inputs.flatMap { (key, input) ->
            val events = ParityFixtureFiles.detectionEvents(input.events)
            val expected = uninterrupted(input, events)
            (1 until events.size).mapNotNull { deathAfter ->
                val actual = replayThroughDeath(input, events, deathAfter, relaunchHalfwayToNextEvent = true)
                describeDivergence(expected, actual)?.let { "$key, relaunch before event $deathAfter: $it" }
            }
        }

        // Assert
        assertTrue(divergences.take(MAX_REPORTED).joinToString("\n"), divergences.isEmpty())
    }

    @Test
    fun `the uninterrupted replay creates the candidate the runtime stores`() = runBlocking {
        // Arrange — a guard on the property above: a comparison that never sees a candidate
        // could not notice a store that loses one.
        val input = ParityFixtureFiles.allReplayInputs().getValue(VEHICLE_THEN_WALK)
        val events = ParityFixtureFiles.detectionEvents(input.events)

        // Act
        val outcome = replayThroughDeath(input, events, deathAfter = events.size / 2)

        // Assert
        val created = outcome.effects.filterIsInstance<DetectionEffect.CreateCandidate>()
        assertEquals(1, created.size)
        assertEquals(created.single().candidateId, outcome.storedCandidate?.id)
        assertEquals(DetectionState.CANDIDATE_PENDING, outcome.state.state)
    }

    // ── replay ──────────────────────────────────────────────────────────────────────

    private data class Outcome(
        val state: DetectionEngineState,
        val effects: List<DetectionEffect>,
        /** The stored candidate; null for the pure engine, which stores nothing. */
        val storedCandidate: ParkingCandidate?,
    )

    /** The pure engine, its state never leaving memory. */
    private fun uninterrupted(input: ReplayInput, events: List<DetectionEvent>): Outcome {
        val engine = ParkingDetectionEngine(candidateIds())
        var state = DetectionEngineState.startingIn(DetectionState.valueOf(input.initialState), ParityFixtureFiles.EPOCH)
        val effects = mutableListOf<DetectionEffect>()
        for (event in events) {
            val step = engine.handle(state, event)
            state = step.state
            effects += step.effects
        }
        return Outcome(state, effects, storedCandidate = null)
    }

    /** [events] through a runtime that dies after [deathAfter] of them, over one real store. */
    private suspend fun replayThroughDeath(
        input: ReplayInput,
        events: List<DetectionEvent>,
        deathAfter: Int,
        /** Starts the new process between the two events rather than at the next one. */
        relaunchHalfwayToNextEvent: Boolean = false,
    ): Outcome {
        val store = DetectionStateStore(InMemoryPreferencesDataStore())
        val initial = DetectionEngineState.startingIn(DetectionState.valueOf(input.initialState), ParityFixtureFiles.EPOCH)
        store.writeEngineStateAndCheckpoint(initial, initial.toCheckpoint())
        // Minted ids outlive the process, as a UUID's uniqueness does.
        val ids = candidateIds()
        val clock = MutableTestClock(epochMillis = ParityFixtureFiles.EPOCH)
        val effects = mutableListOf<DetectionEffect>()

        var process = Process(store, ids, clock)
        events.forEachIndexed { index, event ->
            if (index == deathAfter) {
                process = Process(store, ids, clock)
                if (relaunchHalfwayToNextEvent && index > 0) {
                    val previousAt = events[index - 1].atMillis
                    clock.epochMillis = previousAt + (event.atMillis - previousAt) / 2
                    process.runtime.restore()
                }
            }
            clock.epochMillis = event.atMillis
            effects += process.runtime.feed(event)
        }
        // A death after the last event still has to leave a state the next process reads.
        val state = Process(store, ids, clock).runtime.restore()
        return Outcome(state, effects, store.readCandidateOnce())
    }

    /** Everything one process builds and a death throws away; only [store] survives it. */
    private class Process(store: DetectionStateStore, ids: () -> String, clock: MutableTestClock) {
        private val coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { error("the runtime never reaches the record store without a confirmation") },
            notifier = FakeCandidateNotifier(),
            clock = clock,
            idGenerator = { error("the engine mints every candidate id") },
            analytics = RecordingAnalytics(),
        )
        val runtime = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            engine = ParkingDetectionEngine(ids),
        )
    }

    /**
     * Each §2 event through the runtime entry point production uses for it. The runtime has no
     * entry of its own for `location_quality_degraded` — nothing on Android emits one yet — so
     * it goes through the generic sensor entry the car link uses.
     */
    private suspend fun ParkingDetectionRuntime.feed(event: DetectionEvent): List<DetectionEffect> = when (event) {
        is DetectionEvent.VehicleEnter -> handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, event.atMillis))
        is DetectionEvent.VehicleExit -> handleMotion(motion(MotionEventKind.EXITED_VEHICLE, event.atMillis))
        is DetectionEvent.WalkingEnter -> handleMotion(motion(MotionEventKind.STARTED_WALKING, event.atMillis))
        is DetectionEvent.StationaryEnter -> handleMotion(motion(MotionEventKind.BECAME_STATIONARY, event.atMillis))
        is DetectionEvent.StationaryExit ->
            handleMotion(motion(MotionEventKind.STOPPED_BEING_STATIONARY, event.atMillis))
        is DetectionEvent.Location -> handleLocations(listOf(event.sample))
        is DetectionEvent.TimerTick -> handleTick(event.atMillis)
        is DetectionEvent.UserSavedParking -> handleUserSavedParking(event.atMillis)
        is DetectionEvent.UserKeptParking -> handleUserKeptParking(event.atMillis)
        is DetectionEvent.UserConfirmedParking, is DetectionEvent.UserRejectedParking -> handleUserAnswer(event)
        is DetectionEvent.CarLinkConnected, is DetectionEvent.CarLinkDisconnected,
        is DetectionEvent.LocationQualityDegraded,
        -> handleCarLink(event)
        is DetectionEvent.SmartDetectionDisabled -> error("no fixture produces $event")
    }

    private fun motion(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis)

    private fun describeDivergence(expected: Outcome, actual: Outcome): String? {
        if (actual.state != expected.state) return "state ${expected.state} but ${actual.state}"
        if (actual.effects != expected.effects) {
            val at = actual.effects.indices.firstOrNull { actual.effects[it] != expected.effects.getOrNull(it) }
                ?: actual.effects.size
            return "effect $at ${expected.effects.getOrNull(at)} but ${actual.effects.getOrNull(at)}"
        }
        // The slot the confirmation screen opens names the candidate the engine holds pending.
        val pendingId = actual.state.candidate?.id?.takeIf { actual.state.state == DetectionState.CANDIDATE_PENDING }
        if (pendingId != null && actual.storedCandidate?.id != pendingId) {
            return "engine holds $pendingId pending but the store holds ${actual.storedCandidate?.id}"
        }
        return null
    }

    private fun candidateIds(): () -> String {
        var next = 0
        return { "restore-candidate-${next++}" }
    }

    private companion object {
        const val VEHICLE_THEN_WALK = "vehicle_then_walk.json"
        const val MAX_REPORTED = 20
    }
}

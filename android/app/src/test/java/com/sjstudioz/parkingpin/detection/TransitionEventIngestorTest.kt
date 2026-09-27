package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.domain.detection.DetectionEngineState
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionActivity
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.detection.TransitionKind
import com.sjstudioz.parkingpin.domain.location.LocationSessionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receiver's delegate: normalize, persist, update checkpoint. */
class TransitionEventIngestorTest {

    private val clock = MutableTestClock()
    private val store = DetectionStateStore(InMemoryPreferencesDataStore())
    private val locationRegistrar = FakeLocationSessionRegistrar()
    private val ingestor = TransitionEventIngestor(
        store,
        clock,
        FusedLocationSessionController(store, locationRegistrar, clock),
    )

    @Test
    fun firstEventEver_createsTheCheckpoint() = runTest {
        // Arrange — nothing persisted yet, as on a fresh install.
        // Act
        ingestor.ingest(listOf(raw(MotionActivity.WALKING, TransitionKind.ENTER, clock.epochMillis)))

        // Assert
        val checkpoint = store.readCheckpointOnce()
        assertNotNull(checkpoint)
        assertEquals(DetectionState.IDLE, checkpoint?.state)
        assertEquals(1L, checkpoint?.revision)
    }

    @Test
    fun batchedTransitions_persistInOrder() = runTest {
        // Arrange — Play services delivers several transitions in one intent.
        val t = clock.epochMillis
        val batch = listOf(
            raw(MotionActivity.IN_VEHICLE, TransitionKind.EXIT, t - 2_000),
            raw(MotionActivity.STILL, TransitionKind.ENTER, t - 1_000),
            raw(MotionActivity.WALKING, TransitionKind.ENTER, t),
        )

        // Act
        ingestor.ingest(batch)

        // Assert
        assertEquals(
            listOf(
                MotionEventKind.EXITED_VEHICLE,
                MotionEventKind.BECAME_STATIONARY,
                MotionEventKind.STARTED_WALKING,
            ),
            store.recentEvents.first().map { it.kind },
        )
        assertEquals(t - 2_000, store.readCheckpointOnce()?.lastAutomotiveAtMillis)
        assertEquals(3L, store.readCheckpointOnce()?.revision)
    }

    @Test
    fun unmappedTransition_isDroppedWithoutTouchingTheCheckpoint() = runTest {
        // Arrange / Act — WALKING EXIT has no product meaning.
        ingestor.ingest(listOf(raw(MotionActivity.WALKING, TransitionKind.EXIT, clock.epochMillis)))

        // Assert
        assertTrue(store.recentEvents.first().isEmpty())
        assertEquals(null, store.readCheckpointOnce())
    }

    @Test
    fun emptyBatch_isANoOp() = runTest {
        // Arrange / Act
        ingestor.ingest(emptyList())

        // Assert
        assertTrue(store.recentEvents.first().isEmpty())
    }

    @Test
    fun receivedTimestamp_comesFromTheInjectedClock() = runTest {
        // Arrange — the transition happened 30s before delivery.
        val happenedAt = clock.epochMillis - 30_000

        // Act
        ingestor.ingest(listOf(raw(MotionActivity.IN_VEHICLE, TransitionKind.ENTER, happenedAt)))

        // Assert — the delivery delay is measurable, which is the point of P0 instrumentation.
        val event = store.recentEvents.first().single()
        assertEquals(happenedAt, event.atMillis)
        assertEquals(30_000L, event.receivedAtMillis - event.atMillis)
    }

    // ── docs/05 §19: an exit opens a kerb capture only for a drive the engine follows ──

    /** An ingestor wired to a real engine runtime whose state is seeded as [engine]. */
    private suspend fun ingestorWithEngineIn(engine: DetectionEngineState): TransitionEventIngestor {
        store.writeEngineStateAndCheckpoint(engine, engine.toCheckpoint())
        return TransitionEventIngestor(
            store,
            clock,
            FusedLocationSessionController(store, locationRegistrar, clock),
            ParkingDetectionRuntime(store = store, candidates = { error("no candidate is expected here") }),
        )
    }

    @Test
    fun vehicleExit_withTheEngineIdle_opensNoCapture() = runTest {
        // Arrange — every bus or subway ride the Transition API calls IN_VEHICLE: the engine
        // dropped the lapsed DRIVING_CANDIDATE, and would drop every fix of a kerb capture.
        val withEngine = ingestorWithEngineIn(DetectionEngineState.startingIn(DetectionState.IDLE, clock.epochMillis))

        // Act
        withEngine.ingest(listOf(raw(MotionActivity.IN_VEHICLE, TransitionKind.EXIT, clock.epochMillis)))

        // Assert
        assertFalse("a 300 s high-accuracy capture nothing consumes", locationRegistrar.isRegistered)
    }

    @Test
    fun vehicleExit_withACandidatePending_opensNoCapture() = runTest {
        // Arrange — a car-link disconnect already produced the candidate; the exit the
        // Transition API reports afterwards has nothing left to decide.
        val pending = DetectionEngineState.startingIn(DetectionState.CANDIDATE_PENDING, clock.epochMillis - 60_000)
        val withEngine = ingestorWithEngineIn(pending)

        // Act
        withEngine.ingest(listOf(raw(MotionActivity.IN_VEHICLE, TransitionKind.EXIT, clock.epochMillis)))

        // Assert
        assertFalse(locationRegistrar.isRegistered)
    }

    @Test
    fun vehicleExit_afterADrivingCandidateLapsed_opensNoCapture() = runTest {
        // Arrange — docs/05 §19: the stored state still says DRIVING_CANDIDATE, but its
        // window lapsed long before this exit with its vehicle activity never sustained (a
        // session a car link opened, a short IN_VEHICLE burst on a bus). The engine settles it
        // to IDLE on the exit's own timestamp, so the capture decision must read the settled
        // state, not the stored one.
        val lapsedAt = clock.epochMillis - ParkingDetectionEngine.DRIVING_CANDIDATE_WINDOW_MILLIS - 60_000
        val seeded = DetectionEngineState.startingIn(DetectionState.DRIVING_CANDIDATE, lapsedAt)
        val notSustained = seeded.copy(session = seeded.session?.copy(vehicleActiveSinceMillis = null))
        val withEngine = ingestorWithEngineIn(notSustained)

        // Act
        withEngine.ingest(listOf(raw(MotionActivity.IN_VEHICLE, TransitionKind.EXIT, clock.epochMillis)))

        // Assert
        assertEquals(DetectionState.IDLE, store.readEngineStateOnce()?.state)
        assertTrue("no kerb request was ever made", locationRegistrar.requestedConfigs.isEmpty())
        assertFalse(locationRegistrar.isRegistered)
    }

    @Test
    fun vehicleExit_fromADrive_capturesTheKerb() = runTest {
        // Arrange
        val withEngine = ingestorWithEngineIn(DetectionEngineState.startingIn(DetectionState.DRIVING, clock.epochMillis - 600_000))

        // Act
        withEngine.ingest(listOf(raw(MotionActivity.IN_VEHICLE, TransitionKind.EXIT, clock.epochMillis)))

        // Assert
        assertTrue(locationRegistrar.isRegistered)
        assertEquals(LocationSessionMode.PARKING_TRANSITION, store.readLocationSessionStateOnce().mode)
    }

    private fun raw(activity: MotionActivity, transition: TransitionKind, atMillis: Long) =
        TransitionEventIngestor.RawTransition(activity, transition, atMillis)
}

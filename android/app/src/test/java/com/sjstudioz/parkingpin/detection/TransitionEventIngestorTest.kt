package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionActivity
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.TransitionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private fun raw(activity: MotionActivity, transition: TransitionKind, atMillis: Long) =
        TransitionEventIngestor.RawTransition(activity, transition, atMillis)
}

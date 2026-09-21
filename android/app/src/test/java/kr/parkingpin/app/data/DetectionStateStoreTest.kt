package kr.parkingpin.app.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kr.parkingpin.app.domain.detection.DetectionCheckpoint
import kr.parkingpin.app.domain.detection.DetectionState
import kr.parkingpin.app.domain.detection.MotionDomainEvent
import kr.parkingpin.app.domain.detection.MotionEventKind
import kr.parkingpin.app.domain.detection.ReliableLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionStateStoreTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun checkpoint_roundTripsThroughPersistence() = runTest {
        // Arrange
        val store = DetectionStateStore(InMemoryPreferencesDataStore())
        val checkpoint = DetectionCheckpoint(
            state = DetectionState.DRIVING,
            stateEnteredAtMillis = t0,
            lastAutomotiveAtMillis = t0 - 1_000,
            lastReliableLocation = ReliableLocation(37.5665, 126.9780, 9.0f, t0 - 500),
            lastLocationAtMillis = t0 - 500,
            travelDistanceEstimateMeters = 1234.5,
            candidateId = "c-1",
            revision = 3L,
        )

        // Act
        store.writeCheckpoint(checkpoint)

        // Assert
        assertEquals(checkpoint, store.readCheckpointOnce())
        assertEquals(checkpoint, store.checkpoint.first())
    }

    @Test
    fun absentCheckpoint_readsAsNull() = runTest {
        // Arrange
        val store = DetectionStateStore(InMemoryPreferencesDataStore())

        // Act / Assert
        assertNull(store.readCheckpointOnce())
    }

    @Test
    fun corruptCheckpoint_degradesToNullInsteadOfThrowing() = runTest {
        // Arrange — a payload written by an incompatible build.
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit { it[stringPreferencesKey("checkpoint")] = "{not json" }
        val store = DetectionStateStore(dataStore)

        // Act
        val checkpoint = store.readCheckpointOnce()

        // Assert — detection must never be bricked by a bad blob.
        assertNull(checkpoint)
    }

    @Test
    fun appendEventAndCheckpoint_writesBothTogether() = runTest {
        // Arrange
        val store = DetectionStateStore(InMemoryPreferencesDataStore())
        val event = event(MotionEventKind.ENTERED_VEHICLE, t0)
        val checkpoint = DetectionCheckpoint.initial(t0).copy(lastAutomotiveAtMillis = t0, revision = 1)

        // Act
        store.appendEventAndCheckpoint(event, checkpoint)

        // Assert — the log never shows an event the checkpoint has not accounted for.
        assertEquals(listOf(event), store.recentEvents.first())
        assertEquals(checkpoint, store.readCheckpointOnce())
    }

    @Test
    fun eventLog_keepsNewestEntriesWithinTheBound() = runTest {
        // Arrange
        val store = DetectionStateStore(InMemoryPreferencesDataStore(), maxLoggedEvents = 3)

        // Act — five events into a three-slot log.
        repeat(5) { index ->
            store.appendEventAndCheckpoint(
                event(MotionEventKind.BECAME_STATIONARY, t0 + index),
                DetectionCheckpoint.initial(t0).copy(revision = index.toLong()),
            )
        }

        // Assert — oldest dropped, order preserved.
        val logged = store.recentEvents.first()
        assertEquals(3, logged.size)
        assertEquals(listOf(t0 + 2, t0 + 3, t0 + 4), logged.map { it.atMillis })
    }

    @Test
    fun registrationRecord_persistsAndClears() = runTest {
        // Arrange
        val store = DetectionStateStore(InMemoryPreferencesDataStore())

        // Act
        store.recordRegistered(specVersion = 4, atMillis = t0)

        // Assert
        assertEquals(RegistrationRecord(4, t0), store.readRegistrationRecordOnce())

        // Act — reboot recovery clears it.
        store.clearRegistrationRecord()

        // Assert
        assertNull(store.readRegistrationRecordOnce())
    }

    @Test
    fun desiredEnabled_defaultsToOffAndPersists() = runTest {
        // Arrange — smart detection is opt-in, never on by default.
        val store = DetectionStateStore(InMemoryPreferencesDataStore())

        // Act / Assert
        assertEquals(false, store.readDesiredEnabledOnce())
        store.setDesiredEnabled(true)
        assertTrue(store.readDesiredEnabledOnce())
    }

    @Test
    fun clearEventLog_leavesCheckpointIntact() = runTest {
        // Arrange
        val store = DetectionStateStore(InMemoryPreferencesDataStore())
        val checkpoint = DetectionCheckpoint.initial(t0).copy(revision = 9)
        store.appendEventAndCheckpoint(event(MotionEventKind.STARTED_WALKING, t0), checkpoint)

        // Act
        store.clearEventLog()

        // Assert
        assertEquals(emptyList<MotionDomainEvent>(), store.recentEvents.first())
        assertEquals(checkpoint, store.readCheckpointOnce())
    }

    private fun event(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis + 250)
}

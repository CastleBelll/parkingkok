package kr.parkingpin.app.detection

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.parkingpin.app.AppContainer
import kr.parkingpin.app.analytics.DetectionProperties
import kr.parkingpin.app.domain.detection.CandidateOutcome
import kr.parkingpin.app.domain.parking.ConfidenceBucket
import kr.parkingpin.app.domain.parking.FloorParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of the notification history only a device can answer
 * (docs/10_DESIGN_UX_SPEC.md §7b, docs/05 §10a "History").
 *
 * The JVM suite states which outcome each resolution writes, against an in-memory
 * DataStore. What it cannot say is that the list survives a real file-backed DataStore
 * and a real Room database on the reference Galaxy S21+ — the append happens inside the
 * same `edit` that empties the candidate slot, and that transaction is the OS's.
 *
 * It leaves the four states §7b lists behind it on purpose, so the screen can be looked
 * at: three resolved candidates and one still unanswered, which is also the dot.
 */
@RunWith(AndroidJUnit4::class)
class CandidateHistoryDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun threeOutcomesAndAPendingOne_surviveARealStoreOnTheDevice() = runBlocking {
        // Arrange — the real composition, not a fake: this is the point of the test.
        val container = AppContainer(context)
        val coordinator = container.parkingCandidateCoordinator
        val store = container.detectionStateStore
        // A device carries state between runs, and the list is append-only by contract,
        // so the assertions read the tail this run appended rather than the whole list.
        store.readCandidateOnce()?.let { coordinator.retire(it.id) }
        container.parkingRepository.findActive()?.let { container.parkingRepository.delete(it.id) }

        // Act — one of each resolution §7b lists, oldest first, then one left unanswered.
        val confirmed = coordinator.create(evidence(), lastReliableLocation = null)
        coordinator.confirm(
            confirmed.id,
            ConfirmedCandidateDetails(FloorParser.parse("B3"), zone = "A구역", spot = "142"),
        )
        val rejected = coordinator.create(evidence(), lastReliableLocation = null)
        coordinator.reject(rejected.id)
        val expired = coordinator.create(evidence(), lastReliableLocation = null)
        coordinator.retire(expired.id)
        val pending = coordinator.create(evidence(), lastReliableLocation = null)

        // Assert
        val history = store.readCandidateHistoryOnce().takeLast(OUTCOMES)
        assertEquals(
            listOf(CandidateOutcome.CONFIRMED, CandidateOutcome.REJECTED, CandidateOutcome.EXPIRED),
            history.map { it.outcome },
        )
        // The confirmed line points at a record that is really in Room.
        assertNotNull(container.parkingRepository.find(requireNotNull(history.first().recordId)))
        // The slot still holds exactly one, which is what the bell's dot is drawn from.
        assertEquals(pending.id, store.readCandidateOnce()?.id)
    }

    private fun evidence() = DetectionProperties(
        confidenceBucket = ConfidenceBucket.HIGH,
        walkingEvidence = true,
        gpsDegradation = false,
        optionalVehicleSignal = false,
    )

    private companion object {
        /** The three this run appends. */
        const val OUTCOMES = 3
    }
}

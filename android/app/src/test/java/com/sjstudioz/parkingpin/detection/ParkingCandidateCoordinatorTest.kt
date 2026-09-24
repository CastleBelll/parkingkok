package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.DetectionProperties
import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.domain.detection.ParkingCandidate
import com.sjstudioz.parkingpin.domain.detection.ReliableLocation
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The candidate contract of docs/05_PARKING_DETECTION_ENGINE.md §10a, clause by clause.
 *
 * A real Room database and a real `DetectionStateStore` over an in-memory DataStore: the
 * rules under test are about what survives a write and what the two stores agree on, and a
 * hand-written repository fake would agree with whatever the coordinator did.
 */
class ParkingCandidateCoordinatorTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: DetectionStateStore
    private lateinit var notifier: FakeCandidateNotifier
    private lateinit var analytics: RecordingAnalytics
    private lateinit var coordinator: ParkingCandidateCoordinator

    private val start = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = start)
    private var nextId = 0

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        val preferences = InMemoryPreferencesDataStore()
        store = DetectionStateStore(preferences)
        notifier = FakeCandidateNotifier()
        analytics = RecordingAnalytics()
        coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { repository },
            notifier = notifier,
            clock = clock,
            idGenerator = { "id-${nextId++}" },
            analytics = analytics,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── Posting (§10a, §9) ───────────────────────────────────────────────────────────

    @Test
    fun `a high confidence candidate is stored and announced`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        assertEquals(listOf(candidate), notifier.showing)
        assertEquals(candidate.id, store.readCandidateOnce()?.id)
        assertTrue("parking_candidate_created" in analytics.names)
    }

    @Test
    fun `a low confidence candidate posts nothing but is still recorded`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.LOW), location())

        assertEquals(emptyList<ParkingCandidate>(), notifier.posted)
        // §9: "the candidate is still recorded so the app can show it when opened".
        assertEquals(candidate.id, store.readCandidateOnce()?.id)
        assertEquals(candidate.id, coordinator.observePending().first()?.id)
        // The event is about detection, not about the notification.
        assertTrue("parking_candidate_created" in analytics.names)
    }

    @Test
    fun `a denied notification permission costs the prompt and nothing else`() = runTest {
        notifier.authorized = false

        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        assertEquals(emptyList<ParkingCandidate>(), notifier.posted)
        // "Denied, the candidate is saved and surfaces in the app on next launch;
        // nothing is lost and nothing is retried."
        assertEquals(candidate.id, coordinator.observePending().first()?.id)
        assertNotNull(store.readCandidateOnce()?.lastReliableLocation)
    }

    // ── Identity and deduplication (§10a) ────────────────────────────────────────────

    @Test
    fun `re-posting the same candidate replaces its notification instead of stacking`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        notifier.post(candidate)
        notifier.post(candidate)

        assertEquals(3, notifier.posted.size)
        // The shade is keyed by candidate id, so "a session can never show two".
        assertEquals(1, notifier.showing.size)
    }

    @Test
    fun `a new travel session expires the previous candidate and withdraws its notification`() =
        runTest {
            val first = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

            clock.epochMillis = start + 60_000L
            val second = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

            assertEquals(listOf(first.id), notifier.withdrawn)
            assertEquals(listOf(second), notifier.showing)
            assertEquals(second.id, store.readCandidateOnce()?.id)
            // The superseded one is gone, not merely hidden.
            assertNull(coordinator.pending(first.id))
        }

    // ── Confirmation (§10a) ──────────────────────────────────────────────────────────

    @Test
    fun `confirming writes a detected record with the candidate location and chosen floor`() =
        runTest {
            val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

            clock.epochMillis = start + 5 * 60_000L
            val result = coordinator.confirm(
                candidate.id,
                ConfirmedCandidateDetails(floor = FloorParser.parse("B3")),
            )

            val record = (result as ConfirmCandidateResult.Confirmed).record
            assertEquals(ParkingSource.DETECTED, record.source)
            assertEquals(ConfidenceBucket.HIGH, record.confidenceBucket)
            assertEquals(LATITUDE, record.location?.latitude ?: 0.0, 0.0)
            assertEquals("B3", record.floor?.displayLabel)
            // The car was left when the fix was taken, not when the user answered.
            assertEquals(candidate.parkedAtMillis, record.startedAtMillis)
            assertEquals(record.id, repository.findActive()?.id)
            assertTrue("parking_candidate_confirmed" in analytics.names)
        }

    @Test
    fun `confirming clears the candidate and takes its notification down`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        coordinator.confirm(candidate.id, ConfirmedCandidateDetails(floor = null))

        assertNull(store.readCandidateOnce())
        assertEquals(emptyList<ParkingCandidate>(), notifier.showing)
    }

    @Test
    fun `confirming refuses to open a second session while one is already active`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        coordinator.confirm(candidate.id, ConfirmedCandidateDetails(floor = null))

        clock.epochMillis = start + 60_000L
        val second = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        val result = coordinator.confirm(second.id, ConfirmedCandidateDetails(floor = null))

        assertTrue(result is ConfirmCandidateResult.AlreadyActive)
        // Nothing was written — no second record, and no record was closed to make room.
        assertEquals(emptyList<String>(), repository.observeCompleted(limit = -1).first().map { it.id })
        // The candidate is left for the user to answer or abandon.
        assertEquals(second.id, store.readCandidateOnce()?.id)
    }

    // ── Rejection (§10a "never dropped") ─────────────────────────────────────────────

    @Test
    fun `rejecting discards the candidate, withdraws the notification and reports the event`() =
        runTest {
            val candidate = coordinator.create(evidence(ConfidenceBucket.MEDIUM), location())

            assertTrue(coordinator.reject(candidate.id))

            assertNull(store.readCandidateOnce())
            assertEquals(listOf(candidate.id), notifier.withdrawn)
            assertEquals(1, analytics.names.count { it == "parking_candidate_rejected" })
            assertNull(repository.findActive())
        }

    @Test
    fun `rejecting a candidate that has already gone still reports the event`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS
        coordinator.expireIfDue()

        assertFalse(coordinator.reject(candidate.id))

        // §10a: "rejection is the event that pays for the whole feature, so it is never
        // dropped". A user who answers a prompt a second after it lapsed has still told
        // the detector it was wrong.
        assertEquals(1, analytics.names.count { it == "parking_candidate_rejected" })
    }

    // ── Expiry (§10, §10a) ───────────────────────────────────────────────────────────

    @Test
    fun `a candidate survives until the 45th minute`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS - 1
        assertNull(coordinator.expireIfDue())
        assertEquals(candidate.id, coordinator.pending(candidate.id)?.id)
    }

    @Test
    fun `at 45 minutes the candidate expires, the notification is withdrawn and no record exists`() =
        runTest {
            val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

            clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS
            val expired = coordinator.expireIfDue()

            assertEquals(candidate.id, expired?.id)
            assertNull(store.readCandidateOnce())
            assertEquals(listOf(candidate.id), notifier.withdrawn)
            // "do not silently create parking" (§10).
            assertNull(repository.findActive())
            assertEquals(emptyList<String>(), analytics.names.filter { it == "parking_candidate_confirmed" })
        }

    @Test
    fun `tapping an expired notification finds nothing and creates nothing`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS

        // The tap arrives before any sweep has run — the process may have been dead.
        assertNull(coordinator.pending(candidate.id))
        val result = coordinator.confirm(candidate.id, ConfirmedCandidateDetails(floor = null))

        assertEquals(ConfirmCandidateResult.Gone(alreadyBecame = null), result)
        // §10a: "a tap never silently creates parking".
        assertNull(repository.findActive())
        assertTrue(candidate.id in notifier.withdrawn)
    }

    @Test
    fun `a tap on a candidate that was already confirmed opens the record it became`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        val confirmed = coordinator.confirm(candidate.id, ConfirmedCandidateDetails(floor = null))
        val recordId = (confirmed as ConfirmCandidateResult.Confirmed).record.id

        // The tap beats the notification's withdrawal, which is the only way to get here.
        assertEquals(recordId, coordinator.confirmedRecordId(candidate.id))
        assertEquals(
            ConfirmCandidateResult.Gone(alreadyBecame = recordId),
            coordinator.confirm(candidate.id, ConfirmedCandidateDetails(floor = null)),
        )
        // §10a: confirming twice must not produce a second record.
        assertEquals(recordId, repository.findActive()?.id)
    }

    @Test
    fun `an expired candidate became no record, so its tap has nothing to open`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS
        coordinator.expireIfDue()

        assertNull(coordinator.confirmedRecordId(candidate.id))
    }

    @Test
    fun `expiry reports nothing at all`() = runTest {
        coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        val afterCreate = analytics.names.size

        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS
        coordinator.expireIfDue()

        // An unanswered guess is not an event, and the user is not told about it either.
        assertEquals(afterCreate, analytics.names.size)
    }

    private fun evidence(bucket: ConfidenceBucket) = DetectionProperties(
        confidenceBucket = bucket,
        walkingEvidence = true,
        gpsDegradation = false,
        optionalVehicleSignal = false,
    )

    private fun location() = ReliableLocation(
        latitude = LATITUDE,
        longitude = 127.0276,
        horizontalAccuracyM = 12f,
        capturedAtMillis = start - 30_000L,
    )

    private companion object {
        const val LATITUDE = 37.4979
    }
}

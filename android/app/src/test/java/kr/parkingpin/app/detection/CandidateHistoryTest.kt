package kr.parkingpin.app.detection

import kr.parkingpin.app.analytics.DetectionProperties
import kr.parkingpin.app.data.DetectionStateStore
import kr.parkingpin.app.data.InMemoryPreferencesDataStore
import kr.parkingpin.app.data.parking.ParkingDatabase
import kr.parkingpin.app.data.parking.RoomParkingRepository
import kr.parkingpin.app.data.parking.createTestParkingDatabase
import kr.parkingpin.app.domain.detection.CandidateHistoryEntry
import kr.parkingpin.app.domain.detection.CandidateOutcome
import kr.parkingpin.app.domain.detection.ParkingCandidate
import kr.parkingpin.app.domain.detection.ReliableLocation
import kr.parkingpin.app.domain.parking.ConfidenceBucket
import kr.parkingpin.app.domain.parking.FloorParser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The notification history of docs/10_DESIGN_UX_SPEC.md §7b and
 * docs/05_PARKING_DETECTION_ENGINE.md §10a "History", clause by clause.
 *
 * The same real stores [ParkingCandidateCoordinatorTest] uses, for the same reason: what
 * is under test is what survives a write, and a fake would agree with whatever the
 * coordinator did.
 */
class CandidateHistoryTest {

    private lateinit var database: ParkingDatabase
    private lateinit var store: DetectionStateStore
    private lateinit var notifier: FakeCandidateNotifier
    private lateinit var coordinator: ParkingCandidateCoordinator

    private val start = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = start)
    private var nextId = 0

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        notifier = FakeCandidateNotifier()
        coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { RoomParkingRepository(database.parkingRecordDao()) },
            notifier = notifier,
            clock = clock,
            idGenerator = { "id-${nextId++}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── The three outcomes (§7b "What is in it") ─────────────────────────────────────

    @Test
    fun `confirming records the candidate as saved, with the record it became`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        val result = coordinator.confirm(
            candidate.id,
            ConfirmedCandidateDetails(floor = FloorParser.parse("B3")),
        )

        val record = (result as ConfirmCandidateResult.Confirmed).record
        assertEquals(
            listOf(
                CandidateHistoryEntry(
                    candidateId = candidate.id,
                    raisedAtMillis = candidate.detectedAtMillis,
                    outcome = CandidateOutcome.CONFIRMED,
                    recordId = record.id,
                ),
            ),
            store.readCandidateHistoryOnce(),
        )
    }

    @Test
    fun `rejecting records the candidate as 주차 아님`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        coordinator.reject(candidate.id)

        val entry = store.readCandidateHistoryOnce().single()
        assertEquals(CandidateOutcome.REJECTED, entry.outcome)
        assertEquals(candidate.detectedAtMillis, entry.raisedAtMillis)
        // Nothing was written, so there is nothing for a tap to open (§7b).
        assertEquals(null, entry.recordId)
    }

    @Test
    fun `an unanswered candidate records as 응답 없음 when it expires`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS
        coordinator.expireIfDue()

        val entry = store.readCandidateHistoryOnce().single()
        assertEquals(CandidateOutcome.EXPIRED, entry.outcome)
        assertEquals(candidate.id, entry.candidateId)
    }

    @Test
    fun `a superseded candidate records as 응답 없음 too`() = runTest {
        // §10a: a new travel session makes the older candidate "expire immediately".
        val first = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        clock.epochMillis = start + 60_000L
        val second = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        val entry = store.readCandidateHistoryOnce().single()
        assertEquals(first.id, entry.candidateId)
        assertEquals(CandidateOutcome.EXPIRED, entry.outcome)
        // The live one is still live: §10a's one slot has not gained a second job.
        assertEquals(second.id, store.readCandidateOnce()?.id)
    }

    @Test
    fun `a candidate retired by the car link reconnecting records as 응답 없음`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        coordinator.retire(candidate.id)

        assertEquals(
            listOf(CandidateOutcome.EXPIRED),
            store.readCandidateHistoryOnce().map { it.outcome },
        )
    }

    // ── One line per candidate ───────────────────────────────────────────────────────

    @Test
    fun `rejecting a candidate that already expired does not record it twice`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        clock.epochMillis = start + ParkingCandidate.LIFETIME_MILLIS
        coordinator.expireIfDue()

        // The shade outlived the candidate and the user answered it anyway. §10a still
        // reports the rejection, but the history already says what became of this one.
        coordinator.reject(candidate.id)

        assertEquals(
            listOf(CandidateOutcome.EXPIRED),
            store.readCandidateHistoryOnce().map { it.outcome },
        )
    }

    @Test
    fun `a confirmation refused because a session is already open records nothing`() = runTest {
        val first = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        coordinator.confirm(first.id, ConfirmedCandidateDetails(floor = null))
        clock.epochMillis = start + 60_000L
        val second = coordinator.create(evidence(ConfidenceBucket.HIGH), location())

        val result = coordinator.confirm(second.id, ConfirmedCandidateDetails(floor = null))

        assertTrue(result is ConfirmCandidateResult.AlreadyActive)
        // The superseding of `first` is one line; `second` is still pending and has none.
        assertEquals(
            listOf(CandidateOutcome.CONFIRMED),
            store.readCandidateHistoryOnce().map { it.outcome },
        )
    }

    // ── Retention (§7b) ──────────────────────────────────────────────────────────────

    @Test
    fun `the history keeps the last thirty and drops the oldest first`() = runTest {
        val overflow = CandidateHistoryEntry.MAX_ENTRIES + 1
        val ids = (0 until overflow).map { index ->
            clock.epochMillis = start + index * ParkingCandidate.LIFETIME_MILLIS
            val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
            clock.epochMillis += ParkingCandidate.LIFETIME_MILLIS
            coordinator.expireIfDue()
            candidate.id
        }

        val stored = store.readCandidateHistoryOnce()
        assertEquals(CandidateHistoryEntry.MAX_ENTRIES, stored.size)
        assertEquals(ids.drop(1), stored.map { it.candidateId })
    }

    // ── Privacy (§7b, docs/09 §9) ────────────────────────────────────────────────────

    @Test
    fun `no coordinate reaches the history`() = runTest {
        val candidate = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        coordinator.confirm(candidate.id, ConfirmedCandidateDetails(floor = FloorParser.parse("B3")))
        clock.epochMillis = start + 60_000L
        val rejected = coordinator.create(evidence(ConfidenceBucket.HIGH), location())
        coordinator.reject(rejected.id)

        val encoded = Json.encodeToString(store.readCandidateHistoryOnce())

        // The candidate this came from carried all four numbers.
        for (leak in listOf(LATITUDE, LONGITUDE, ACCURACY).map(Any::toString)) {
            assertFalse("$leak leaked into the notification history", leak in encoded)
        }
        for (field in listOf("latitude", "longitude", "location", "address")) {
            assertFalse("$field leaked into the notification history", field in encoded)
        }
    }

    private fun evidence(bucket: ConfidenceBucket) = DetectionProperties(
        confidenceBucket = bucket,
        walkingEvidence = true,
        gpsDegradation = false,
        optionalVehicleSignal = false,
    )

    private fun location() = ReliableLocation(
        latitude = LATITUDE,
        longitude = LONGITUDE,
        horizontalAccuracyM = ACCURACY,
        capturedAtMillis = clock.nowEpochMillis() - 30_000L,
    )

    private companion object {
        const val LATITUDE = 37.4979
        const val LONGITUDE = 127.0276
        const val ACCURACY = 12f
    }
}

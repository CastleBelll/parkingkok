package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.analytics.AnalyticsEvent
import com.sjstudioz.parkingpin.analytics.RecordingAnalytics
import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.domain.detection.DetectionEvent
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * §11 departure, end to end: driving away from a parking closes the record.
 *
 * The engine already had `PARKED → DEPARTURE_CANDIDATE → DRIVING` before this test existed.
 * What it did not have was any effect that closed the parking, so the machine noticed the
 * departure and the user's 진행 중 주차 stayed open for ever. That gap is what these
 * assertions are for, which is why they are about the **record** and not about the state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoEndParkingTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var store: DetectionStateStore
    private lateinit var runtime: ParkingDetectionRuntime
    private lateinit var analytics: RecordingAnalytics
    private val clock = MutableTestClock(epochMillis = START)

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        store = DetectionStateStore(InMemoryPreferencesDataStore())
        analytics = RecordingAnalytics()
        val coordinator = ParkingCandidateCoordinator(
            store = store,
            repository = { repository },
            notifier = FakeCandidateNotifier(),
            analytics = analytics,
            clock = clock,
            idGenerator = { UUID.randomUUID().toString() },
        )
        runtime = ParkingDetectionRuntime(
            store = store,
            candidates = { coordinator },
            engine = ParkingDetectionEngine { UUID.randomUUID().toString() },
            endParking = { endedAt -> EndParkingUseCase(repository, clock)(endedAt) },
            analytics = { analytics },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `driving away from a parking closes the record`() = runTest {
        // Arrange — a parking the user confirmed, and the machine in PARKED with it.
        parkAndConfirm()
        assertEquals(DetectionState.PARKED, runtime.restore().state)
        assertNotNull("the control: a parking is open", repository.findActive())

        // Act — get in and drive: §11's two bars, then §7's guard in full.
        driveAway()

        // Assert — the record is closed, and closed at the moment the car pulled away
        // rather than whenever the guard finally agreed.
        assertNull("driving away ends the parking", repository.findActive())
        assertEquals(DetectionState.DRIVING, runtime.restore().state)
        assertTrue(
            "docs/17: an automatic end is reported as one",
            analytics.events.any { it is AnalyticsEvent.ParkingAutoEnd },
        )
    }

    @Test
    fun `sitting in the parked car does not end it`() = runTest {
        // §11: "if uncertain -> suggestion, not destructive silent end." Ninety seconds of
        // vehicle evidence with no movement is a phone that woke up in a parked car.
        parkAndConfirm()

        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, DEPART))
        runtime.handleMotion(motion(MotionEventKind.STARTED_WALKING, DEPART + 200_000L))

        assertNotNull("ending a parking the user is still inside is the one unrecoverable move", repository.findActive())
    }

    private suspend fun parkAndConfirm() {
        SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-1" },
        )(ManualParkingInput(floorRaw = "B3"))
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, START))
        runtime.handleMotion(motion(MotionEventKind.EXITED_VEHICLE, START + 400_000L))
        runtime.handleMotion(motion(MotionEventKind.STARTED_WALKING, START + 420_000L))
        runtime.handleUserAnswer(DetectionEvent.UserConfirmedParking(START + 430_000L))
    }

    /** §11's bars and then §7's: 90 s of vehicle activity and real distance covered. */
    private suspend fun driveAway() {
        runtime.handleMotion(motion(MotionEventKind.ENTERED_VEHICLE, DEPART))
        var north = 0.0
        var at = DEPART
        repeat(12) {
            at += 30_000L
            north += 300.0
            runtime.handleLocations(listOf(fix(at, north)))
        }
    }

    private fun fix(atMillis: Long, north: Double) = LocationSample(
        atMillis = atMillis,
        latitude = 37.5 + north / 111_320.0,
        longitude = 127.0,
        horizontalAccuracyM = 8f,
        speedMps = 12f,
    )

    private fun motion(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis)

    private companion object {
        const val START = 1_700_000_000_000L
        const val DEPART = START + 3_600_000L
    }
}

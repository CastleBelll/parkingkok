package com.parkingpin.app.analytics

import com.parkingpin.app.data.InMemoryPreferencesDataStore
import com.parkingpin.app.data.parking.ParkingDatabase
import com.parkingpin.app.data.parking.RoomParkingRepository
import com.parkingpin.app.data.parking.createTestParkingDatabase
import com.parkingpin.app.detection.MutableTestClock
import com.parkingpin.app.domain.parking.usecase.ManualParkingInput
import com.parkingpin.app.domain.parking.usecase.SaveManualParkingResult
import com.parkingpin.app.domain.parking.usecase.SaveManualParkingUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one detection-independent call site this milestone wires (docs/17 §2
 * `parking_manual_saved`). The remaining twelve events are type layer only, by design —
 * they land with the features that emit them.
 */
class ManualSaveAnalyticsTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository

    private val clock = MutableTestClock(epochMillis = 1_780_000_000_000L)
    private val sink = RecordingAnalyticsSink()
    private val consent = AnalyticsConsentStore(InMemoryPreferencesDataStore())
    private var nextId = 0

    private lateinit var save: SaveManualParkingUseCase

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        save = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-${nextId++}" },
            analytics = AnalyticsRecorder(consentStore = consent, sink = sink, clock = clock),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a manual save reports parking_manual_saved, once, and carries no typed field`() = runTest {
        // Arrange
        consent.setGranted(true)

        // Act
        val result = save(ManualParkingInput(floorRaw = "B3", zone = "A구역", spot = "142", memo = "기둥 옆"))

        // Assert
        assertTrue(result is SaveManualParkingResult.Saved)
        assertEquals(listOf("parking_manual_saved"), sink.names)
        // The floor the user typed is nowhere in the payload — it has nowhere to be.
        assertEquals(
            mapOf("platform" to AnalyticsValue.Text("android")),
            sink.payloads.single().parameters,
        )
    }

    @Test
    fun `a manual save that could not be written reports nothing`() = runTest {
        // Arrange — FR-004 allows one active parking, so a second save fails.
        consent.setGranted(true)
        save(ManualParkingInput())

        // Act
        val second = save(ManualParkingInput())

        // Assert — one write, one event. An event for a failed save would overstate use.
        assertTrue(second is SaveManualParkingResult.AlreadyActive)
        assertEquals(1, sink.payloads.size)
    }

    @Test
    fun `a manual save while opted out reports nothing at all`() = runTest {
        // Arrange — consent left at its default.

        // Act
        val result = save(ManualParkingInput())

        // Assert — the save is unaffected by the opt-out; only the report is.
        assertTrue(result is SaveManualParkingResult.Saved)
        assertTrue(sink.payloads.isEmpty())
    }
}

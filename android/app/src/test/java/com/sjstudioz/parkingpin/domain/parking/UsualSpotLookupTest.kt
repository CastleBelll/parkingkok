package com.sjstudioz.parkingpin.domain.parking

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.domain.parking.usecase.SuggestUsualSpotUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.UsualSpotLookup
import com.sjstudioz.parkingpin.domain.photo.PillarSuggestion
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/02 §18: last time's floor and zone, offered where the car was left before. */
class UsualSpotLookupTest {

    // ~111 m per 0.001° of latitude.
    private val mart = location(37.5000, 127.0000)
    private val acrossTheCarPark = location(37.5010, 127.0000)
    private val nextBlock = location(37.5020, 127.0000)

    private var nextId = 0
    private val database: ParkingDatabase = createTestParkingDatabase()
    private val repository = RoomParkingRepository(database.parkingRecordDao())

    @After
    fun tearDown() = database.close()

    @Test
    fun `the newest parking here is the suggestion`() {
        val history = listOf(
            record(mart, floor = "B2", zone = "A", endedAt = 300),
            record(mart, floor = "B4", zone = "C", endedAt = 100),
        )

        assertEquals(PillarSuggestion(floorRaw = "B2", zone = "A"), UsualSpotLookup.find(mart, history))
    }

    @Test
    fun `the far side of one car park still counts`() {
        val history = listOf(record(acrossTheCarPark, floor = "B1"))

        assertEquals("B1", UsualSpotLookup.find(mart, history)?.floorRaw)
    }

    @Test
    fun `the next block is a different car park`() {
        val history = listOf(record(nextBlock, floor = "B1"))

        assertNull(UsualSpotLookup.find(mart, history))
    }

    @Test
    fun `a parking that said nothing is skipped for an older one that did`() {
        val history = listOf(
            record(mart, floor = null, zone = null, endedAt = 300),
            record(mart, floor = "3F", endedAt = 100),
        )

        assertEquals("3F", UsualSpotLookup.find(mart, history)?.floorRaw)
    }

    @Test
    fun `the bay number is never carried over`() {
        val history = listOf(record(mart, floor = "B2", zone = "A", spot = "47"))

        assertNull(UsualSpotLookup.find(mart, history)?.spot)
    }

    @Test
    fun `a vague fix here offers nothing`() {
        val history = listOf(record(mart, floor = "B2"))

        assertNull(UsualSpotLookup.find(mart.copy(horizontalAccuracyM = 300f), history))
        assertNull(UsualSpotLookup.find(mart.copy(horizontalAccuracyM = null), history))
        assertNull(UsualSpotLookup.find(null, history))
    }

    @Test
    fun `a vague fix in history is not a match`() {
        val history = listOf(record(mart.copy(horizontalAccuracyM = 250f), floor = "B2"))

        assertNull(UsualSpotLookup.find(mart, history))
    }

    @Test
    fun `no history, no suggestion`() {
        assertNull(UsualSpotLookup.find(mart, emptyList()))
    }

    @Test
    fun `a saved parking is offered only what it left blank`() = runTest {
        repository.insert(record(mart, floor = "B2", zone = "A"))
        val active = record(mart, floor = "B2", zone = null, endedAt = null)
        repository.insert(active)

        val offered = SuggestUsualSpotUseCase(repository).forRecord(active)

        assertEquals(PillarSuggestion(zone = "A"), offered)
    }

    @Test
    fun `a saved parking that says everything is not second-guessed`() = runTest {
        repository.insert(record(mart, floor = "B2", zone = "A"))
        val active = record(mart, floor = "B3", zone = "D", endedAt = null)

        assertNull(SuggestUsualSpotUseCase(repository).forRecord(active))
    }

    @Test
    fun `an empty form near last time's parking is offered its floor and zone`() = runTest {
        repository.insert(record(mart, floor = "B2", zone = "A"))

        assertEquals(
            PillarSuggestion(floorRaw = "B2", zone = "A"),
            SuggestUsualSpotUseCase(repository).forLocation(mart),
        )
    }

    @Test
    fun `a parking on another floor is not offered last time's zone`() = runTest {
        // Zone C on B2 says nothing about B3.
        repository.insert(record(mart, floor = "B2", zone = "C"))
        val active = record(mart, floor = "B3", zone = null, endedAt = null)

        assertNull(SuggestUsualSpotUseCase(repository).forRecord(active))
    }

    @Test
    fun `the same floor written another way still agrees`() {
        assertEquals(true, UsualSpotLookup.agrees(FloorParser.parse("지하 2층"), "B2"))
        assertEquals(false, UsualSpotLookup.agrees(FloorParser.parse("B3"), "B2"))
        assertEquals(true, UsualSpotLookup.agrees(null, "B2"))
        assertEquals(false, UsualSpotLookup.agrees(FloorParser.parse("B2"), null))
    }

    private fun location(latitude: Double, longitude: Double) =
        ParkingLocation(latitude, longitude, horizontalAccuracyM = 20f, capturedAtMillis = 0L)

    private fun record(
        at: ParkingLocation,
        floor: String? = null,
        zone: String? = null,
        spot: String? = null,
        endedAt: Long? = 100L,
    ) = ParkingRecord(
        id = "record-${nextId++}",
        startedAtMillis = 0L,
        endedAtMillis = endedAt,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = at,
        floor = FloorParser.parse(floor),
        zone = zone,
        spot = spot,
        memo = null,
        photoRelativePath = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        revision = 1,
    )
}

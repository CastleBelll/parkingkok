package com.parkingkok.app.domain.parking

import com.parkingkok.app.data.parking.ParkingDatabase
import com.parkingkok.app.data.parking.RoomParkingRepository
import com.parkingkok.app.data.parking.createTestParkingDatabase
import com.parkingkok.app.detection.MutableTestClock
import com.parkingkok.app.domain.parking.usecase.ApplyPillarSuggestionUseCase
import com.parkingkok.app.domain.parking.usecase.ManualParkingInput
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingResult
import com.parkingkok.app.domain.parking.usecase.SaveManualParkingUseCase
import com.parkingkok.app.domain.parking.usecase.SuggestFromPillarPhotoUseCase
import com.parkingkok.app.domain.photo.PhotoSource
import com.parkingkok.app.domain.photo.PillarSuggestion
import com.parkingkok.app.domain.photo.PillarTextReader
import com.parkingkok.app.domain.photo.ReadPillarSuggestionUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The home and detail half of docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a: a suggestion for
 * the fields the record leaves empty, written only on a tap.
 */
class PillarSuggestionUseCasesTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository

    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)
    private val photo = PhotoSource { ByteArrayInputStream(ByteArray(0)) }

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a record with nothing filled in is offered everything the photo read`() = runTest {
        // Arrange
        val recordId = save(floorRaw = "")

        // Act
        val suggestion = suggesting("지하 3층", "A구역 142")(recordId, photo)

        // Assert
        assertEquals(PillarSuggestion("지하 3층", "A구역", "142"), suggestion)
    }

    @Test
    fun `a record that already says B3 is not second-guessed by a photo`() = runTest {
        // Arrange — §6a's own words.
        val recordId = save(floorRaw = "B3")

        // Act
        val suggestion = suggesting("지하 5층", "A구역 142")(recordId, photo)

        // Assert — the floor the user gave stands; only the blanks are offered.
        assertNull(suggestion.floorRaw)
        assertEquals("A구역", suggestion.zone)
        assertEquals("142", suggestion.spot)
    }

    @Test
    fun `nothing readable is nothing offered`() = runTest {
        val recordId = save(floorRaw = "")

        assertEquals(PillarSuggestion.NONE, suggesting("엘리베이터")(recordId, photo))
    }

    @Test
    fun `applying writes only the blanks, and only when it is called`() = runTest {
        // Arrange
        val recordId = save(floorRaw = "B3")
        val suggestion = PillarSuggestion(floorRaw = "B5", zone = "A구역", spot = "142")

        // Assert — nothing has changed yet. The offer is not the write.
        assertEquals("B3", repository.find(recordId)?.floor?.displayLabel)

        // Act
        clock.epochMillis += 1_000L
        val updated = ApplyPillarSuggestionUseCase(repository, clock)(recordId, suggestion)

        // Assert — the floor a person chose outranks one a camera read, even at the tap.
        assertEquals("B3", updated?.floor?.displayLabel)
        assertEquals("A구역", updated?.zone)
        assertEquals("142", updated?.spot)
        assertEquals(2, updated?.revision)
    }

    @Test
    fun `applying an empty suggestion touches nothing`() = runTest {
        val recordId = save(floorRaw = "B3")

        val updated = ApplyPillarSuggestionUseCase(repository, clock)(recordId, PillarSuggestion.NONE)

        assertNull(updated)
        assertEquals(1, repository.find(recordId)?.revision)
    }

    private fun suggesting(vararg lines: String) = SuggestFromPillarPhotoUseCase(
        repository = repository,
        readPillar = ReadPillarSuggestionUseCase(PillarTextReader { lines.toList() }),
    )

    private suspend fun save(floorRaw: String): String {
        val result = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-1" },
        )(ManualParkingInput(floorRaw = floorRaw))
        return (result as SaveManualParkingResult.Saved).record.id
    }
}

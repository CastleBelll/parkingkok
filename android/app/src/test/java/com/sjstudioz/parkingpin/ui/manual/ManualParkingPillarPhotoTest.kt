package com.sjstudioz.parkingpin.ui.manual

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import com.sjstudioz.parkingpin.domain.photo.FakeParkingPhotoStore
import com.sjstudioz.parkingpin.domain.photo.PhotoSource
import com.sjstudioz.parkingpin.domain.photo.PillarTextReader
import com.sjstudioz.parkingpin.domain.photo.ReadPillarSuggestionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * `사진으로 입력` on the manual form — docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a.
 *
 * The two rules that matter most live here: what was read is a *suggestion* and is never
 * written on its own, and a read that finds nothing is indistinguishable from never
 * having taken a photo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualParkingPillarPhotoTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository
    private lateinit var photoStore: FakeParkingPhotoStore

    private val clock = MutableTestClock(epochMillis = 1_700_000_000_000L)
    private val photo = PhotoSource { ByteArrayInputStream(ByteArray(0)) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        photoStore = FakeParkingPhotoStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `what the pillar said is filled in and nothing is written`() = runTest {
        // Arrange
        val viewModel = viewModelReading(listOf("지하 3층", "A구역 142"))

        // Act
        val state = viewModel.uiState.first { it.pillarSuggestionOffered }

        // Assert — the form is filled and the cursor has somewhere to go.
        assertEquals("지하 3층", state.floorRaw)
        assertEquals("A구역", state.zone)
        assertEquals("142", state.spot)
        // §6a: "Nothing is auto-saved from a photo". The user has not pressed anything.
        assertNull(state.savedRecordId)
        assertNull(repository.findActive())
        assertEquals(0, photoStore.saveCount)
    }

    @Test
    fun `a read that finds nothing leaves the form exactly as it opens today`() = runTest {
        // Arrange — no text on the wall, or nothing the parser can use.
        val viewModel = viewModelReading(listOf("엘리베이터"))

        // Act
        advanceUntilIdle()
        val state = viewModel.uiState.value

        // Assert — §6a: "No message, no spinner left behind, no 인식 실패 dialog". There is
        // no field on this state that could carry one.
        assertEquals("", state.floorRaw)
        assertEquals("", state.zone)
        assertEquals("", state.spot)
        assertFalse(state.pillarSuggestionOffered)
        assertNull(repository.findActive())
    }

    @Test
    fun `a recogniser that takes too long is the same silence`() = runTest {
        // Arrange — §6a puts a slow model and a blank wall in the same bucket.
        val viewModel = viewModelReading(
            reader = {
                kotlinx.coroutines.delay(10_000L)
                listOf("B3")
            },
            timeoutMillis = 50L,
        )

        // Act
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.uiState.value.pillarSuggestionOffered)
        assertEquals("", viewModel.uiState.value.floorRaw)
    }

    @Test
    fun `what the user typed is never overwritten by what the camera read`() = runTest {
        // Arrange — the read is slow enough that the user gets there first.
        val viewModel = viewModelReading(
            reader = {
                kotlinx.coroutines.delay(100L)
                listOf("B3")
            },
            timeoutMillis = 10_000L,
        )
        viewModel.onFloorChange("B1")

        // Act
        val state = viewModel.uiState.first { it.pillarSuggestionOffered }

        // Assert
        assertEquals("B1", state.floorRaw)
    }

    @Test
    fun `saving keeps the photo on the record it became`() = runTest {
        // Arrange — §6a: the photo is attached, not read and thrown away.
        val viewModel = viewModelReading(listOf("B3"))
        viewModel.uiState.first { it.pillarSuggestionOffered }

        // Act
        viewModel.onSave()
        val saved = viewModel.uiState.first { it.savedRecordId != null }

        // Assert
        val record = repository.find(requireNotNull(saved.savedRecordId))
        assertEquals("B3", record?.floor?.displayLabel)
        assertNotNull(record?.photoRelativePath)
    }

    private fun viewModelReading(
        lines: List<String>,
        timeoutMillis: Long = ReadPillarSuggestionUseCase.DEFAULT_TIMEOUT_MILLIS,
    ): ManualParkingViewModel = viewModelReading({ lines }, timeoutMillis)

    private fun viewModelReading(
        reader: PillarTextReader,
        timeoutMillis: Long,
    ): ManualParkingViewModel = ManualParkingViewModel(
        saveManualParking = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { UUID.randomUUID().toString() },
        ),
        pillarPhoto = PillarPhotoEntry(
            photo = photo,
            readSuggestion = ReadPillarSuggestionUseCase(reader, timeoutMillis),
            attachPhoto = AttachParkingPhotoUseCase(repository, photoStore, clock),
        ),
        clock = clock,
    )
}

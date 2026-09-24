package com.sjstudioz.parkingpin.domain.parking

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.CleanUpOrphanPhotosUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.DeleteParkingHistoryUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.DeleteParkingRecordUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingResult
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import com.sjstudioz.parkingpin.domain.photo.FakeParkingPhotoStore
import com.sjstudioz.parkingpin.domain.photo.PhotoSource
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deleting a record must delete its photo. A photo that outlives its record is sensitive
 * local data (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §1) the user can no longer see, and
 * therefore can no longer delete.
 */
class DeleteParkingPhotoCleanupTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository

    private val start = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = start)
    private val photos = FakeParkingPhotoStore()
    private val source = PhotoSource { ByteArrayInputStream(byteArrayOf(0)) }

    private var nextId = 0

    private lateinit var save: SaveManualParkingUseCase
    private lateinit var end: EndParkingUseCase
    private lateinit var attach: AttachParkingPhotoUseCase
    private lateinit var deleteRecord: DeleteParkingRecordUseCase
    private lateinit var deleteHistory: DeleteParkingHistoryUseCase
    private lateinit var cleanUpOrphans: CleanUpOrphanPhotosUseCase

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        save = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-${nextId++}" },
        )
        end = EndParkingUseCase(repository, clock)
        attach = AttachParkingPhotoUseCase(repository, photos, clock)
        deleteRecord = DeleteParkingRecordUseCase(repository, photos)
        cleanUpOrphans = CleanUpOrphanPhotosUseCase(repository, photos)
        deleteHistory = DeleteParkingHistoryUseCase(repository, cleanUpOrphans)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun parkWithPhoto(): ParkingRecord {
        val record = (save(ManualParkingInput()) as SaveManualParkingResult.Saved).record
        attach(record.id, source)
        return record
    }

    @Test
    fun `deleting a record deletes the photo it held`() = runTest {
        val record = parkWithPhoto()

        deleteRecord(record.id)

        assertNull(repository.find(record.id))
        assertTrue(photos.stored.isEmpty())
    }

    @Test
    fun `deleting a record that never had a photo is not an error`() = runTest {
        val record = (save(ManualParkingInput()) as SaveManualParkingResult.Saved).record

        deleteRecord(record.id)

        assertNull(repository.find(record.id))
    }

    @Test
    fun `deleting a record that is already gone touches nothing`() = runTest {
        val record = parkWithPhoto()

        deleteRecord("never-existed")

        assertEquals(setOf(FakeParkingPhotoStore.pathFor(record.id)), photos.stored)
    }

    @Test
    fun `clearing history deletes the photos of completed records`() = runTest {
        val completed = parkWithPhoto()
        clock.epochMillis = start + 60_000L
        end()
        val active = parkWithPhoto()

        deleteHistory()

        // The active parking is not history, so neither it nor its photo is touched.
        assertEquals(active.id, repository.findActive()?.id)
        assertEquals(setOf(FakeParkingPhotoStore.pathFor(active.id)), photos.stored)
        assertNull(repository.find(completed.id))
    }

    /**
     * The orphan case: a photo written just before the process died, so the row that would
     * have named it was never updated.
     */
    @Test
    fun `the sweep removes a photo no record points at`() = runTest {
        val record = parkWithPhoto()
        photos.stored += "parking-photos/left-behind.jpg"

        cleanUpOrphans()

        assertEquals(setOf(FakeParkingPhotoStore.pathFor(record.id)), photos.stored)
    }

    @Test
    fun `the sweep on an empty database clears everything stored`() = runTest {
        photos.stored += "parking-photos/left-behind.jpg"

        cleanUpOrphans()

        assertTrue(photos.stored.isEmpty())
    }
}

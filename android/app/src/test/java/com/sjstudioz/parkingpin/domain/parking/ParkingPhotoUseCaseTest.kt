package com.sjstudioz.parkingpin.domain.parking

import com.sjstudioz.parkingpin.data.parking.ParkingDatabase
import com.sjstudioz.parkingpin.data.parking.RoomParkingRepository
import com.sjstudioz.parkingpin.data.parking.createTestParkingDatabase
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoResult
import com.sjstudioz.parkingpin.domain.parking.usecase.AttachParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.EndParkingUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.ManualParkingInput
import com.sjstudioz.parkingpin.domain.parking.usecase.RemoveParkingPhotoUseCase
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingResult
import com.sjstudioz.parkingpin.domain.parking.usecase.SaveManualParkingUseCase
import com.sjstudioz.parkingpin.domain.photo.FakeParkingPhotoStore
import com.sjstudioz.parkingpin.domain.photo.PhotoSaveResult
import com.sjstudioz.parkingpin.domain.photo.PhotoSource
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** FR-007 attached to and removed from a real record. */
class ParkingPhotoUseCaseTest {

    private lateinit var database: ParkingDatabase
    private lateinit var repository: RoomParkingRepository

    private val start = 1_700_000_000_000L
    private val clock = MutableTestClock(epochMillis = start)
    private val photos = FakeParkingPhotoStore()
    private val source = PhotoSource { ByteArrayInputStream(byteArrayOf(0)) }

    private lateinit var save: SaveManualParkingUseCase
    private lateinit var attach: AttachParkingPhotoUseCase
    private lateinit var remove: RemoveParkingPhotoUseCase

    @Before
    fun setUp() {
        database = createTestParkingDatabase()
        repository = RoomParkingRepository(database.parkingRecordDao())
        save = SaveManualParkingUseCase(
            repository = repository,
            locationProvider = { null },
            clock = clock,
            idGenerator = { "record-1" },
        )
        attach = AttachParkingPhotoUseCase(repository, photos, clock)
        remove = RemoveParkingPhotoUseCase(repository, photos, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun savedRecord(): ParkingRecord =
        (save(ManualParkingInput(floorRaw = "B3")) as SaveManualParkingResult.Saved).record

    @Test
    fun `attaching stores the photo and points the record at it`() = runTest {
        val record = savedRecord()
        clock.epochMillis = start + 60_000L

        val result = attach(record.id, source)

        assertTrue(result is AttachParkingPhotoResult.Attached)
        val stored = repository.find(record.id)!!
        assertEquals(FakeParkingPhotoStore.pathFor(record.id), stored.photoRelativePath)
        assertEquals(start + 60_000L, stored.updatedAtMillis)
        assertEquals(record.revision + 1, stored.revision)
        // The record's own facts are untouched by adding a photo to it.
        assertEquals(record.startedAtMillis, stored.startedAtMillis)
        assertEquals(record.floor, stored.floor)
    }

    @Test
    fun `a second photo replaces the first, keeping one per record`() = runTest {
        val record = savedRecord()

        attach(record.id, source)
        attach(record.id, source)

        assertEquals(setOf(FakeParkingPhotoStore.pathFor(record.id)), photos.stored)
        assertEquals(2, photos.saveCount)
    }

    @Test
    fun `attaching to a record that is gone stores nothing`() = runTest {
        val result = attach("never-existed", source)

        assertEquals(AttachParkingPhotoResult.RecordGone, result)
        assertEquals(0, photos.saveCount)
        assertTrue(photos.stored.isEmpty())
    }

    /**
     * A photo is optional and a parking record is not: a failed save must cost the record
     * nothing (CLAUDE.md — a denied permission or a failure here is not an app failure).
     */
    @Test
    fun `a failed save leaves the record exactly as it was`() = runTest {
        val record = savedRecord()
        photos.failWith = PhotoSaveResult.Failed.Reason.STORAGE

        val result = attach(record.id, source)

        assertEquals(
            AttachParkingPhotoResult.Failed(PhotoSaveResult.Failed.Reason.STORAGE),
            result,
        )
        assertEquals(record, repository.find(record.id))
        assertTrue(photos.stored.isEmpty())
    }

    @Test
    fun `removing clears the path and deletes the file`() = runTest {
        val record = savedRecord()
        attach(record.id, source)
        clock.epochMillis = start + 120_000L

        val updated = remove(record.id)

        assertNull(updated?.photoRelativePath)
        assertNull(repository.find(record.id)?.photoRelativePath)
        assertTrue(photos.stored.isEmpty())
        assertEquals(start + 120_000L, repository.find(record.id)?.updatedAtMillis)
    }

    @Test
    fun `removing a photo that was never there changes nothing`() = runTest {
        val record = savedRecord()

        val updated = remove(record.id)

        assertNull(updated)
        assertEquals(record, repository.find(record.id))
    }

    /** The common case in this build: no photo was ever added, and everything still works. */
    @Test
    fun `a record with no photo ends and reads back normally`() = runTest {
        val record = savedRecord()
        clock.epochMillis = start + 3_600_000L

        val ended = EndParkingUseCase(repository, clock)()

        assertNull(ended?.photoRelativePath)
        assertEquals(start + 3_600_000L, ended?.endedAtMillis)
        assertNull(repository.findActive())
        assertEquals(1, repository.observeCompleted(limit = -1).first().size)
        assertEquals(record.id, ended?.id)
    }
}

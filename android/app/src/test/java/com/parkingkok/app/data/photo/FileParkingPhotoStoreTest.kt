package com.parkingkok.app.data.photo

import com.parkingkok.app.domain.photo.PhotoSaveResult
import com.parkingkok.app.domain.photo.PhotoScale
import com.parkingkok.app.domain.photo.PhotoSource
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The store's own rules: what a photo is named, and what happens when the decode or the
 * disk says no. The decoding itself is [BitmapPhotos]' and needs a device; the encoder is
 * faked here so these rules can be checked without one.
 */
class FileParkingPhotoStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = PhotoSource { ByteArrayInputStream(byteArrayOf(0)) }

    private var requestedLongEdge: Int? = null
    private var encoded: ByteArray? = byteArrayOf(1, 2, 3)

    private val directory: File
        get() = File(temporaryFolder.root, ParkingPhotoFiles.DIRECTORY_NAME)

    private fun store(files: ParkingPhotoFiles = ParkingPhotoFiles(directory)) =
        FileParkingPhotoStore(
            files = files,
            encoder = { _, maxLongEdge ->
                requestedLongEdge = maxLongEdge
                encoded
            },
            dispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun `a saved photo is named after its record, so a second save replaces it`() = runTest {
        val first = store().save("record-1", source)
        encoded = byteArrayOf(4, 4)
        val second = store().save("record-1", source)

        assertEquals(PhotoSaveResult.Saved("parking-photos/record-1.jpg"), first)
        assertEquals(PhotoSaveResult.Saved("parking-photos/record-1.jpg"), second)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun `the encoder is asked for the FR-007 long edge`() = runTest {
        store().save("record-1", source)

        assertEquals(PhotoScale.MAX_LONG_EDGE, requestedLongEdge)
    }

    @Test
    fun `an undecodable image is reported, and nothing is written`() = runTest {
        encoded = null

        val result = store().save("record-1", source)

        assertEquals(
            PhotoSaveResult.Failed(PhotoSaveResult.Failed.Reason.UNREADABLE),
            result,
        )
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a directory that cannot be written reports a storage failure`() = runTest {
        // A regular file where the photo directory should be: creating it must fail.
        directory.parentFile!!.mkdirs()
        directory.writeBytes(byteArrayOf(0))

        val result = store().save("record-1", source)

        assertEquals(PhotoSaveResult.Failed(PhotoSaveResult.Failed.Reason.STORAGE), result)
    }

    @Test
    fun `a record id that cannot name a file is refused rather than escaping the directory`() =
        runTest {
            val result = store().save("../../evil", source)

            assertEquals(PhotoSaveResult.Saved("parking-photos/evil.jpg"), result)
            assertEquals(listOf("evil.jpg"), directory.listFiles()!!.map { it.name })
        }

    @Test
    fun `an id with nothing usable in it stores nothing`() = runTest {
        val result = store().save("../..", source)

        assertEquals(PhotoSaveResult.Failed(PhotoSaveResult.Failed.Reason.STORAGE), result)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `delete and the orphan sweep reach the files`() = runTest {
        val saved = store().save("record-1", source) as PhotoSaveResult.Saved
        encoded = byteArrayOf(5)
        store().save("record-2", source)

        store().retainOnly(setOf(saved.relativePath))
        assertEquals(listOf("record-1.jpg"), directory.listFiles()!!.map { it.name })

        store().delete(saved.relativePath)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }
}

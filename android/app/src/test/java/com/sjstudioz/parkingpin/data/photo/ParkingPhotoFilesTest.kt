package com.sjstudioz.parkingpin.data.photo

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Where a parking photo lands, what replaces it, and what sweeps it away. */
class ParkingPhotoFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val directory: File
        get() = File(temporaryFolder.root, ParkingPhotoFiles.DIRECTORY_NAME)

    private val files: ParkingPhotoFiles
        get() = ParkingPhotoFiles(directory)

    @Test
    fun `a write creates the directory and returns the path to store`() {
        val path = files.write("a.jpg", byteArrayOf(1, 2, 3))

        assertEquals("parking-photos/a.jpg", path)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(directory, "a.jpg").readBytes())
    }

    /**
     * The half-written file is the failure this class exists to prevent: the record's path
     * is set only after the move, so a reader sees no photo or the whole photo.
     */
    @Test
    fun `a write leaves no temporary file behind`() {
        files.write("a.jpg", byteArrayOf(1, 2, 3))

        val leftovers = directory.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun `a second write replaces the first in place, keeping one photo per record`() {
        files.write("a.jpg", byteArrayOf(1))
        files.write("a.jpg", byteArrayOf(2, 2))

        assertEquals(1, directory.listFiles()!!.size)
        assertArrayEquals(byteArrayOf(2, 2), File(directory, "a.jpg").readBytes())
    }

    @Test
    fun `delete removes the file, and says nothing about paths it does not own`() {
        val path = files.write("a.jpg", byteArrayOf(1))!!

        files.delete(path)
        files.delete(null)
        files.delete("parking-photos/never-existed.jpg")

        assertFalse(File(directory, "a.jpg").exists())
    }

    @Test
    fun `retainOnly deletes the photos no record points at`() {
        val kept = files.write("kept.jpg", byteArrayOf(1))!!
        files.write("orphan.jpg", byteArrayOf(2))

        files.retainOnly(setOf(kept))

        assertTrue(File(directory, "kept.jpg").exists())
        assertFalse(File(directory, "orphan.jpg").exists())
    }

    /** What a process killed mid-write leaves: the sweep is the only thing that sees it. */
    @Test
    fun `retainOnly also removes a temporary file a crashed write left behind`() {
        directory.mkdirs()
        File(directory, "a.jpg.tmp").writeBytes(byteArrayOf(9))
        val kept = files.write("a.jpg", byteArrayOf(1))!!

        files.retainOnly(setOf(kept))

        assertEquals(listOf("a.jpg"), directory.listFiles()!!.map { it.name })
    }

    @Test
    fun `an empty referenced path cannot keep a file alive`() {
        files.write("orphan.jpg", byteArrayOf(2))

        files.retainOnly(setOf(""))

        assertFalse(File(directory, "orphan.jpg").exists())
    }

    @Test
    fun `retainOnly on an empty store does not fail`() {
        files.retainOnly(setOf("parking-photos/a.jpg"))
    }

    /**
     * The stored path comes out of the database, which is a place a bad value can end up.
     * Resolving it must not reach the database file next door.
     */
    @Test
    fun `a path that escapes the photo directory is refused`() {
        val outside = File(temporaryFolder.root, "secret.db")
        outside.writeBytes(byteArrayOf(7))

        files.delete("parking-photos/../secret.db")

        assertNull(files.resolve("parking-photos/../secret.db"))
        assertNull(files.resolve("../secret.db"))
        assertNull(files.resolve("parking-photos/nested/a.jpg"))
        assertTrue(outside.exists())
    }

    @Test
    fun `a path from some other directory is not ours to resolve`() {
        assertNull(files.resolve("detection/traces/a.json"))
        assertNull(files.resolve("a.jpg"))
    }
}

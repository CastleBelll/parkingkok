package com.sjstudioz.parkingpin.data.photo

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The file half of [com.sjstudioz.parkingpin.domain.photo.ParkingPhotoStore]: where a parking
 * photo lives on disk, and how it gets there without ever being half-written.
 *
 * Plain `java.io`/`java.nio` on purpose — no Android type appears here, so every rule in
 * this file (atomic replace, orphan sweep, traversal refusal) is covered by an ordinary
 * JVM unit test against a temporary directory rather than by a device run.
 *
 * ## Why the write is a move
 *
 * A photo is written to `<name>.tmp` and then `rename(2)`d over its final name. The
 * record's `photoRelativePath` is set only after that returns, so the two states a reader
 * can observe are "no photo" and "the whole photo" — never a truncated JPEG, which is
 * what a direct write interrupted by process death leaves behind.
 */
class ParkingPhotoFiles(private val directory: File) {

    /**
     * Writes [bytes] as [fileName] and returns the path to store, or null if it could not
     * be written.
     */
    fun write(fileName: String, bytes: ByteArray): String? {
        val target = fileIn(fileName) ?: return null
        val temporary = File(directory, "$fileName$TEMP_SUFFIX")
        return try {
            Files.createDirectories(directory.toPath())
            temporary.writeBytes(bytes)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            relativePathOf(fileName)
        } catch (_: IOException) {
            // A partial temp file would be swept by retainOnly eventually; removing it now
            // keeps a repeatedly failing save from filling the directory.
            temporary.delete()
            null
        }
    }

    /** Deletes the file [relativePath] names. Absent, null and foreign paths are no-ops. */
    fun delete(relativePath: String?) {
        resolve(relativePath)?.delete()
    }

    /** Deletes every stored photo whose path is not in [relativePaths], and any temp file. */
    fun retainOnly(relativePaths: Set<String>) {
        // An empty string is not a path; it must not be able to keep a file alive.
        val kept = relativePaths.filterTo(mutableSetOf()) { it.isNotEmpty() }
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(TEMP_SUFFIX) || relativePathOf(file.name) !in kept) {
                file.delete()
            }
        }
    }

    /**
     * The file [relativePath] names, or null when it is not one of ours.
     *
     * The path comes out of the database, and a database is a place a bad value can be
     * put; resolving `../../databases/parkingpin-parking.db` to a real file and deleting
     * it would be this class's own doing, so anything that escapes [directory] is refused.
     */
    fun resolve(relativePath: String?): File? {
        val path = relativePath ?: return null
        if (!path.startsWith(DIRECTORY_PREFIX)) return null
        return fileIn(path.removePrefix(DIRECTORY_PREFIX))
    }

    private fun fileIn(fileName: String): File? {
        if (fileName.isEmpty() || fileName != File(fileName).name) return null
        return File(directory, fileName)
    }

    private fun relativePathOf(fileName: String): String = DIRECTORY_PREFIX + fileName

    companion object {
        /** The directory name under `filesDir`, and the prefix of every stored path. */
        const val DIRECTORY_NAME: String = "parking-photos"

        private const val DIRECTORY_PREFIX = "$DIRECTORY_NAME/"

        private const val TEMP_SUFFIX = ".tmp"

        /** `filesDir` is app-private storage (docs/06 §4). */
        fun defaultDirectory(filesDir: File): File = File(filesDir, DIRECTORY_NAME)
    }
}

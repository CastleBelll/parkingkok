package com.parkingkok.app.domain.photo

import java.io.InputStream

/**
 * A photo the user just picked or shot, readable more than once.
 *
 * Reading twice is not incidental: sizing the decode needs a bounds pass before the real
 * one ([PhotoScale]), and a `content://` stream cannot be rewound. Each call returns a
 * fresh stream, and the caller closes it.
 */
fun interface PhotoSource {
    fun openStream(): InputStream
}

/** Outcome of [ParkingPhotoStore.save]. */
sealed interface PhotoSaveResult {

    /** [relativePath] is what goes in `ParkingRecord.photoRelativePath`. */
    data class Saved(val relativePath: String) : PhotoSaveResult

    /**
     * Nothing was stored. [reason] is for the user-facing message and for a log line —
     * it never carries a path or a coordinate.
     */
    data class Failed(val reason: Reason) : PhotoSaveResult {

        enum class Reason {
            /** The stream could not be read, or held no image this device can decode. */
            UNREADABLE,

            /** Decoding, re-encoding or writing failed — out of space is the usual one. */
            STORAGE,
        }
    }
}

/**
 * App-private photo storage — FR-007, and docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §4
 * "photo: app-private files directory".
 *
 * A photo never leaves the device: docs/07_FIREBASE_BACKEND.md §2 says "no Firebase
 * Storage", and §1 keeps parking photos local. Nothing in this interface returns a
 * shareable URI, and the relative paths it deals in are stored, never logged.
 */
interface ParkingPhotoStore {

    /**
     * Downsamples [source] and stores it as [recordId]'s photo, replacing any previous one.
     *
     * One photo per record (FR-007), so the name is derived from [recordId] and a second
     * save overwrites the first rather than accumulating.
     */
    suspend fun save(recordId: String, source: PhotoSource): PhotoSaveResult

    /** Removes a stored photo. A null or already-absent path is a no-op, not an error. */
    suspend fun delete(relativePath: String?)

    /**
     * Deletes every stored photo that is not in [relativePaths].
     *
     * The orphan sweep. A photo outlives its record whenever a write lands and the
     * process dies before the row is updated, and FR-007 photos are sensitive local data
     * (docs/06 §1) — leaving them on disk unreferenced means they are never deleted by
     * anything the user can see.
     */
    suspend fun retainOnly(relativePaths: Set<String>)
}

package com.sjstudioz.parkingpin.trace

import android.content.Context
import android.util.Log
import com.sjstudioz.parkingpin.domain.trace.TraceSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Where recorded traces live, behind an interface so the recorder is testable without a
 * device (docs/16_CODING_STANDARDS.md §2).
 */
interface TraceStore {

    /** Newest first. A file that cannot be parsed is skipped, never thrown. */
    fun list(): List<TraceSession>

    fun read(sessionId: String): TraceSession?

    /** @return null on success, or a short, coordinate-free reason string on failure. */
    fun write(session: TraceSession): String?

    /**
     * Applies the rolling cap, oldest session first.
     *
     * @param keepSessionId a session that must survive regardless — the one being written
     *   into right now.
     * @return how many sessions were discarded.
     */
    fun prune(keepSessionId: String?): Int

    /**
     * Removes one session's file.
     *
     * Idempotent and never an error: the caller is on a path that must not be able to
     * break a detection callback, and a file already gone is the wanted outcome.
     *
     * @return true when this call is the one that removed it, so a counter cannot move
     *   twice for the same session.
     */
    fun delete(sessionId: String): Boolean

    /**
     * Swaps a closed session for the fragments a human cut it into
     * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "원본은 조각으로 대체된다").
     *
     * Openness is not this layer's to judge — which session is being appended to is
     * recorded beside the detection state, so [TraceRecorder] checks it before calling.
     *
     * @return null on success, or a short, coordinate-free reason on failure.
     */
    fun replace(sessionId: String, fragments: List<TraceSession>): String?
}

/**
 * Writes one JSON file per session under `filesDir/detection/traces/`, so a trace comes
 * off the device with
 *
 * ```
 * adb shell run-as com.sjstudioz.parkingpin cat files/detection/traces/<sessionId>.json
 * ```
 *
 * the same way the diagnostics report does, and for the same reason: `run-as` reaches the
 * app's own private data directory on a stock device with no root, but only under
 * `filesDir` and only for a debuggable build.
 *
 * One file per session rather than one growing log, because the rolling cap evicts whole
 * sessions and a single file would have to be rewritten to drop its oldest half.
 *
 * Every write goes through a temporary file and a rename, so a trace pulled while the app
 * is recording is either the old version or the new one, never half of each.
 *
 * Best-effort throughout: a failed trace write must never take down a detection callback,
 * so failures are returned as strings and surface in the diagnostics report.
 */
class FileTraceStore(
    private val directory: File,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : TraceStore {

    override fun list(): List<TraceSession> = sessionFiles()
        .mapNotNull { parse(it) }
        .sortedByDescending { it.startedAt }

    override fun read(sessionId: String): TraceSession? {
        val file = fileFor(sessionId) ?: return null
        return if (file.exists()) parse(file) else null
    }

    override fun write(session: TraceSession): String? {
        val file = fileFor(session.sessionId) ?: return "InvalidSessionId"
        return try {
            directory.mkdirs()
            val temporary = File(directory, file.name + TEMPORARY_SUFFIX)
            temporary.writeText(json.encodeToString(session))
            if (!temporary.renameTo(file)) {
                // renameTo does not replace on every filesystem; fall back rather than
                // leaving the caller with a stale trace and no explanation.
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            null
        } catch (error: IOException) {
            failure("IOException(${error.javaClass.simpleName})")
        } catch (error: SecurityException) {
            failure("SecurityException")
        }
    }

    /**
     * The count and size checks run on directory metadata alone. Parsing every session to
     * find the oldest would mean reading the whole recording back on every event, so the
     * files are only opened once a cap has actually been exceeded — which is rare.
     */
    override fun prune(keepSessionId: String?): Int {
        val files = sessionFiles()
        if (files.size <= maxSessions && files.sumOf { it.length() } <= maxTotalBytes) return 0

        // Oldest first. `startedAt` is the honest ordering, but it costs a parse per file;
        // lastModified is already in the directory entry and orders identically, because a
        // session is only ever written while it is the open one.
        //
        // The newest session is never evictable, whatever the caps say. One recording can
        // exceed the byte cap on its own — [TraceSessionBoundaryPolicy] bounds how large it
        // can get, not how it compares to the cap — and without this the loop below would
        // empty the whole directory to satisfy a limit it cannot satisfy. Losing the most
        // recent trip is the one loss that cannot be made up by recording more.
        val evictable = files
            .sortedBy { it.lastModified() }
            .dropLast(1)
            .filter { it.nameWithoutExtension != keepSessionId }

        var remainingCount = files.size
        var remainingBytes = files.sumOf { it.length() }
        var discarded = 0

        for (file in evictable) {
            if (remainingCount <= maxSessions && remainingBytes <= maxTotalBytes) break
            val size = file.length()
            if (!deleteQuietly(file)) continue
            remainingCount--
            remainingBytes -= size
            discarded++
        }
        if (discarded > 0) Log.i(TAG, "trace rolling cap discarded $discarded session(s)")
        return discarded
    }

    override fun delete(sessionId: String): Boolean {
        val file = fileFor(sessionId) ?: return false
        return file.exists() && deleteQuietly(file)
    }

    /**
     * Fragments first, parent second. A process death between the two leaves the events on
     * disk twice, which a person can see and undo; the other order would lose a recorded
     * trip outright, which nobody can.
     *
     * The rolling cap is re-applied by the caller rather than here, because one session
     * became two and the eviction that follows has to be counted the same way every other
     * eviction is.
     */
    override fun replace(sessionId: String, fragments: List<TraceSession>): String? {
        if (read(sessionId) == null) return "NotFound"
        fragments.forEach { fragment ->
            val failure = write(fragment)
            if (failure != null) return failure
        }
        return if (delete(sessionId)) null else "DeleteFailed"
    }

    private fun sessionFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_EXTENSION) }?.toList().orEmpty()

    /**
     * Session ids are generated as UUIDs, so this can only reject a caller that made one
     * up. It is here anyway: the id reaches the filesystem, and a `..` in it would put the
     * write outside the traces directory.
     */
    private fun fileFor(sessionId: String): File? =
        if (SESSION_ID_PATTERN.matches(sessionId)) File(directory, sessionId + FILE_EXTENSION) else null

    /** A corrupt or half-written trace must degrade to "not there", never crash a callback. */
    private fun parse(file: File): TraceSession? = try {
        json.decodeFromString<TraceSession>(file.readText())
            .takeIf { it.schemaVersion == TraceSession.SCHEMA_VERSION }
    } catch (error: IOException) {
        Log.w(TAG, "unreadable trace file: ${error.javaClass.simpleName}")
        null
    } catch (error: SerializationException) {
        Log.w(TAG, "corrupt trace file, skipped")
        null
    } catch (error: IllegalArgumentException) {
        Log.w(TAG, "malformed trace file, skipped")
        null
    }

    private fun deleteQuietly(file: File): Boolean = try {
        file.delete()
    } catch (error: SecurityException) {
        Log.w(TAG, "trace prune blocked: SecurityException")
        false
    }

    private fun failure(reason: String): String {
        Log.e(TAG, "trace write failed: $reason")
        return reason
    }

    companion object {
        private const val TAG = "ParkingpinTrace"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val FILE_EXTENSION = ".json"
        private const val DIRECTORY_NAME = "detection/traces"

        /**
         * Roughly two weeks of ordinary movement at a handful of trips a day. Enough to
         * hold a field run's worth of evidence without ever being the reason a device
         * runs out of space.
         */
        const val DEFAULT_MAX_SESSIONS: Int = 40

        /**
         * The cap that actually binds. Session count alone would let 40 pathologically
         * long recordings grow without limit, which is the case §9's "must not fill
         * storage even if left on all day" is written against.
         */
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 4L * 1024L * 1024L

        /** `filesDir/detection/traces`, the path `run-as` can reach. */
        fun defaultDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

        private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")

        /**
         * `explicitNulls = false` is what makes the encoded file match §9's example
         * exactly: a `vehicle_enter` carries no `accuracy` key at all rather than
         * `"accuracy": null`. Decoding relies on the defaults in [TraceSession].
         */
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

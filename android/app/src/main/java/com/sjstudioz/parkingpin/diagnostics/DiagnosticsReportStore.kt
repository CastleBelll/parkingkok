package com.sjstudioz.parkingpin.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Where the diagnostics report is written, behind an interface so the export can be tested
 * without a device (docs/16_CODING_STANDARDS.md §2).
 */
interface DiagnosticsReportStore {
    /** @return null on success, or a short, coordinate-free reason string on failure. */
    fun write(report: DiagnosticsReport): String?
}

/**
 * Writes the report to a JSON file under `filesDir`, so it can be pulled with
 *
 * ```
 * adb shell run-as com.sjstudioz.parkingpin cat files/detection/diagnostics.json
 * ```
 *
 * `run-as` can only read the app's own private data directory on a non-rooted device, and
 * only for a debuggable build — hence `filesDir` and not the cache or external storage.
 *
 * Written through a temporary file and renamed, so a report pulled while the app is
 * writing is either the old one or the new one, never half of each.
 *
 * Best-effort by design: a diagnostics write must never take down a detection callback.
 * A failure is logged and surfaces as `lastSessionFailure` in the next report rather than
 * being thrown at the receiver.
 */
class FileDiagnosticsReportStore(private val file: File) : DiagnosticsReportStore {

    override fun write(report: DiagnosticsReport): String? = try {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + TEMPORARY_SUFFIX)
        temporary.writeText(json.encodeToString(report))
        if (!temporary.renameTo(file)) {
            // renameTo does not replace on every filesystem; fall back rather than
            // leaving the caller with a stale report and no explanation.
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        null
    } catch (error: IOException) {
        val reason = "IOException(${error.javaClass.simpleName})"
        Log.e(TAG, "diagnostics export failed: $reason")
        reason
    } catch (error: SecurityException) {
        Log.e(TAG, "diagnostics export failed: SecurityException")
        "SecurityException"
    }

    companion object {
        private const val TAG = "ParkingpinDiagnostics"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val DIRECTORY_NAME = "detection"
        private const val FILE_NAME = "diagnostics.json"

        /** `filesDir/detection/diagnostics.json`, the path `run-as` can reach. */
        fun defaultFile(context: Context): File =
            File(File(context.filesDir, DIRECTORY_NAME), FILE_NAME)

        private val json = Json { prettyPrint = true; encodeDefaults = true }
    }
}

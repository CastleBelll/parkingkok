package com.sjstudioz.parkingpin.data.photo

import android.content.Context
import java.io.File

/**
 * Where the camera writes, for the two paths that care.
 *
 * One fixed name in the cache directory, reused by every capture: a camera photo is
 * full-size and transient — it is downsampled into app-private storage immediately — and
 * a fixed name means the largest thing a capture can leave behind is one file the OS is
 * free to evict.
 *
 * The camera writes here and the picker hands the same URI back to whoever asked, so
 * nothing else needs to know the name.
 */
internal object CameraCaptureFile {

    fun of(context: Context): File = File(File(context.cacheDir, DIRECTORY), FILE_NAME)

    /** Must match `res/xml/file_paths.xml`. */
    const val DIRECTORY = "camera"

    const val FILE_NAME = "capture.jpg"
}

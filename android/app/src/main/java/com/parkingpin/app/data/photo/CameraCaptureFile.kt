package com.parkingpin.app.data.photo

import android.content.Context
import com.parkingpin.app.domain.photo.PhotoSource
import java.io.File
import java.io.FileInputStream

/**
 * Where the camera writes, for the two paths that care.
 *
 * One fixed name in the cache directory, reused by every capture: a camera photo is
 * full-size and transient — it is downsampled into app-private storage immediately — and
 * a fixed name means the largest thing a capture can leave behind is one file the OS is
 * free to evict.
 *
 * It is shared because `사진으로 입력` on the confirmation screen shoots on one screen and
 * is read on the next (docs/10 §7a, docs/02 §6a). A second constant on the reading side
 * would be the kind of duplication that works until someone renames one of them.
 */
internal object CameraCaptureFile {

    fun of(context: Context): File = File(File(context.cacheDir, DIRECTORY), FILE_NAME)

    /** The last capture, re-openable. The stream throws when there is no file yet. */
    fun sourceIn(context: Context): PhotoSource = PhotoSource { FileInputStream(of(context)) }

    /** Must match `res/xml/file_paths.xml`. */
    const val DIRECTORY = "camera"

    const val FILE_NAME = "capture.jpg"
}

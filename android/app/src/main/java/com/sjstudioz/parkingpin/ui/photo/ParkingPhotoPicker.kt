package com.sjstudioz.parkingpin.ui.photo

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.sjstudioz.parkingpin.R
import com.sjstudioz.parkingpin.data.photo.CameraCaptureFile
import com.sjstudioz.parkingpin.domain.photo.PhotoSource
import java.io.IOException

/**
 * `사진 추가`: the two ways a parking photo gets into the app (FR-007,
 * docs/04_ANDROID_IMPLEMENTATION.md §11 "system picker/camera").
 *
 * ## Permissions
 *
 * Neither path asks for one. The photo picker runs in the system's process and hands back
 * a single granted URI, so `READ_MEDIA_IMAGES` is not involved. `ACTION_IMAGE_CAPTURE`
 * needs `CAMERA` only from an app that *declares* it in its manifest — declaring it would
 * create the requirement, not satisfy one — so this app does not, and
 * docs/04_ANDROID_IMPLEMENTATION.md §19's "CAMERA only when the flow requires it" is met
 * by it never being required.
 *
 * It follows that there is no denied-permission path to handle here, which is the point:
 * a parking record with no photo is a complete record.
 *
 * This composable owns no state beyond the in-flight capture URI; what to do with the
 * chosen image is the caller's ViewModel's business.
 */
@Composable
fun ParkingPhotoPicker(
    visible: Boolean,
    onDismiss: () -> Unit,
    onPhotoSelected: (PhotoSource) -> Unit,
    onCameraUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val pickFromAlbum = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onPhotoSelected(contentPhotoSource(context, uri))
    }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { written ->
        val uri = captureUri
        captureUri = null
        if (written && uri != null) onPhotoSelected(contentPhotoSource(context, uri))
    }

    // The album-or-camera question is asked on every path, including `사진으로 입력`: the
    // pillar may already have been photographed a minute ago, and a button that says camera
    // is not the same as a promise never to offer the album.
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_pick_title)) },
        text = { Text(stringResource(R.string.photo_pick_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    val uri = captureFileUri(context)
                    captureUri = uri
                    if (uri == null) {
                        onCameraUnavailable()
                        return@TextButton
                    }
                    try {
                        takePhoto.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        // No camera app. Not a failure of the app — the album is still there.
                        captureUri = null
                        onCameraUnavailable()
                    }
                },
            ) {
                Text(stringResource(R.string.photo_pick_camera))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    pickFromAlbum.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) {
                Text(stringResource(R.string.photo_pick_album))
            }
        },
    )
}

/**
 * A re-openable stream over [uri].
 *
 * Re-openable because sizing the decode needs a bounds pass first; a `content://` stream
 * cannot be rewound, so the resolver is asked again. The application context's resolver is
 * used so the source stays valid if the caller's composition goes away mid-save.
 */
private fun contentPhotoSource(context: Context, uri: Uri): PhotoSource {
    val resolver = context.applicationContext.contentResolver
    return PhotoSource {
        resolver.openInputStream(uri) ?: throw IOException("photo stream unavailable")
    }
}

/**
 * The camera on its own — `사진으로 입력` on the confirmation screen (docs/10 §7a).
 *
 * No album and no dialog: the user is standing in front of the pillar, and the one thing
 * worth doing there is photographing it. [onCaptured] runs only when a file was actually
 * written, so a cancelled camera leaves the caller exactly where it was.
 *
 * Returns the launcher to call from a button.
 */
@Composable
fun rememberCameraCapture(
    onCaptured: () -> Unit,
    onCameraUnavailable: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { written ->
        if (written) onCaptured()
    }
    return {
        val uri = captureFileUri(context)
        if (uri == null) {
            onCameraUnavailable()
        } else {
            try {
                takePhoto.launch(uri)
            } catch (_: ActivityNotFoundException) {
                // No camera app. Not a failure of the app — 직접 입력 is right there.
                onCameraUnavailable()
            }
        }
    }
}

/** [CameraCaptureFile], as a URI the camera is allowed to write to. */
private fun captureFileUri(context: Context): Uri? = try {
    val file = CameraCaptureFile.of(context)
    file.parentFile?.mkdirs()
    FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
} catch (_: IllegalArgumentException) {
    null
}

/** Must match the provider authority in the manifest. */
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

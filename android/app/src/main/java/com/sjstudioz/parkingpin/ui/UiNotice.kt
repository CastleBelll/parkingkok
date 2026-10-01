package com.sjstudioz.parkingpin.ui

import androidx.annotation.StringRes
import com.sjstudioz.parkingpin.R

/**
 * Something a screen needs to say once, in response to something the user just did.
 *
 * An enum rather than a message string so nothing that reaches the UI layer can carry a
 * coordinate or a file path (docs/00_CORE_RULES.md Privacy, docs/06 §1). The screen turns
 * it into copy; a ViewModel never formats one.
 */
enum class UiNotice {

    /** The chosen file held no image this device can decode. */
    PHOTO_UNREADABLE,

    /** Decoding or writing failed — out of space is the usual cause. */
    PHOTO_NOT_SAVED,

    /** No camera app answered. The album is still available. */
    CAMERA_UNAVAILABLE,

    /** No installed app handles `geo:` (FR-008 is an external maps intent on Android). */
    NO_MAPS_APP,

    /** An answer to the departure question could not be written; the question stays. */
    ANSWER_NOT_SAVED,

    /** The photo was not removed — the file or the record refused the change. */
    PHOTO_NOT_REMOVED,
}

/** The copy for a notice — one mapping, so home and detail cannot word the same event twice. */
@StringRes
fun UiNotice.messageRes(): Int = when (this) {
    UiNotice.PHOTO_UNREADABLE -> R.string.notice_photo_unreadable
    UiNotice.PHOTO_NOT_SAVED -> R.string.notice_photo_not_saved
    UiNotice.CAMERA_UNAVAILABLE -> R.string.notice_camera_unavailable
    UiNotice.NO_MAPS_APP -> R.string.notice_no_maps_app
    UiNotice.ANSWER_NOT_SAVED -> R.string.notice_answer_not_saved
    UiNotice.PHOTO_NOT_REMOVED -> R.string.notice_photo_not_removed
}

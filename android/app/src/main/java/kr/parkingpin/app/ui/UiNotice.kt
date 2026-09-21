package kr.parkingpin.app.ui

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
}

package kr.parkingpin.app.domain.registration

/** What reconciliation decided to do about the Play services transition registration. */
enum class ReconcileAction {
    /** Register (or re-register) the desired transition set. */
    REGISTER,

    /** Tear down an existing registration. */
    UNREGISTER,

    /** Recorded registration already matches the desired spec — do nothing. */
    NONE,

    /** Detection is wanted but ACTIVITY_RECOGNITION is not granted. Not an app failure. */
    BLOCKED_MISSING_PERMISSION,
}

/**
 * Decides how to bring the actual Play services registration in line with the desired one.
 *
 * Pure function, no Android types — this is the heart of the process-death / reboot /
 * app-update recovery required by docs/04_ANDROID_IMPLEMENTATION.md §6, so it is unit
 * tested directly.
 *
 * Duplicate-registration prevention has two layers:
 *  1. [NONE] here, so a plain app restart issues no redundant IPC at all.
 *  2. A stable PendingIntent (fixed request code + action) in the adapter, so even a
 *     REGISTER that does run replaces the previous subscription rather than adding one.
 */
object RegistrationReconciler {

    fun decide(
        desiredEnabled: Boolean,
        recordedSpecVersion: Int?,
        currentSpecVersion: Int,
        permissionGranted: Boolean,
    ): ReconcileAction = when {
        // Tearing a registration down never needs the permission that created it.
        !desiredEnabled && recordedSpecVersion != null -> ReconcileAction.UNREGISTER
        !desiredEnabled -> ReconcileAction.NONE
        !permissionGranted -> ReconcileAction.BLOCKED_MISSING_PERMISSION
        recordedSpecVersion == currentSpecVersion -> ReconcileAction.NONE
        else -> ReconcileAction.REGISTER
    }
}

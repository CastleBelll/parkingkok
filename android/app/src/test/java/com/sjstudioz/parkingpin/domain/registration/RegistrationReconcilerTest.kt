package com.sjstudioz.parkingpin.domain.registration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reconciliation is the recovery contract from docs/04_ANDROID_IMPLEMENTATION.md §6.
 * The duplicate-registration cases are the ones that matter most.
 */
class RegistrationReconcilerTest {

    private val currentVersion = 7

    @Test
    fun enabledAndNeverRegistered_registers() {
        // Arrange / Act
        val action = decide(desiredEnabled = true, recordedSpecVersion = null, permissionGranted = true)

        // Assert
        assertEquals(ReconcileAction.REGISTER, action)
    }

    @Test
    fun enabledAndAlreadyRegisteredWithSameSpec_doesNothing() {
        // Arrange — an ordinary app restart after process death: our record survived and
        // so did the Play services subscription.
        // Act
        val action = decide(desiredEnabled = true, recordedSpecVersion = currentVersion, permissionGranted = true)

        // Assert — no second registration.
        assertEquals(ReconcileAction.NONE, action)
    }

    @Test
    fun repeatedReconcile_isIdempotent() {
        // Arrange — simulate the coordinator recording the registration after the first pass.
        var recorded: Int? = null

        // Act
        val actions = List(5) {
            val action = decide(desiredEnabled = true, recordedSpecVersion = recorded, permissionGranted = true)
            if (action == ReconcileAction.REGISTER) recorded = currentVersion
            action
        }

        // Assert — exactly one REGISTER, the rest no-ops.
        assertEquals(1, actions.count { it == ReconcileAction.REGISTER })
        assertEquals(4, actions.count { it == ReconcileAction.NONE })
    }

    @Test
    fun enabledWithStaleSpecVersion_reRegisters() {
        // Arrange — an app update changed the subscribed transition set.
        // Act
        val action = decide(desiredEnabled = true, recordedSpecVersion = currentVersion - 1, permissionGranted = true)

        // Assert
        assertEquals(ReconcileAction.REGISTER, action)
    }

    @Test
    fun enabledWithoutPermission_blocksInsteadOfFailing() {
        // Arrange / Act
        val action = decide(desiredEnabled = true, recordedSpecVersion = null, permissionGranted = false)

        // Assert — a denial is a state, not an app-wide failure.
        assertEquals(ReconcileAction.BLOCKED_MISSING_PERMISSION, action)
    }

    @Test
    fun enabledWithRecordButPermissionRevoked_blocks() {
        // Arrange — the user revoked ACTIVITY_RECOGNITION in Settings while backgrounded.
        // Act
        val action = decide(desiredEnabled = true, recordedSpecVersion = currentVersion, permissionGranted = false)

        // Assert — the stale record must not be treated as an active registration.
        assertEquals(ReconcileAction.BLOCKED_MISSING_PERMISSION, action)
    }

    @Test
    fun disabledWithExistingRegistration_unregistersEvenWithoutPermission() {
        // Arrange / Act — removing updates does not require the permission that created them.
        val action = decide(desiredEnabled = false, recordedSpecVersion = currentVersion, permissionGranted = false)

        // Assert
        assertEquals(ReconcileAction.UNREGISTER, action)
    }

    @Test
    fun disabledAndNotRegistered_doesNothing() {
        // Arrange / Act
        val action = decide(desiredEnabled = false, recordedSpecVersion = null, permissionGranted = true)

        // Assert
        assertEquals(ReconcileAction.NONE, action)
    }

    private fun decide(desiredEnabled: Boolean, recordedSpecVersion: Int?, permissionGranted: Boolean) =
        RegistrationReconciler.decide(
            desiredEnabled = desiredEnabled,
            recordedSpecVersion = recordedSpecVersion,
            currentSpecVersion = currentVersion,
            permissionGranted = permissionGranted,
        )
}

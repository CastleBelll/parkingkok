package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.domain.registration.TransitionRegistrationSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end reconciliation: the recovery paths from
 * docs/04_ANDROID_IMPLEMENTATION.md §6, with Play services faked out.
 */
class DetectionRegistrationCoordinatorTest {

    private val clock = MutableTestClock()
    private val store = DetectionStateStore(InMemoryPreferencesDataStore())
    private val registrar = FakeTransitionRegistrar()
    private val coordinator = DetectionRegistrationCoordinator(store, registrar, clock)

    @Test
    fun enablingDetection_registersOnce() = runTest {
        // Arrange / Act
        val status = coordinator.setDetectionEnabled(true)

        // Assert
        assertEquals(1, registrar.registerCalls)
        assertEquals(
            RegistrationStatus.Active(TransitionRegistrationSpec.VERSION, clock.epochMillis),
            status,
        )
    }

    @Test
    fun restartAfterProcessDeath_doesNotRegisterAgain() = runTest {
        // Arrange — detection already registered before the process was killed. `am kill`
        // leaves the Play services subscription alive, so re-registering would be waste.
        coordinator.setDetectionEnabled(true)

        // Act — what ParkingpinApplication.onCreate does on the next process start.
        val status = coordinator.reconcile()

        // Assert
        assertEquals(1, registrar.registerCalls)
        assertTrue(status is RegistrationStatus.Active)
    }

    @Test
    fun repeatedReconcile_neverAccumulatesRegistrations() = runTest {
        // Arrange
        coordinator.setDetectionEnabled(true)

        // Act
        repeat(10) { coordinator.reconcile() }

        // Assert
        assertEquals(1, registrar.registerCalls)
    }

    @Test
    fun rebootRecovery_reRegistersExactlyOnce() = runTest {
        // Arrange — the record survives the reboot, the system subscription does not.
        coordinator.setDetectionEnabled(true)

        // Act — what RegistrationRecoveryReceiver does on BOOT_COMPLETED.
        val status = coordinator.reconcileAfterSystemReset()

        // Assert
        assertEquals(2, registrar.registerCalls)
        assertTrue(status is RegistrationStatus.Active)

        // Act — a subsequent app start must not add a third.
        coordinator.reconcile()

        // Assert
        assertEquals(2, registrar.registerCalls)
    }

    @Test
    fun withoutPermission_blocksAndClearsTheStaleRecord() = runTest {
        // Arrange — the user revoked ACTIVITY_RECOGNITION after enabling detection.
        coordinator.setDetectionEnabled(true)
        registrar.permissionGranted = false

        // Act
        val status = coordinator.reconcile()

        // Assert — a state, not a crash, and the stale record is not trusted.
        assertEquals(RegistrationStatus.MissingPermission, status)
        assertNull(store.readRegistrationRecordOnce())
        assertEquals(1, registrar.registerCalls)
    }

    @Test
    fun grantingPermissionAfterDenial_registersOnTheNextReconcile() = runTest {
        // Arrange — detection wanted but permission denied at first.
        registrar.permissionGranted = false
        assertEquals(RegistrationStatus.MissingPermission, coordinator.setDetectionEnabled(true))
        assertEquals(0, registrar.registerCalls)

        // Act — the user grants it; the screen calls refresh().
        registrar.permissionGranted = true
        val status = coordinator.reconcile()

        // Assert
        assertEquals(1, registrar.registerCalls)
        assertTrue(status is RegistrationStatus.Active)
    }

    @Test
    fun disablingDetection_unregistersAndForgetsTheRecord() = runTest {
        // Arrange
        coordinator.setDetectionEnabled(true)

        // Act
        val status = coordinator.setDetectionEnabled(false)

        // Assert
        assertEquals(1, registrar.unregisterCalls)
        assertEquals(RegistrationStatus.Disabled, status)
        assertNull(store.readRegistrationRecordOnce())
    }

    @Test
    fun registrationFailure_isReportedAndNotRecordedAsActive() = runTest {
        // Arrange — Play services unavailable on this device.
        registrar.registerFailure = "ApiException statusCode=17"

        // Act
        val status = coordinator.setDetectionEnabled(true)

        // Assert — failure surfaces, and a retry is still possible because nothing was recorded.
        assertEquals(RegistrationStatus.Failed("ApiException statusCode=17"), status)
        assertNull(store.readRegistrationRecordOnce())
        coordinator.reconcile()
        assertEquals(2, registrar.registerCalls)
    }

    // ── docs/05 §3a "Turning Smart Detection off": the relaunch sweep ─────────────────

    @Test
    fun reconcileWhileOptedOut_endsWhatAnInterruptedOptOutLeftBehind() = runTest {
        // Arrange — the flag was written, then the process died before the opt-out ran.
        val optOuts = mutableListOf<Long>()
        val relaunched = DetectionRegistrationCoordinator(store, registrar, clock, optOut = { optOuts += it })
        store.setDesiredEnabled(false)

        // Act — what ParkingpinApplication.onCreate and the recovery receiver do.
        relaunched.reconcile()
        relaunched.reconcileAfterSystemReset()

        // Assert
        assertEquals(listOf(clock.epochMillis, clock.epochMillis), optOuts)
    }

    @Test
    fun optInRacingAnOptOut_endsNothing() = runTest {
        // Arrange — docs/05 §3a "Turning Smart Detection off": the opt-out ends the session
        // only while the user still wants detection off. The flag and the opt-out used to be
        // two steps with the lock released between them, so switching back on in that gap
        // ended the session of a user who had just opted back in.
        val optOuts = mutableListOf<Long>()
        val racing = DetectionRegistrationCoordinator(store, registrar, clock, optOut = { optOuts += it })
        racing.setDetectionEnabled(true)
        // The off call suspends inside its locked section, which is where the on call
        // arrives and queues for the lock.
        registrar.yieldOnUnregister = true

        // Act — off, and on again before the opt-out has run.
        val off = launch { racing.setDetectionEnabled(false) }
        val on = launch { racing.setDetectionEnabled(true) }
        off.join()
        on.join()

        // Assert
        assertTrue(store.readDesiredEnabledOnce())
        assertEquals(emptyList<Long>(), optOuts)
    }

    @Test
    fun reconcileWhileOptedIn_runsNoOptOut() = runTest {
        // Arrange
        val optOuts = mutableListOf<Long>()
        val relaunched = DetectionRegistrationCoordinator(store, registrar, clock, optOut = { optOuts += it })
        store.setDesiredEnabled(true)

        // Act
        relaunched.reconcile()

        // Assert
        assertEquals(emptyList<Long>(), optOuts)
    }

    @Test
    fun aFailingOptOut_doesNotFailTheSettingsToggle() = runTest {
        // Arrange — the runtime's DataStore write throws.
        val failing = DetectionRegistrationCoordinator(
            store,
            registrar,
            clock,
            optOut = { throw java.io.IOException("disk full") },
        )
        failing.setDetectionEnabled(true)

        // Act
        val status = failing.setDetectionEnabled(false)

        // Assert — the opt-out itself stands.
        assertEquals(RegistrationStatus.Disabled, status)
        assertEquals(false, store.readDesiredEnabledOnce())
    }
}

package kr.parkingpin.app.detection

import kr.parkingpin.app.data.DetectionStateStore
import kr.parkingpin.app.data.InMemoryPreferencesDataStore
import kr.parkingpin.app.domain.registration.TransitionRegistrationSpec
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
}

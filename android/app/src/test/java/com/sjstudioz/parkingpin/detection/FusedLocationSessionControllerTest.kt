package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.location.LocationFreshnessPolicy
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.location.LocationSessionMode
import com.sjstudioz.parkingpin.domain.location.LocationSessionPlanner
import com.sjstudioz.parkingpin.domain.location.LocationSessionProfiles
import com.sjstudioz.parkingpin.domain.location.LocationSessionStopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded session end to end, with the clock injected and no real sleeps
 * (docs/16_CODING_STANDARDS.md §8).
 *
 * The property under test throughout is that Play services is never left holding a
 * request the app has stopped caring about.
 */
class FusedLocationSessionControllerTest {

    private val startMillis = 1_700_000_000_000L

    private class Fixture {
        val clock = MutableTestClock()
        val registrar = FakeLocationSessionRegistrar()
        val store = DetectionStateStore(InMemoryPreferencesDataStore())
        val controller = FusedLocationSessionController(store, registrar, clock)
    }

    private fun fixture(): Fixture = Fixture().apply { clock.epochMillis = startMillis }

    private fun motion(kind: MotionEventKind, atMillis: Long) =
        MotionDomainEvent(kind = kind, atMillis = atMillis, receivedAtMillis = atMillis)

    private fun fix(
        atMillis: Long,
        latitude: Double = 37.5,
        longitude: Double = 127.0,
        accuracyM: Float = 10f,
        speedMps: Float? = null,
    ) = LocationSample(atMillis, latitude, longitude, accuracyM, speedMps)

    @Test
    fun `entering a vehicle opens a bounded confirmation window, not a high accuracy session`() = runTest {
        // Arrange
        val f = fixture()

        // Act
        val state = f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Assert
        assertEquals(LocationSessionMode.DRIVING_CANDIDATE, state.mode)
        assertTrue(f.registrar.isRegistered)
        assertEquals(1, f.registrar.requestedConfigs.size)
        assertTrue(f.registrar.requestedConfigs.single().durationMillis > 0)
    }

    @Test
    fun `walking with no vehicle session behind it never starts a request`() = runTest {
        // Arrange — someone on foot must not cost a single fix.
        val f = fixture()

        // Act
        val state = f.controller.onMotionEvent(motion(MotionEventKind.STARTED_WALKING, startMillis))

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertFalse(f.registrar.isRegistered)
        assertTrue(f.registrar.requestedConfigs.isEmpty())
    }

    @Test
    fun `exiting the vehicle captures the last points and then stops once the budget is spent`() = runTest {
        // Arrange — the underground-parking shape from docs/05 §13.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.clock.epochMillis = startMillis + 200_000L
        f.controller.onMotionEvent(motion(MotionEventKind.EXITED_VEHICLE, f.clock.epochMillis))
        assertEquals(LocationSessionMode.PARKING_TRANSITION, f.controller.reconcile().mode)

        val budget = requireNotNull(
            LocationSessionProfiles.configFor(LocationSessionMode.PARKING_TRANSITION, 60_000L)?.maxUpdates,
        )

        // Act — deliver exactly the budget.
        val state = f.controller.onLocationBatch(
            (1..budget).map { fix(atMillis = f.clock.epochMillis - it * 1_000L) },
        )

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertEquals(LocationSessionStopReason.UPDATE_BUDGET_SPENT, state.lastStopReason)
        assertFalse("Play services must not still hold a request", f.registrar.isRegistered)
    }

    @Test
    fun `a parking candidate releases the capture its transition kept`() = runTest {
        // Arrange — docs/05 §3a / §19: the capture runs while PARKING_TRANSITION decides.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.clock.epochMillis = startMillis + 200_000L
        f.controller.onMotionEvent(motion(MotionEventKind.EXITED_VEHICLE, f.clock.epochMillis))
        assertTrue(f.registrar.isRegistered)

        // Act — a location stop confirmed the parking.
        val state = f.controller.followEngine(
            wantedBefore = LocationSessionMode.PARKING_TRANSITION,
            wantedAfter = null,
        )

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertFalse("Play services must not still hold a request", f.registrar.isRegistered)
    }

    @Test
    fun `a capture the engine does not want is released with no change in its want`() = runTest {
        // Arrange — docs/05 §19 "after every event batch": a kerb capture whose owner went
        // away with no state change (a stop-only window that lapsed, an exit the engine then
        // refused) is released by the per-batch reconcile, not left to its own deadline.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.EXITED_VEHICLE, startMillis))
        assertTrue(f.registrar.isRegistered)

        // Act
        val state = f.controller.followEngine(wantedBefore = null, wantedAfter = null)

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertFalse("Play services must not still hold a request", f.registrar.isRegistered)
    }

    @Test
    fun `the diagnostics override is not the engine's to release`() = runTest {
        // Arrange — the P0 screen runs a capture with the engine IDLE on purpose.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Act
        val state = f.controller.followEngine(wantedBefore = null, wantedAfter = null)

        // Assert
        assertEquals(LocationSessionMode.DRIVING, state.mode)
        assertTrue(f.registrar.isRegistered)
    }

    @Test
    fun `a diagnostics capture the engine came to want becomes the engine's`() = runTest {
        // Arrange — a real drive began under the override. Once the engine wanted the capture
        // it is following that drive, and must be able to end it with the drive.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING_CANDIDATE)
        f.controller.followEngine(wantedBefore = null, wantedAfter = LocationSessionMode.DRIVING_CANDIDATE)

        // Act
        val state = f.controller.followEngine(wantedBefore = LocationSessionMode.DRIVING_CANDIDATE, wantedAfter = null)

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertFalse(f.registrar.isRegistered)
    }

    @Test
    fun `a drive the engine resumed with no motion event is captured`() = runTest {
        // Arrange — the fuel stop: the candidate released the capture, then the link came back.
        val f = fixture()

        // Act
        val state = f.controller.followEngine(wantedBefore = null, wantedAfter = LocationSessionMode.DRIVING)

        // Assert
        assertEquals(LocationSessionMode.DRIVING, state.mode)
        assertTrue(f.registrar.isRegistered)
        assertTrue("§7's guard needs evidence to measure into", state.evidence != null)
    }

    @Test
    fun `a session past its deadline is torn down on the next process start`() = runTest {
        // Arrange — the process-death case: the app died mid-drive and woke much later.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        assertTrue(f.registrar.isRegistered)

        // Act — past the DRIVING_CANDIDATE ceiling.
        f.clock.epochMillis = startMillis +
            LocationSessionProfiles.maxSessionMillis(LocationSessionMode.DRIVING_CANDIDATE) + 1
        val state = f.controller.reconcile()

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertEquals(LocationSessionStopReason.DEADLINE_REACHED, state.lastStopReason)
        assertFalse(f.registrar.isRegistered)
        assertEquals(1, f.registrar.removeCalls)
    }

    @Test
    fun `turning capture off removes the request and clears the record`() = runTest {
        // Arrange
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        assertTrue(f.registrar.isRegistered)

        // Act
        val state = f.controller.setDesiredMode(LocationSessionMode.IDLE)

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertNull(state.record)
        assertFalse(f.registrar.isRegistered)
        assertEquals(LocationSessionStopReason.DESIRED_IDLE, state.lastStopReason)
    }

    @Test
    fun `a parking saved by hand ends a running drive capture`() = runTest {
        // Arrange — docs/05 §11c: the user said where the car is, so the fixes a drive was
        // collecting toward that answer are no longer worth the battery.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        assertTrue(f.registrar.isRegistered)

        // Act
        val state = f.controller.stop()

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertNull("the drive's evidence goes with it", state.evidence)
        assertFalse(f.registrar.isRegistered)
    }

    @Test
    fun `stopping with no capture running touches nothing`() = runTest {
        // Arrange — the ordinary hand save: nothing was being captured.
        val f = fixture()

        // Act
        val state = f.controller.stop()

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertEquals(0, f.registrar.removeCalls)
    }

    @Test
    fun `a rejected request is not recorded as a live session`() = runTest {
        // Arrange — believing a registration exists when it does not is what leaks one.
        val f = fixture()
        f.registrar.requestFailure = "ApiException statusCode=17"

        // Act
        val state = f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Assert
        assertNull(state.record)
        assertEquals("ApiException statusCode=17", state.lastFailure)
        assertEquals(LocationSessionMode.IDLE, state.mode)
    }

    @Test
    fun `a failed renewal removes the request it just forgot about`() = runTest {
        // Arrange — the leak this closes: renewal fails, we drop the record, and the
        // previous Play services request is left with nobody who knows it exists.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        assertTrue(f.registrar.isRegistered)
        val removalsBefore = f.registrar.removeCalls

        // Act — wind the clock into the renewal window, then fail the renewal.
        f.clock.epochMillis = startMillis +
            requireNotNull(LocationSessionProfiles.configFor(LocationSessionMode.DRIVING, 60 * 60_000L))
                .durationMillis - LocationSessionPlanner.RENEW_LEAD_MILLIS + 1
        f.registrar.requestFailure = "ApiException statusCode=17"
        val state = f.controller.reconcile()

        // Assert
        assertNull(state.record)
        assertEquals("ApiException statusCode=17", state.lastFailure)
        assertEquals(removalsBefore + 1, f.registrar.removeCalls)
        assertFalse("Play services must not be left holding a forgotten request", f.registrar.isRegistered)
    }

    @Test
    fun `a capture counts as running only while its registration is live`() = runTest {
        // Arrange — docs/05 §3a / §19: a stop-only window lives exactly as long as its
        // capture, so "is one running" has to be the registration's truth, not the mode's.
        val f = fixture()
        val beforeAny = f.controller.isCaptureRunning()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        val whileLive = f.controller.isCaptureRunning()

        // Act — Play services expires an unrenewed request on its own.
        val record = requireNotNull(f.store.readLocationSessionStateOnce().record)
        f.clock.epochMillis = record.registrationExpiresAtMillis
        val afterExpiry = f.controller.isCaptureRunning()

        // Assert
        assertFalse(beforeAny)
        assertTrue(whileLive)
        assertFalse("an expired request delivers nothing", afterExpiry)
    }

    @Test
    fun `a capture without location permission is not running`() = runTest {
        // Arrange — revoking the permission kills the process and the request with it.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Act
        f.registrar.foregroundGranted = false

        // Assert
        assertFalse(f.controller.isCaptureRunning())
    }

    @Test
    fun `a capture without background location is not running`() = runTest {
        // Arrange — a downgrade to "only while using": Play services stops delivering to the
        // PendingIntent once the app is backgrounded, and the process dies with the service.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Act
        f.registrar.backgroundGranted = false

        // Assert
        assertFalse(f.controller.isCaptureRunning())
    }

    @Test
    fun `a system reset cleans a record that had already expired instead of marking it dropped`() = runTest {
        // Arrange — the drive's capture outlived its own hard deadline on disk (the process
        // never ran again to tear it down), then the phone rebooted.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.clock.epochMillis = startMillis +
            LocationSessionProfiles.maxSessionMillis(LocationSessionMode.DRIVING_CANDIDATE) + 1
        val requestsBefore = f.registrar.requestedConfigs.size

        // Act
        val state = f.controller.reconcileAfterSystemReset()
        val followed = f.controller.followEngine(LocationSessionMode.DRIVING_CANDIDATE, LocationSessionMode.DRIVING_CANDIDATE)

        // Assert — ended by its deadline, so the engine's follow does not reopen it.
        assertNull(state.record)
        assertEquals(LocationSessionStopReason.DEADLINE_REACHED, state.lastStopReason)
        assertEquals(LocationSessionMode.IDLE, followed.mode)
        assertEquals(requestsBefore, f.registrar.requestedConfigs.size)
        assertFalse(f.registrar.isRegistered)
    }

    @Test
    fun `a system reset forgets the registration the system dropped`() = runTest {
        // Arrange — a reboot or an app update drops the Play services request while the
        // DataStore record survives; trusted, it would call a dead capture live.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        val removalsBefore = f.registrar.removeCalls

        // Act
        val state = f.controller.reconcileAfterSystemReset()

        // Assert
        assertNull(state.record)
        assertEquals(LocationSessionStopReason.SYSTEM_RESET, state.lastStopReason)
        assertEquals("removed anyway, so nothing is left orphaned", removalsBefore + 1, f.registrar.removeCalls)
        assertFalse(f.controller.isCaptureRunning())
    }

    @Test
    fun `a system reset with no capture recorded changes nothing`() = runTest {
        // Arrange
        val f = fixture()

        // Act
        val state = f.controller.reconcileAfterSystemReset()

        // Assert
        assertNull(state.record)
        assertNull(state.lastStopReason)
        assertEquals(0, f.registrar.removeCalls)
    }

    @Test
    fun `losing location permission mid-session stops it`() = runTest {
        // Arrange — revocation in Settings while backgrounded.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Act
        f.registrar.foregroundGranted = false
        val state = f.controller.reconcile()

        // Assert
        assertEquals(LocationSessionStopReason.PERMISSION_LOST, state.lastStopReason)
        assertFalse(f.registrar.isRegistered)
    }

    @Test
    fun `a regranted permission reopens nothing for the session that lost the capture`() = runTest {
        // Arrange — docs/05 §11 "A lost capture stays lost for its session".
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.registrar.foregroundGranted = false
        f.controller.reconcile()
        f.registrar.foregroundGranted = true
        val requestsBefore = f.registrar.requestedConfigs.size

        // Act
        val state = f.controller.onMotionEvent(
            motion(MotionEventKind.EXITED_VEHICLE, startMillis + 60_000),
            engineWantsCapture = true,
            continuesEngineSession = true,
        )

        // Assert
        assertEquals(LocationSessionMode.IDLE, state.mode)
        assertEquals(LocationSessionStopReason.PERMISSION_LOST, state.lastStopReason)
        assertEquals(requestsBefore, f.registrar.requestedConfigs.size)
    }

    @Test
    fun `a regranted permission opens a capture for a new session`() = runTest {
        // Arrange
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.registrar.foregroundGranted = false
        f.controller.reconcile()
        f.registrar.foregroundGranted = true

        // Act — the engine's session ended; this vehicle_enter opens another.
        val state = f.controller.onMotionEvent(
            motion(MotionEventKind.ENTERED_VEHICLE, startMillis + 600_000),
            engineWantsCapture = true,
            continuesEngineSession = false,
        )

        // Assert
        assertEquals(LocationSessionMode.DRIVING_CANDIDATE, state.mode)
        assertNull(state.lastStopReason)
        assertTrue(f.registrar.isRegistered)
    }

    @Test
    fun `a capture its own deadline ended still reopens for the same session`() = runTest {
        // Arrange — only a lost permission sticks to the session; a deadline is a leak guard
        // for one request, and the kerb capture after it is the engine's to ask for.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))
        f.clock.epochMillis = startMillis + LocationSessionProfiles.maxSessionMillis(LocationSessionMode.DRIVING_CANDIDATE)
        f.controller.reconcile()
        assertFalse(f.registrar.isRegistered)

        // Act
        val state = f.controller.onMotionEvent(
            motion(MotionEventKind.EXITED_VEHICLE, f.clock.epochMillis),
            engineWantsCapture = true,
            continuesEngineSession = true,
        )

        // Assert
        assertEquals(LocationSessionMode.PARKING_TRANSITION, state.mode)
    }

    @Test
    fun `a cached fix replayed on the first delivery never becomes the reliable location`() = runTest {
        // Arrange — the measured iOS defect, reproduced on the Android path: the first
        // delivery of a fresh request carries the provider's cached last-known fix.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Act — 4h31m old, and perfectly accurate.
        val state = f.controller.onLocationBatch(
            listOf(fix(atMillis = startMillis - 16_260_000L, accuracyM = 8f)),
        )

        // Assert
        assertEquals(1, state.counters.cachedFixDropCount)
        assertEquals(16_260_000L, state.counters.lastCachedFixAgeMillis)
        assertEquals(0, state.counters.admittedCount)
        assertNull(f.store.readCheckpointOnce()?.lastReliableLocation)
    }

    @Test
    fun `a fresh fix becomes the reliable location and is never logged as a coordinate`() = runTest {
        // Arrange
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Act
        val state = f.controller.onLocationBatch(listOf(fix(atMillis = startMillis - 2_000L, accuracyM = 9f)))

        // Assert
        assertEquals(1, state.counters.admittedCount)
        val reliable = requireNotNull(f.store.readCheckpointOnce()?.lastReliableLocation)
        assertEquals(37.5, reliable.latitude, 0.000_001)
        assertEquals(startMillis - 2_000L, reliable.capturedAtMillis)
        // The redacted toString is the last line of defence against an accidental log.
        assertFalse(reliable.toString().contains("37.5"))
        assertTrue(reliable.toString().contains("redacted"))
    }

    @Test
    fun `driving is confirmed only once movement backs the vehicle evidence up`() = runTest {
        // Arrange — docs/05 §7. One transition never confirms, and since the 2026-09-18
        // unification neither does one moving fix: the clause counts samples.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Act — two minutes later, two fixes at traffic speed 222m apart.
        f.clock.epochMillis = startMillis + 130_000L
        val afterOne = f.controller.onLocationBatch(
            listOf(fix(atMillis = f.clock.epochMillis - 2_000L, speedMps = 16f)),
        )
        f.clock.epochMillis = startMillis + 140_000L
        val state = f.controller.onLocationBatch(
            listOf(fix(atMillis = f.clock.epochMillis - 1_000L, latitude = 37.502, speedMps = 16f)),
        )

        // Assert
        assertFalse(afterOne.drivingConfirmed)
        assertTrue(state.drivingConfirmed)
        assertEquals(LocationSessionMode.DRIVING, state.mode)
        assertTrue(state.drivingReasonCodes.contains("vehicle_duration_met"))
        assertEquals(2, state.evidence?.movement?.movingSampleCount)
        assertEquals(2, state.evidence?.movement?.speedAvailableCount)
    }

    @Test
    fun `a coarse fix that the reliability bar rejects still counts as movement evidence`() = runTest {
        // Arrange — the structural defect the unification removed. §6's 35m bar picks a
        // parking spot worth remembering; underground it rejects every fix there is, and
        // gating the movement clause on it made confirmation impossible down there.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Act — three 300m-accurate fixes with no speed, 40s and ~2km apart each. Not one
        // of them clears the 35m reliability bar.
        var state = f.controller.onLocationBatch(emptyList())
        listOf(37.500 to 130_000L, 37.518 to 170_000L, 37.536 to 210_000L).forEach { (latitude, elapsed) ->
            f.clock.epochMillis = startMillis + elapsed
            state = f.controller.onLocationBatch(
                listOf(fix(atMillis = f.clock.epochMillis - 1_000L, latitude = latitude, accuracyM = 300f)),
            )
        }

        // Assert — nothing was admitted as reliable, and the drive is confirmed anyway.
        assertEquals(0, state.counters.admittedCount)
        assertEquals(3, state.counters.poorAccuracyDropCount)
        assertEquals(3, state.evidence?.movement?.speedMissingCount)
        assertEquals(2, state.evidence?.movement?.derivedMovingSampleCount)
        assertTrue(state.drivingConfirmed)
    }

    @Test
    fun `a stationary phone in a vehicle is never promoted to a driving session`() = runTest {
        // Arrange — the false positive the movement clause exists to stop.
        val f = fixture()
        f.controller.onMotionEvent(motion(MotionEventKind.ENTERED_VEHICLE, startMillis))

        // Act — plenty of time, plenty of fixes, no movement.
        f.clock.epochMillis = startMillis + 200_000L
        repeat(4) { index ->
            f.clock.epochMillis += 10_000L
            f.controller.onLocationBatch(listOf(fix(atMillis = f.clock.epochMillis - 1_000L + index)))
        }
        val state = f.controller.reconcile()

        // Assert
        assertFalse(state.drivingConfirmed)
        assertEquals(LocationSessionMode.DRIVING_CANDIDATE, state.mode)
    }

    @Test
    fun `travel distance accumulates within a session and starts from zero in the next one`() = runTest {
        // Arrange
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        f.controller.onLocationBatch(listOf(fix(atMillis = startMillis - 1_000L, latitude = 37.500)))
        f.clock.epochMillis = startMillis + 20_000L
        f.controller.onLocationBatch(
            listOf(fix(atMillis = f.clock.epochMillis - 1_000L, latitude = 37.510)),
        )
        val driven = requireNotNull(f.store.readCheckpointOnce()).travelDistanceEstimateMeters

        // Act — end the session and start a fresh one.
        f.controller.setDesiredMode(LocationSessionMode.IDLE)
        f.clock.epochMillis += 60_000L
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        f.controller.onLocationBatch(
            listOf(fix(atMillis = f.clock.epochMillis - 1_000L, latitude = 37.600)),
        )

        // Assert — ~1.1km across the first session, and the second does not inherit it.
        assertEquals(1_112.0, driven, 20.0)
        assertEquals(0.0, requireNotNull(f.store.readCheckpointOnce()).travelDistanceEstimateMeters, 0.001)
    }

    @Test
    fun `a batched delivery keeps its interior instead of losing it to the freshness bar`() = runTest {
        // Arrange — the shape the Galaxy S21+ actually delivered: a batch whose oldest fix
        // was already ~53s old on arrival. Judged against wall clock the whole interior is
        // stale; judged against the moment the batch describes, none of it is.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        f.clock.epochMillis = startMillis + 60_000L
        val newest = f.clock.epochMillis - 45_000L

        // Act — a batch spanning the capped window, delivered 45s late.
        val state = f.controller.onLocationBatch(
            (0..1).map { index ->
                fix(
                    atMillis = newest - (1 - index) * LocationFreshnessPolicy.SESSION_FRESHNESS_MILLIS,
                    latitude = 37.5 + index * 0.001,
                )
            },
        )

        // Assert
        assertEquals(2, state.counters.admittedCount)
        assertEquals(0, state.counters.staleDropCount)
    }

    @Test
    fun `the DRIVING batch window never outruns the freshness bar it is judged by`() {
        // Arrange — the invariant the S21+ run exposed: a batch must not arrive holding
        // fixes that the freshness rule will then throw away.
        val config = requireNotNull(
            LocationSessionProfiles.configFor(LocationSessionMode.DRIVING, 60 * 60_000L),
        )

        // Act & Assert
        assertTrue(config.maxUpdateDelayMillis <= LocationFreshnessPolicy.SESSION_FRESHNESS_MILLIS)
    }

    @Test
    fun `an entire batch of cached fixes is still rejected, however self-consistent it is`() = runTest {
        // Arrange — the batch reference must not let a replayed batch vouch for itself.
        // The cached-fix guard stays anchored on wall clock for exactly this case.
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Act — four hours old, 15s apart, internally perfectly fresh.
        val state = f.controller.onLocationBatch(
            (0..3).map { index -> fix(atMillis = startMillis - 16_260_000L + index * 15_000L) },
        )

        // Assert
        assertEquals(4, state.counters.cachedFixDropCount)
        assertEquals(0, state.counters.admittedCount)
        assertNull(f.store.readCheckpointOnce()?.lastReliableLocation)
    }

    @Test
    fun `counters reset with a new session so one trip's numbers are readable`() = runTest {
        // Arrange
        val f = fixture()
        f.controller.setDesiredMode(LocationSessionMode.DRIVING)
        f.controller.onLocationBatch(listOf(fix(atMillis = startMillis - 16_260_000L)))
        assertEquals(1, f.store.readLocationSessionStateOnce().counters.cachedFixDropCount)

        // Act
        f.controller.setDesiredMode(LocationSessionMode.IDLE)
        val state = f.controller.setDesiredMode(LocationSessionMode.DRIVING)

        // Assert
        assertEquals(0, state.counters.cachedFixDropCount)
        assertEquals(2, state.counters.sessionsStarted)
        assertEquals(1, state.counters.sessionsStopped)
    }
}

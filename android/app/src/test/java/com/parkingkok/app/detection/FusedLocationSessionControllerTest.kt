package com.parkingkok.app.detection

import com.parkingkok.app.data.DetectionStateStore
import com.parkingkok.app.data.InMemoryPreferencesDataStore
import com.parkingkok.app.domain.detection.MotionDomainEvent
import com.parkingkok.app.domain.detection.MotionEventKind
import com.parkingkok.app.domain.location.LocationFreshnessPolicy
import com.parkingkok.app.domain.location.LocationSample
import com.parkingkok.app.domain.location.LocationSessionMode
import com.parkingkok.app.domain.location.LocationSessionPlanner
import com.parkingkok.app.domain.location.LocationSessionProfiles
import com.parkingkok.app.domain.location.LocationSessionStopReason
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

package com.parkingkok.app.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded-session lifecycle: every path that must end in a stop, does.
 *
 * A leaked session is the failure the P0 battery gate rejects outright
 * (docs/05_PARKING_DETECTION_ENGINE.md §19, docs/00_CORE_RULES.md Background), so each of
 * the three guard layers is tested on its own rather than trusted together.
 */
class LocationSessionPlannerTest {

    private val now = 1_700_000_000_000L

    private fun record(
        mode: LocationSessionMode = LocationSessionMode.DRIVING,
        startedAgoMillis: Long = 0L,
        hardDeadlineInMillis: Long = 60 * 60_000L,
        registrationExpiresInMillis: Long = 15 * 60_000L,
        deliveredUpdateCount: Int = 0,
        profileVersion: Int = LocationSessionProfiles.VERSION,
    ) = LocationSessionRecord(
        mode = mode,
        profileVersion = profileVersion,
        startedAtMillis = now - startedAgoMillis,
        hardDeadlineAtMillis = now + hardDeadlineInMillis,
        registrationExpiresAtMillis = now + registrationExpiresInMillis,
        deliveredUpdateCount = deliveredUpdateCount,
    )

    private fun plan(
        current: LocationSessionRecord?,
        desiredMode: LocationSessionMode,
        permissionGranted: Boolean = true,
        nowMillis: Long = now,
    ) = LocationSessionPlanner.plan(current, desiredMode, permissionGranted, nowMillis)

    @Test
    fun `IDLE registers nothing at all`() {
        // Arrange & Act — docs/04 §2: IDLE holds no continuous request.
        val action = plan(current = null, desiredMode = LocationSessionMode.IDLE)

        // Assert
        assertEquals(LocationSessionAction.None, action)
    }

    @Test
    fun `asking for IDLE while a session is registered stops it`() {
        // Arrange
        val running = record()

        // Act
        val action = plan(running, LocationSessionMode.IDLE)

        // Assert
        assertEquals(LocationSessionStopReason.DESIRED_IDLE, (action as LocationSessionAction.Stop).reason)
    }

    @Test
    fun `a started session carries an absolute deadline, so it cannot run forever`() {
        // Arrange & Act
        val action = plan(null, LocationSessionMode.DRIVING) as LocationSessionAction.Start

        // Assert
        assertEquals(
            now + LocationSessionProfiles.maxSessionMillis(LocationSessionMode.DRIVING),
            action.record.hardDeadlineAtMillis,
        )
        // Layer 1: Play services expires the request by itself even if this process never
        // runs again, so the registration is bounded independently of the deadline.
        assertTrue(action.config.durationMillis > 0)
        assertTrue(action.config.durationMillis <= LocationSessionProfiles.maxSessionMillis(LocationSessionMode.DRIVING))
    }

    @Test
    fun `reaching the deadline stops the session even while the mode is still wanted`() {
        // Arrange — layer 2, the guard a process that woke late must honour.
        val expired = record(hardDeadlineInMillis = -1L)

        // Act
        val action = plan(expired, LocationSessionMode.DRIVING)

        // Assert
        assertEquals(LocationSessionStopReason.DEADLINE_REACHED, (action as LocationSessionAction.Stop).reason)
    }

    @Test
    fun `renewal re-arms the request without ever extending the deadline`() {
        // Arrange — this is what keeps renewals from walking a session forward forever.
        val nearlyExpired = record(registrationExpiresInMillis = LocationSessionPlanner.RENEW_LEAD_MILLIS - 1)

        // Act
        val action = plan(nearlyExpired, LocationSessionMode.DRIVING) as LocationSessionAction.Renew

        // Assert
        assertEquals(nearlyExpired.hardDeadlineAtMillis, action.record.hardDeadlineAtMillis)
        assertTrue(action.record.registrationExpiresAtMillis > nearlyExpired.registrationExpiresAtMillis)
    }

    @Test
    fun `a renewal never outlives the deadline it is renewing under`() {
        // Arrange — 30s of session left, so the re-armed request must not ask for more.
        val almostDone = record(hardDeadlineInMillis = 30_000L, registrationExpiresInMillis = 0L)

        // Act
        val action = plan(almostDone, LocationSessionMode.DRIVING) as LocationSessionAction.Renew

        // Assert
        assertEquals(30_000L, action.config.durationMillis)
        assertTrue(action.record.registrationExpiresAtMillis <= almostDone.hardDeadlineAtMillis)
    }

    @Test
    fun `a healthy session in the right mode issues no call at all`() {
        // Arrange — re-registering on every delivery would cost battery for nothing.
        val healthy = record(registrationExpiresInMillis = LocationSessionPlanner.RENEW_LEAD_MILLIS + 1)

        // Act & Assert
        assertEquals(LocationSessionAction.None, plan(healthy, LocationSessionMode.DRIVING))
    }

    @Test
    fun `PARKING_CANDIDATE stops once its update budget is spent`() {
        // Arrange — docs/04 §2: capture the last reliable points, then stop.
        val budget = requireNotNull(
            LocationSessionProfiles.configFor(LocationSessionMode.PARKING_CANDIDATE, 60_000L)?.maxUpdates,
        )
        val spent = record(
            mode = LocationSessionMode.PARKING_CANDIDATE,
            hardDeadlineInMillis = 60_000L,
            deliveredUpdateCount = budget,
        )

        // Act
        val action = plan(spent, LocationSessionMode.PARKING_CANDIDATE)

        // Assert
        assertEquals(LocationSessionStopReason.UPDATE_BUDGET_SPENT, (action as LocationSessionAction.Stop).reason)
    }

    @Test
    fun `losing the permission mid-session tears the registration down`() {
        // Arrange — revocation in Settings while backgrounded is routine.
        val running = record()

        // Act
        val action = plan(running, LocationSessionMode.DRIVING, permissionGranted = false)

        // Assert
        assertEquals(LocationSessionStopReason.PERMISSION_LOST, (action as LocationSessionAction.Stop).reason)
    }

    @Test
    fun `a mode change replaces the request rather than leaving the old one running`() {
        // Arrange
        val candidate = record(mode = LocationSessionMode.DRIVING_CANDIDATE)

        // Act
        val action = plan(candidate, LocationSessionMode.DRIVING) as LocationSessionAction.Start

        // Assert
        assertEquals(LocationSessionMode.DRIVING, action.record.mode)
        assertEquals(LocationAccuracyTier.HIGH, action.config.tier)
    }

    @Test
    fun `a session registered by an older profile version is replaced`() {
        // Arrange — the registered request no longer matches what this build asks for.
        val stale = record(profileVersion = LocationSessionProfiles.VERSION - 1)

        // Act & Assert
        assertTrue(plan(stale, LocationSessionMode.DRIVING) is LocationSessionAction.Start)
    }

    @Test
    fun `IDLE has no request shape, and every other mode does`() {
        // Arrange & Act & Assert — the structural half of "IDLE costs nothing".
        assertEquals(null, LocationSessionProfiles.configFor(LocationSessionMode.IDLE, 60_000L))
        for (mode in LocationSessionMode.entries - LocationSessionMode.IDLE) {
            val config = requireNotNull(LocationSessionProfiles.configFor(mode, 60 * 60_000L)) { "$mode" }
            assertTrue("$mode duration must be bounded", config.durationMillis > 0)
            assertTrue("$mode must be bounded", LocationSessionProfiles.maxSessionMillis(mode) > 0)
        }
    }
}

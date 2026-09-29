package com.sjstudioz.parkingpin.domain.location

import com.sjstudioz.parkingpin.domain.detection.DetectionEngineState
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.ParkingDetectionEngine
import com.sjstudioz.parkingpin.domain.detection.StopOnlyResumeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * docs/05 §3a / §19 (2026-09-27): the bounded capture runs while a drive is open **and**
 * while `PARKING_TRANSITION` decides, and is released when the transition leaves by
 * `CANDIDATE_PENDING` or `IDLE`. Motion events cannot see those edges — a `movementIdle`
 * transition, a location stop, a window lapse and a car-link row produce no transition — so
 * the capture also follows the engine's state. These are that rule's cases.
 */
class LocationCaptureModePolicyTest {

    private val at = 1_700_000_000_000L

    @Test
    fun `the engine wants a capture exactly while a drive or a transition is open`() {
        // Arrange
        val parkedWithDeparture = DetectionEngineState.startingIn(DetectionState.DRIVING, at)
            .copy(state = DetectionState.PARKED)

        // Act & Assert
        assertEquals(LocationSessionMode.DRIVING_CANDIDATE, wanted(DetectionState.DRIVING_CANDIDATE))
        assertEquals(LocationSessionMode.DRIVING, wanted(DetectionState.DRIVING))
        assertEquals(LocationSessionMode.PARKING_TRANSITION, wanted(DetectionState.PARKING_TRANSITION))
        assertEquals(LocationSessionMode.DRIVING_CANDIDATE, wanted(DetectionState.DEPARTURE_CANDIDATE))
        assertEquals(
            "§11: PARKED measures a departure's bars once a vehicle_enter opened its evidence",
            LocationSessionMode.DRIVING_CANDIDATE,
            LocationCaptureModePolicy.modeWantedBy(parkedWithDeparture),
        )
        assertNull(wanted(DetectionState.PARKED))
        assertNull(wanted(DetectionState.IDLE))
        assertNull(wanted(DetectionState.CANDIDATE_PENDING))
    }

    @Test
    fun `a stop-only candidate keeps the transition's capture while its resume window is open`() {
        // Arrange — docs/05 §3a "A stop-only candidate can still be a long light" rule 1: the
        // resume rows are location rows, so releasing the capture at the candidate would make
        // them unreachable on a device while every fixture still passed.
        val stopOnly = DetectionEngineState(
            state = DetectionState.CANDIDATE_PENDING,
            stopOnlyResumeWindow = StopOnlyResumeWindow(
                driveEndedAtMillis = at,
                deadlineMillis = at + ParkingDetectionEngine.TRANSITION_WINDOW_MILLIS,
            ),
        )

        // Act
        val mode = LocationCaptureModePolicy.modeWantedBy(stopOnly)

        // Assert — the transition's want, unchanged, so PT -> CANDIDATE_PENDING is no edge.
        assertEquals(LocationSessionMode.PARKING_TRANSITION, mode)
    }

    @Test
    fun `an exit the engine does not want captured opens no kerb capture`() {
        // Arrange — an IN_VEHICLE EXIT after a bus ride the engine already dropped, or after a
        // link disconnect already produced the candidate: nothing would consume the fixes of a
        // 300 s high-accuracy kerb capture, and no engine edge would release it.
        // Act
        val mode = LocationCaptureModePolicy.modeFor(
            MotionEventKind.EXITED_VEHICLE,
            LocationSessionMode.IDLE,
            engineWantsCapture = false,
        )

        // Assert
        assertEquals(LocationSessionMode.IDLE, mode)
    }

    @Test
    fun `an exit from a drive the engine is following narrows to the kerb`() {
        // Act
        val fromDriving = LocationCaptureModePolicy.modeFor(
            MotionEventKind.EXITED_VEHICLE,
            LocationSessionMode.DRIVING,
            engineWantsCapture = true,
        )
        // A drive whose capture its own deadline already ended still gets the kerb fixes.
        val afterDeadline = LocationCaptureModePolicy.modeFor(
            MotionEventKind.EXITED_VEHICLE,
            LocationSessionMode.IDLE,
            engineWantsCapture = true,
        )

        // Assert
        assertEquals(LocationSessionMode.PARKING_TRANSITION, fromDriving)
        assertEquals(LocationSessionMode.PARKING_TRANSITION, afterDeadline)
    }

    @Test
    fun `no motion edge reopens a capture the engine's session lost`() {
        // docs/05 §11 "A lost capture stays lost for its session": the permission came back,
        // but the session that lost the capture is still the engine's.
        // Act
        val modes = MotionEventKind.entries.associateWith {
            LocationCaptureModePolicy.modeFor(
                it,
                LocationSessionMode.IDLE,
                engineWantsCapture = true,
                sessionLostCapture = true,
            )
        }

        // Assert
        assertEquals(MotionEventKind.entries.associateWith { LocationSessionMode.IDLE }, modes)
    }

    @Test
    fun `a session that lost its capture leaves a running capture alone`() {
        // A capture that is running belongs to no lost session (the diagnostics override);
        // the loss rule only refuses to open one.
        // Act
        val mode = LocationCaptureModePolicy.modeFor(
            MotionEventKind.EXITED_VEHICLE,
            LocationSessionMode.DRIVING,
            engineWantsCapture = true,
            sessionLostCapture = true,
        )

        // Assert
        assertEquals(LocationSessionMode.DRIVING, mode)
    }

    @Test
    fun `an exit the engine does not want leaves a capture it did not open alone`() {
        // The P0 diagnostics override runs with the engine IDLE; an exit neither narrows nor
        // ends it — the engine's follow is what releases captures it asked for.
        val mode = LocationCaptureModePolicy.modeFor(
            MotionEventKind.EXITED_VEHICLE,
            LocationSessionMode.DRIVING,
            engineWantsCapture = false,
        )

        assertEquals(LocationSessionMode.DRIVING, mode)
    }

    @Test
    fun `leaving the transition for a candidate releases the capture`() {
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = LocationSessionMode.PARKING_TRANSITION,
            wantedAfter = null,
            current = LocationSessionMode.PARKING_TRANSITION,
            ownedByDiagnostics = false,
        )

        assertEquals(LocationSessionMode.IDLE, mode)
    }

    @Test
    fun `a movementIdle candidate releases the driving capture it never switched away from`() {
        // The stop produced no vehicle_exit, so the motion policy left DRIVING running — for
        // up to two hours after the parking, without this.
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = LocationSessionMode.DRIVING,
            wantedAfter = null,
            current = LocationSessionMode.DRIVING,
            ownedByDiagnostics = false,
        )

        assertEquals(LocationSessionMode.IDLE, mode)
    }

    @Test
    fun `a movementIdle transition keeps the driving capture it already has`() {
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = LocationSessionMode.DRIVING,
            wantedAfter = LocationSessionMode.PARKING_TRANSITION,
            current = LocationSessionMode.DRIVING,
            ownedByDiagnostics = false,
        )

        assertEquals(LocationSessionMode.DRIVING, mode)
    }

    @Test
    fun `a transition that resumes driving widens the kerb capture back to driving`() {
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = LocationSessionMode.PARKING_TRANSITION,
            wantedAfter = LocationSessionMode.DRIVING,
            current = LocationSessionMode.PARKING_TRANSITION,
            ownedByDiagnostics = false,
        )

        assertEquals(LocationSessionMode.DRIVING, mode)
    }

    @Test
    fun `a drive the engine opened with no motion event gets a capture`() {
        // The fuel stop (§3a car-link row 3): CANDIDATE_PENDING -> DRIVING on a reconnect,
        // with the capture released at the candidate and no motion event to reopen it.
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = null,
            wantedAfter = LocationSessionMode.DRIVING,
            current = LocationSessionMode.IDLE,
            ownedByDiagnostics = false,
        )

        assertEquals(LocationSessionMode.DRIVING, mode)
    }

    @Test
    fun `a capture its own deadline ended is not reopened while the drive goes on`() {
        // The hard deadline is one of the three leak guards; the engine's 2-hour ceiling and
        // windows end the drive itself.
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = LocationSessionMode.DRIVING,
            wantedAfter = LocationSessionMode.DRIVING,
            current = LocationSessionMode.IDLE,
            ownedByDiagnostics = false,
        )

        assertEquals(LocationSessionMode.IDLE, mode)
    }

    @Test
    fun `a capture a reboot dropped is reopened while the engine still wants one`() {
        // Arrange — docs/05 §14 (FG11): a reboot or an app update dropped the request, not its
        // own deadline, so a restored DEPARTURE_CANDIDATE gets its bounded capture back, as
        // iOS reopens it on restore.
        // Act
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = LocationSessionMode.DRIVING_CANDIDATE,
            wantedAfter = LocationSessionMode.DRIVING_CANDIDATE,
            current = LocationSessionMode.IDLE,
            ownedByDiagnostics = false,
            droppedBySystem = true,
        )

        // Assert
        assertEquals(LocationSessionMode.DRIVING_CANDIDATE, mode)
    }

    @Test
    fun `a capture a reboot dropped is not reopened for an engine that wants none`() {
        // Act
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = LocationSessionMode.DRIVING_CANDIDATE,
            wantedAfter = null,
            current = LocationSessionMode.IDLE,
            ownedByDiagnostics = false,
            droppedBySystem = true,
        )

        // Assert
        assertEquals(LocationSessionMode.IDLE, mode)
    }

    @Test
    fun `an engine with nothing open leaves the diagnostics override alone`() {
        // Arrange — the P0 diagnostics override starts a capture with the engine IDLE; it is
        // the one capture the engine never owned.
        // Act
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = null,
            wantedAfter = null,
            current = LocationSessionMode.DRIVING,
            ownedByDiagnostics = true,
        )

        // Assert
        assertEquals(LocationSessionMode.DRIVING, mode)
    }

    @Test
    fun `a capture the engine does not want is released even when the want did not change`() {
        // Arrange — docs/05 §19 "after every event batch": a kerb capture a motion edge
        // opened for an exit the engine then refused, or one whose stop-only window closed at
        // a deadline no tick reached, has no want edge to release it.
        // Act
        val mode = LocationCaptureModePolicy.modeFollowingEngine(
            wantedBefore = null,
            wantedAfter = null,
            current = LocationSessionMode.PARKING_TRANSITION,
            ownedByDiagnostics = false,
        )

        // Assert
        assertEquals(LocationSessionMode.IDLE, mode)
    }

    private fun wanted(state: DetectionState): LocationSessionMode? =
        LocationCaptureModePolicy.modeWantedBy(DetectionEngineState(state = state))
}

package com.sjstudioz.parkingpin.domain.location

import com.sjstudioz.parkingpin.domain.detection.DetectionEngineState
import com.sjstudioz.parkingpin.domain.detection.DetectionState
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind

/**
 * Picks the capture mode — the conceptual mode ladder in docs/04_ANDROID_IMPLEMENTATION.md
 * §2 — from two sources, each for the edges only it can see.
 *
 * **Motion events** ([modeFor]) shape the request as a drive unfolds: a vehicle entry opens a
 * cheap confirmation window, an exit narrows it to the kerb.
 *
 * **The detection engine's state** ([modeFollowingEngine]) decides the edges no motion event
 * marks. docs/05 §3a / §19 (2026-09-27): the capture runs while a drive is open *and* while
 * `PARKING_TRANSITION` decides — two of its three exits are location rows — and is released
 * when the transition leaves by `CANDIDATE_PENDING` or `IDLE` — except that a stop-only
 * candidate keeps it until the transition's own deadline (§3a "A stop-only candidate can still
 * be a long light"), which bounds the capture per stop at `transitionWindow` either way. A `movementIdle` stop, a
 * location stop, a window lapse and every car-link row move the engine with no transition
 * delivered, so before this the capture sat in `DRIVING` for up to two hours after a
 * detected parking, and a drive resumed by a car link had none at all.
 *
 * Still one owner of the registration: both answers go through
 * [com.sjstudioz.parkingpin.detection.FusedLocationSessionController], under its lock.
 */
object LocationCaptureModePolicy {

    /**
     * The capture a motion event asks for.
     *
     * [engineWantsCapture] is whether the detection engine, once it has taken this event and
     * settled its windows against the event's timestamp, wants any capture at all
     * ([modeWantedBy] non-null). It gates the one edge that *opens* a high-accuracy request on
     * its own: the kerb capture after `EXITED_VEHICLE`. The settled answer and not the stored
     * one, because a lapsed `DRIVING_CANDIDATE` or a stop-only window this exit closes still
     * reads as wanting one on disk (docs/05 §19).
     */
    fun modeFor(
        event: MotionEventKind,
        current: LocationSessionMode,
        engineWantsCapture: Boolean,
    ): LocationSessionMode = when (event) {
        // Vehicle evidence opens a cheap confirmation window; §7's guard promotes it.
        MotionEventKind.ENTERED_VEHICLE -> when (current) {
            LocationSessionMode.DRIVING -> LocationSessionMode.DRIVING
            else -> LocationSessionMode.DRIVING_CANDIDATE
        }

        // Vehicle ended. Capture the last reliable points, then stop (§2 PARKING_TRANSITION) —
        // but only for a drive the engine is following. The kerb profile is 300 s of HIGH
        // accuracy every 5 s; an exit the engine has no use for (IDLE after a bus ride the
        // Transition API called IN_VEHICLE, CANDIDATE_PENDING after a link disconnect already
        // decided, PARKED) would open one that nothing consumes — the engine drops every fix
        // outside a session — and that no engine edge releases, because the engine's want
        // never changed. Anything already running is left as it is: the per-batch follow
        // releases what the engine no longer wants, and the diagnostics override is not the
        // engine's.
        MotionEventKind.EXITED_VEHICLE ->
            if (engineWantsCapture) LocationSessionMode.PARKING_TRANSITION else current

        // Walking only matters as an end-of-drive confirmation. Walking with no vehicle
        // session behind it is someone on foot, and must not start a location request.
        MotionEventKind.STARTED_WALKING -> when (current) {
            LocationSessionMode.DRIVING,
            LocationSessionMode.DRIVING_CANDIDATE,
            LocationSessionMode.PARKING_TRANSITION,
            -> LocationSessionMode.PARKING_TRANSITION

            LocationSessionMode.IDLE -> LocationSessionMode.IDLE
        }

        // Stationary is supporting evidence only (§8) and never changes the request shape:
        // a car at a red light is STILL, and dropping capture there would lose the drive.
        MotionEventKind.BECAME_STATIONARY,
        MotionEventKind.STOPPED_BEING_STATIONARY,
        -> current
    }

    /** Promotion to [LocationSessionMode.DRIVING] once §7's guard is satisfied. */
    fun promoteOnDrivingConfirmed(current: LocationSessionMode): LocationSessionMode =
        if (current == LocationSessionMode.DRIVING_CANDIDATE) LocationSessionMode.DRIVING else current

    /**
     * The capture the engine's state asks for, or null for none — iOS's
     * `isLocationCaptureWanted`, with the mode that state would open it in.
     *
     * `PARKED` wants one only while a `vehicle_enter` has opened a departure's evidence: §11's
     * 500 m bar is measured from fixes.
     */
    fun modeWantedBy(engine: DetectionEngineState): LocationSessionMode? = when (engine.state) {
        DetectionState.DRIVING_CANDIDATE, DetectionState.DEPARTURE_CANDIDATE -> LocationSessionMode.DRIVING_CANDIDATE
        DetectionState.DRIVING -> LocationSessionMode.DRIVING
        DetectionState.PARKING_TRANSITION -> LocationSessionMode.PARKING_TRANSITION
        DetectionState.PARKED -> if (engine.session != null) LocationSessionMode.DRIVING_CANDIDATE else null
        // docs/05 §3a "A stop-only candidate can still be a long light" rule 1 / §19: a
        // stop-only candidate keeps the transition's capture until the transition's own
        // deadline, because both of its resume rows are fed by fixes and motion. The same mode
        // as the transition's, so PARKING_TRANSITION -> CANDIDATE_PENDING is no edge and the
        // capture is released when the window closes (deadline, exit, walk, answer). "Still
        // holds the capture" (§19) needs no flag here: the runtime closes a window whose
        // capture is gone before the engine or this policy ever sees it (docs/05 §3a "The
        // window lives exactly as long as its capture"), so an open window holds one.
        DetectionState.CANDIDATE_PENDING ->
            if (engine.stopOnlyResumeWindow != null) LocationSessionMode.PARKING_TRANSITION else null
        DetectionState.IDLE -> null
    }

    /**
     * What the capture becomes after a batch that took the engine's want from [wantedBefore]
     * to [wantedAfter], given the [current] capture. Asked after **every** batch, whether or
     * not the want changed (docs/05 §19).
     *
     * Deliberately narrow — only what the motion policy cannot see:
     *
     * - **The engine wants none**: released, whatever mode it was in and whether or not the
     *   want just changed — a candidate, a lapse, the ceiling, getting out of a parked car, a
     *   stop-only window closed by its deadline or an exit, or a kerb capture a motion edge
     *   opened for an exit the engine then refused. The one exception is a capture the engine
     *   never owned: the P0 diagnostics override ([ownedByDiagnostics]).
     * - **The engine started wanting one with nothing running** (a car-link connect, the
     *   fuel-stop reconnect): opened in the engine's mode.
     * - **The engine still wants one that a reboot or an app update dropped**
     *   ([droppedBySystem]): reopened in the engine's mode, as iOS reopens it on restore
     *   (docs/05 §14). The system took it, not its own deadline, so no leak guard is undone;
     *   the reopened capture gets the planner's usual deadlines.
     * - **A transition resumed `DRIVING` while the capture had narrowed to the kerb**:
     *   widened back, or the kerb profile's short deadline would end it mid-drive.
     *
     * Everything else keeps [current]. In particular a capture whose own hard deadline ended
     * it is not reopened while the engine still wants one — that deadline is one of the three
     * leak guards, and the engine's windows end the drive itself.
     */
    fun modeFollowingEngine(
        wantedBefore: LocationSessionMode?,
        wantedAfter: LocationSessionMode?,
        current: LocationSessionMode,
        ownedByDiagnostics: Boolean,
        droppedBySystem: Boolean = false,
    ): LocationSessionMode = when {
        wantedAfter == null -> if (ownedByDiagnostics) current else LocationSessionMode.IDLE
        current == LocationSessionMode.IDLE -> if (wantedBefore == null || droppedBySystem) wantedAfter else current
        wantedAfter == LocationSessionMode.DRIVING && current == LocationSessionMode.PARKING_TRANSITION ->
            LocationSessionMode.DRIVING
        else -> current
    }
}

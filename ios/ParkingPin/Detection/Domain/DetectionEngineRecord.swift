import Foundation

/// Everything `ParkingDetectionEngine` holds beyond the §14 checkpoint fields, as one value.
///
/// docs/05 §14 "Both platforms persist and reload their whole engine state" (2026-09-29):
/// the engine keeps its state *in* this value — its properties forward here — so there is no
/// second copy that a checkpoint could forget. It is written with the checkpoint
/// (`DetectionCheckpoint.engine`) whenever it changes and restored verbatim, which is what
/// Android's `DetectionEngineState` has always been. Nothing is rebuilt on a relaunch.
///
/// On-device only, like `lastReliableLocation`: the drives hold anchor fixes. It is never
/// logged, and `DiagnosticsReport` projects the checkpoint field by field so none of this
/// leaves the device.
///
/// What is **not** here: the per-process diagnostics counters (§6's update/reject counts),
/// which change no decision, and the verdict of the fix being handled, which lives for one
/// event.
struct DetectionEngineRecord: Sendable, Equatable, Codable {
    /// The open session: the drive of `DRIVING_CANDIDATE` and `DRIVING`, and the get-in or
    /// departure §11 measures in `PARKED` and `DEPARTURE_CANDIDATE`.
    var driving: DrivingEvidence?
    /// docs/05 §11 "A lost capture decides nothing": the open session lost its bounded
    /// capture and goes on without one.
    var isDrivingCaptureLost = false
    /// Non-nil exactly while the state is `PARKING_TRANSITION`.
    var transition: ParkingTransition?
    /// The drive a pending candidate came from, kept only while `CANDIDATE_PENDING`.
    var candidateDrive: DrivingEvidence?
    /// docs/05 §3a "A stop-only candidate can still be a long light".
    var candidateResume: CandidateResume?
    /// docs/05 §3a "A stop-only candidate takes the exit that follows it".
    var candidateRescore: CandidateRescore?
    /// The vehicle-activity *level* (`DetectionEvent`).
    var isVehicleActive = false
    /// When the current stretch of vehicle activity began, as the evidence dates it.
    var vehicleActiveSince: Date?
    /// §3a "a connected car link suppresses `movementIdleWindow`". Restored with the rest, so
    /// the adapter's first route sample after a relaunch compares against what the engine
    /// believed and derives a disconnect for a link that went away while the process was
    /// dead — Android's obligation to re-assert the link, met the same way.
    var connectedCarLinks: Set<CarLinkKind> = []
    /// §12 / §3a "One candidate per travel session".
    var hasProducedCandidateInSession = false
    /// docs/05 §11d: where the active parking says the car is — the confirmed candidate's
    /// fix, or the location a hand save carried. `nil` when the parking has none, which
    /// turns the passenger test off.
    var parkedLocation: LastReliableLocation?
    /// docs/05 §11d: the newest vehicle evidence of a ride already judged to be in someone
    /// else's car. While it is recent, more of that ride's vehicle evidence opens nothing.
    var passengerRideLastVehicleAt: Date?
}

/// The drive that just ended, held while `PARKING_TRANSITION` decides what it was.
///
/// §3a: "codes accumulate as evidence arrives and travel with the candidate."
/// `evidence` is that accumulation — added to as signals show up and handed to the
/// policy unchanged, never recomputed at the end from the final state.
struct ParkingTransition: Sendable, Equatable, Codable {
    let enteredAt: Date
    let entryReason: DrivingSessionEndReason
    /// The finished drive, **still recording**. docs/05 §3a: a fix inside the window
    /// either confirms the parking (a location stop) or resumes the drive (movement
    /// returns), and both need the drive's anchors; a resume hands this back as
    /// `driving`, so the trip keeps its start, distance and confirmation.
    var drive: DrivingEvidence
    /// §8b: duration and distance are measured at the vehicle end — this state's entry
    /// — and frozen there, so a walk to the lift adds neither. `nil` duration: unknown,
    /// a transition migrated from a pre-schema-3 checkpoint.
    let driveDurationAtEnd: TimeInterval?
    let driveDistanceAtEnd: Double
    /// §5's inheritance bound: the travel session's start. `nil` for a migrated
    /// transition, where only the age bound applies.
    let sessionStartedAt: Date?
    /// The flags this transition earns as signals arrive — §3a "codes accumulate as
    /// evidence arrives". Discarded with the transition when the drive resumes: that
    /// stop was a red light, and its evidence belonged to it.
    var evidence: ParkingEvidence
    /// Whether the bounded capture is still running for this transition.
    var isCapturing: Bool

    init(
        enteredAt: Date,
        entryReason: DrivingSessionEndReason,
        drive: DrivingEvidence,
        driveDurationAtEnd: TimeInterval?,
        driveDistanceAtEnd: Double? = nil,
        sessionStartedAt: Date?,
        vehicleExitDetected: Bool,
        isCapturing: Bool
    ) {
        self.enteredAt = enteredAt
        self.entryReason = entryReason
        self.drive = drive
        self.driveDurationAtEnd = driveDurationAtEnd
        // A migrated transition has a fresh drive with nothing in it; the distance the
        // real one covered survives only as the checkpoint's `travelDistanceEstimate`.
        self.driveDistanceAtEnd = driveDistanceAtEnd ?? drive.distanceMeters
        self.sessionStartedAt = sessionStartedAt
        evidence = ParkingEvidence(
            hasMeaningfulVehicleSession: true,
            vehicleEnded: true,
            vehicleExitDetected: vehicleExitDetected
        )
        self.isCapturing = isCapturing
    }

    /// docs/05 §8b `vehicle_exit_detected`: which entries *are* an exit. The iOS
    /// adapter derives its exit from a walk (`walkingDetected`) or from Core Motion
    /// going silent (`vehicleEvidenceExpired`) — the normalized `vehicle_exit` Android
    /// is handed. `movementIdle` is an inference from absence and is not.
    static func isExplicitExit(_ reason: DrivingSessionEndReason) -> Bool {
        switch reason {
        case .vehicleExit, .walkingDetected, .vehicleEvidenceExpired, .carLinkDisconnected:
            true
        case .movementIdle, .maximumDurationReached, .authorizationLost, .captureFailed,
             .smartDetectionDisabled, .fieldTestStopped:
            false
        }
    }
}

/// The window in which a stop-only candidate can still turn out to be a long light.
///
/// Exists exactly while the bounded capture the transition kept is running (§3a "The
/// window lives exactly as long as its capture"): it is never opened without one, and
/// every door that stops the capture closes it.
struct CandidateResume: Sendable, Equatable, Codable {
    /// The drive's end — the transition's entry. A fix older than this says nothing
    /// about whether the car moved on.
    let driveEndedAt: Date
    /// `driveEndedAt + transitionWindow`: the deadline the transition itself had.
    let deadline: Date
    /// Fixes inside the window that reported moving speed. §7's "one event alone never
    /// confirms": a single Doppler spike under a slab must not withdraw a parking.
    var reportedMovingFixes = 0
}

/// docs/05 §3a "A stop-only candidate takes the exit that follows it" (2026-10-01): what a
/// stop-only candidate was scored on, so an exit or a walk before `deadline` can re-score it.
/// Android's `StopOnlyRescore`.
///
/// Separate from `CandidateResume` on purpose. That window is the capture's lifetime and an
/// exit closes it; the walk that follows the exit a second later must still count. Read only
/// while it names the pending candidate, so a stale one is inert and is never cleared path by
/// path.
struct CandidateRescore: Sendable, Equatable, Codable {
    let candidateId: UUID
    /// The drive's end plus `transitionWindow`: the car has provably not moved on before it.
    let deadline: Date
    /// The candidate's evidence so far, signals that did not move the bucket included.
    var evidence: ParkingEvidence
}

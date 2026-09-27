import Foundation

/// Everything the engine knows, projected for diagnostics and for the adapter.
///
/// A value, not a window into the actor: the coordinator reads it after every batch of
/// effects and copies what the §19 field checklist needs into `RehydrationSnapshot`.
struct DetectionEngineSnapshot: Sendable, Equatable {
    var checkpoint: DetectionCheckpoint
    var driving: DrivingEvidence?
    /// The finished drive a `PARKING_TRANSITION` is still recording fixes into. Separate
    /// from `driving`, which is non-nil only while a drive is *open*: the adapter's silence
    /// bound and field-test hooks ask that question, and a transition is not an open drive.
    var transitionDrive: DrivingEvidence?
    var parkingTransitionEnteredAt: Date?
    var isVehicleActive: Bool
    var connectedCarLinks: Set<CarLinkKind>
    var pendingCandidateId: UUID?
    /// §6 selection outcomes. Zero updates against a non-zero fix count is how a
    /// mis-set accuracy gate announces itself in the field.
    var reliableLocationUpdateCount: Int
    var reliableLocationRejectCount: Int
    var lastReliableLocationRejection: ReliableLocationRejection?
    /// Whether the bounded capture should be running. docs/05 §3a / §19 (2026-09-27): it
    /// runs while a drive is open **and** while `PARKING_TRANSITION` decides, because two
    /// of that state's three exits are location rows. The adapter releases Core Location
    /// on this, not on `driving`, or no fix could ever reach those rows on a device.
    var isLocationCaptureWanted: Bool
}

/// The §3a transition table, and nothing else.
///
/// `docs/05_PARKING_DETECTION_ENGINE.md` §16 fixes this shape — `restore(_:)` plus
/// `handle(_:) -> [DetectionEffect]` — and the reason it is an `actor` is the same reason
/// `BackgroundCoordinator` is: the state is touched from a background relaunch, a delegate
/// callback and the UI.
///
/// ### What lives here and what does not
/// The engine owns the seven states, the windows that move between them, and the evidence
/// that travels with a candidate. It owns **no SDK, no clock and no I/O**: every decision
/// takes its time from the event that caused it, and every consequence leaves as a
/// `DetectionEffect`. That is what makes `platform-tests/*.json` replayable through the
/// same code the device runs — the fixture runner is a `for` loop over `handle(_:)`.
///
/// ### The evidence policies are reused, not restated
/// `DrivingEvidence`, `MovementEvidencePolicy`, `ReliableLocationPolicy`,
/// `ParkingCandidatePolicy` and `ParkingTransitionPolicy` already encode §5–§9 and were
/// derived from field traces. This type decides *when* to consult them, never what they
/// should say.
actor ParkingDetectionEngine {
    private var checkpoint: DetectionCheckpoint
    /// Non-nil exactly while a bounded session is open — `DRIVING_CANDIDATE` or `DRIVING`.
    private var driving: DrivingEvidence?
    /// Non-nil exactly while the state is `PARKING_TRANSITION`.
    private var transition: ParkingTransition?
    /// The drive a pending candidate came from, kept only while `CANDIDATE_PENDING` so a
    /// car-link reconnect (§3a, the fuel stop) resumes the *same* trip — its start, its
    /// distance — rather than a new one. Cleared on leaving the state any other way.
    private var candidateDrive: DrivingEvidence?
    /// docs/05 §3a "A stop-only candidate can still be a long light" (2026-09-27). Non-nil
    /// while the pending candidate came from a transition that nothing but absence ended —
    /// entered by `movementIdle`, confirmed by a stop, no exit, no walk — and the drive's
    /// `transitionWindow` has not yet run out. While it stands `candidateDrive` keeps
    /// recording, and reported movement or `vehicle_enter` takes the candidate back.
    private var candidateResume: CandidateResume?
    /// What the fix this event carried did to the drive, for `applyEdge`. Set by `ingest`,
    /// consumed by the edge after the windows have been judged, cleared per event.
    private var lastFixVerdict: FixVerdict?
    /// The vehicle-activity *level*. Set by `vehicleEnter`, cleared by `vehicleExit` — see
    /// `DetectionEvent` for why this is a level and not a decaying sample.
    private var isVehicleActive = false
    /// When the current stretch of vehicle activity began, as the *evidence* dates it.
    ///
    /// The promotion bar is measured from the later of this and the session start. Both
    /// halves matter: a car link opens `DRIVING_CANDIDATE` before any vehicle activity
    /// exists, so the link must not buy those 90 seconds; and Core Motion history hands
    /// iOS a sample that is already minutes old, which must not buy them either.
    private var vehicleActiveSince: Date?
    private var connectedCarLinks: Set<CarLinkKind> = []
    private var pendingCandidate: ParkingCandidate?
    /// §12 / §3a "One candidate per travel session". Cleared on the way back to `IDLE` and
    /// when a candidate is retired unanswered, which is what makes the fuel-stop reconnect
    /// (§17 fixture 3) able to produce the real parking later in the same trip.
    private var hasProducedCandidateInSession = false
    private var reliableLocationUpdateCount = 0
    private var reliableLocationRejectCount = 0
    private var lastReliableLocationRejection: ReliableLocationRejection?
    /// Injected so a fixture run and a test can be deterministic; `UUID.init` in production.
    private let makeCandidateId: @Sendable () -> UUID

    init(
        checkpoint: DetectionCheckpoint? = nil,
        makeCandidateId: @escaping @Sendable () -> UUID = { UUID() }
    ) {
        self.checkpoint = checkpoint ?? DetectionCheckpoint.initial(at: .distantPast)
        self.makeCandidateId = makeCandidateId
    }

    /// The drive that just ended, held while `PARKING_TRANSITION` decides what it was.
    ///
    /// §3a: "codes accumulate as evidence arrives and travel with the candidate."
    /// `evidence` is that accumulation — added to as signals show up and handed to the
    /// policy unchanged, never recomputed at the end from the final state.
    private struct ParkingTransition {
        let enteredAt: Date
        let entryReason: DrivingSessionEndReason
        /// The finished drive, **still recording**. docs/05 §3a: a fix inside the window
        /// either confirms the parking (a location stop) or resumes the drive (movement
        /// returns), and both need the drive's anchors; a resume hands this back as
        /// `driving`, so the trip keeps its start, distance and confirmation.
        var drive: DrivingEvidence
        /// §8b: duration and distance are measured at the vehicle end — this state's entry
        /// — and frozen there, so a walk to the lift adds neither. `nil` duration: unknown,
        /// a transition rebuilt from a checkpoint.
        let driveDurationAtEnd: TimeInterval?
        let driveDistanceAtEnd: Double
        /// §5's inheritance bound: the travel session's start. `nil` for a rebuilt
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
            // A rebuilt transition has a fresh drive with nothing in it; the distance the
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
    private struct CandidateResume {
        /// The drive's end — the transition's entry. A fix older than this says nothing
        /// about whether the car moved on.
        let driveEndedAt: Date
        /// `driveEndedAt + transitionWindow`: the deadline the transition itself had.
        let deadline: Date
        /// Fixes inside the window that reported moving speed. §7's "one event alone never
        /// confirms": a single Doppler spike under a slab must not withdraw a parking.
        var reportedMovingFixes = 0
    }

    /// What one accepted fix did to the drive it was folded into.
    private struct FixVerdict {
        let timestamp: Date
        /// It counted as movement under §7, by either route.
        let moved: Bool
        /// It *reported* a speed at or above `movingSpeedThreshold`. The only movement that
        /// may take a candidate back (§3a): the distance fallback reads the jitter around a
        /// parked car as travel.
        let reportedMoving: Bool
        /// It reported a speed below `movingSpeedThreshold` — §3a's "location stop".
        let stopped: Bool
    }

    // MARK: - Lifecycle

    func snapshot() -> DetectionEngineSnapshot {
        DetectionEngineSnapshot(
            checkpoint: checkpoint,
            driving: driving,
            transitionDrive: transition?.drive,
            parkingTransitionEnteredAt: transition?.enteredAt,
            isVehicleActive: isVehicleActive,
            connectedCarLinks: connectedCarLinks,
            pendingCandidateId: checkpoint.candidateId,
            reliableLocationUpdateCount: reliableLocationUpdateCount,
            reliableLocationRejectCount: reliableLocationRejectCount,
            lastReliableLocationRejection: lastReliableLocationRejection,
            isLocationCaptureWanted: driving != nil || transition?.isCapturing == true
                || candidateResume != nil
        )
    }

    var state: DetectionState { checkpoint.state }

    /// docs/05 §16. Rebuilds what a dead process left behind.
    ///
    /// A checkpoint is not a session: it says which state was current and when it was
    /// entered, so the bounded session and the parking transition are *recreated* from it
    /// rather than resumed. docs/04 §3 requires that, because nothing else will ever
    /// reopen a session the previous process owned.
    ///
    /// `pendingCandidate` is passed separately because it lives in its own file (§10a) and
    /// only the adapter knows how to read it.
    func restore(
        _ restored: DetectionCheckpoint?,
        pendingCandidate: ParkingCandidate? = nil,
        seedIfAbsent: Bool = true,
        now: Date
    ) -> [DetectionEffect] {
        checkpoint = restored ?? DetectionCheckpoint.initial(at: now)
        self.pendingCandidate = pendingCandidate
        hasProducedCandidateInSession = checkpoint.state == .candidatePending

        var effects: [DetectionEffect] = []
        if restored == nil, seedIfAbsent {
            // First run on this install: give process death something to restore.
            effects.append(.persistCheckpoint(checkpoint))
        }

        // §10: checked before the state is consulted, because the candidate lives in its
        // own file and the two can disagree after a process death. A candidate whose 45
        // minutes ran out must be withdrawn whatever the checkpoint believes.
        if let pendingCandidate, pendingCandidate.isExpired(now: now) {
            effects += retirePendingCandidate(now: now)
        }

        switch checkpoint.state {
        case .drivingCandidate, .driving:
            effects += restoreDrivingSession(now: now)
        case .parkingTransition:
            effects += restoreParkingTransition(now: now)
        case .candidatePending:
            isVehicleActive = false
        case .idle, .parked, .departureCandidate:
            break
        }
        effects += tick(now: now)
        return effects
    }

    private func restoreDrivingSession(now: Date) -> [DetectionEffect] {
        let resumed = DrivingEvidence(
            startedAt: checkpoint.stateEnteredAt,
            lastVehicleEvidenceAt: checkpoint.lastAutomotiveAt
        )
        // A session that would end the instant it reopens is not worth the GPS. The
        // ordinary expiry rule decides, so there is one definition of "too old".
        if let expiry = DrivingSessionTimeoutPolicy.expiryReason(for: resumed, now: now) {
            driving = nil
            isVehicleActive = false
            return [.sessionEnded(reason: expiry, at: now), .stopLocationCapture] + moveTo(.idle, now: now)
        }
        driving = resumed
        isVehicleActive = true
        vehicleActiveSince = checkpoint.lastAutomotiveAt ?? checkpoint.stateEnteredAt
        if checkpoint.state == .driving {
            driving?.markConfirmed(at: checkpoint.stateEnteredAt)
        }
        return [.startBoundedLocationCapture]
    }

    private func restoreParkingTransition(now: Date) -> [DetectionEffect] {
        guard !ParkingTransitionPolicy.hasElapsed(enteredAt: checkpoint.stateEnteredAt, now: now) else {
            return moveTo(.idle, now: now)
        }
        // The evidence is thinner than the original — the drive's duration, its anchors
        // and how it ended are not checkpoint fields — and it says so rather than guessing
        // (the distance *is* a field, written at the entry, and is kept):
        // the duration is `nil` (unknown, which §8's short-trip penalty does not punish),
        // and no exit is credited, because nothing here knows one was detected. A drive is
        // still recreated, so a fix inside the window can confirm or resume it (§3a).
        var drive = DrivingEvidence(
            startedAt: checkpoint.stateEnteredAt,
            lastVehicleEvidenceAt: checkpoint.lastAutomotiveAt
        )
        drive.markConfirmed(at: checkpoint.stateEnteredAt)
        transition = ParkingTransition(
            enteredAt: checkpoint.stateEnteredAt,
            entryReason: .vehicleEvidenceExpired,
            drive: drive,
            driveDurationAtEnd: nil,
            driveDistanceAtEnd: checkpoint.travelDistanceEstimate,
            sessionStartedAt: nil,
            vehicleExitDetected: false,
            isCapturing: true
        )
        // docs/05 §3a / §19: the capture runs while the transition decides, and nothing
        // else will reopen it for a process that died mid-window.
        return [.startBoundedLocationCapture]
    }

    // MARK: - The event loop

    /// docs/05 §16. One event in, the effects it caused out.
    ///
    /// The order — ingest, catch up the clock, apply the edge, catch up again — is not
    /// cosmetic. Two fixtures depend on it:
    ///
    /// * `red_light_no_candidate.json` has a moving fix arriving exactly
    ///   `movementIdleWindow` after the last one. Folding the fix in **before** the
    ///   windows are examined is what keeps that a drive rather than a parking.
    /// * `vehicle_then_walk.json` needs the opposite for `walking_enter`: the window is
    ///   examined **before** the edge, so a walk that arrives after `transitionWindow` has
    ///   closed lands in `IDLE` and confirms nothing.
    ///
    /// `now` defaults to the event's own timestamp, which is what a fixture replay wants:
    /// there, the event stream *is* the clock. The iOS adapter passes the wake time
    /// instead, because Core Motion history is read in arrears — the evidence is minutes
    /// old and the windows still have to be judged from the present.
    func handle(_ event: DetectionEvent, now observedAt: Date? = nil) -> [DetectionEffect] {
        let now = observedAt ?? event.timestamp
        // §3a's `any → PARKED` row (§11c) is answered before any evidence is folded or any
        // window judged: the user has said where the car is, and nothing the engine was
        // inferring — including a window that happened to close at this instant — outranks
        // that. Android has always answered it first; iOS used to let a lapsing window
        // report `candidateRuleUnmet` on the way.
        if case .userSavedParking = event {
            return adoptUserSavedParking(now: now)
        }
        lastFixVerdict = nil
        var effects = ingest(event, now: now)
        effects += tick(now: now)
        effects += applyEdge(event, now: now)
        effects += tick(now: now)
        return effects
    }

    /// Evidence that the event carries, with no state transition attached.
    private func ingest(_ event: DetectionEvent, now: Date) -> [DetectionEffect] {
        switch event {
        case let .location(fix):
            return recordFix(fix, now: now)
        case let .locationQualityDegraded(at, _, to):
            // §8b "location_quality_degraded", §13's underground pattern. Recorded on the
            // drive with its time — in `DRIVING` too, where iOS used to drop it — and
            // judged against the drive's end when a candidate is scored. Supporting
            // evidence only: §6 will not let it satisfy the candidate rule alone.
            if driving != nil {
                driving?.noteQualityDegraded(at: at, to: to)
            } else {
                transition?.drive.noteQualityDegraded(at: at, to: to)
            }
            return []
        case .vehicleEnter, .vehicleExit, .walkingEnter, .stationaryEnter, .stationaryExit,
             .carLinkConnected, .carLinkDisconnected, .timerTick,
             .userConfirmedParking, .userRejectedParking, .userSavedParking:
            return []
        }
    }

    /// The transitions this event triggers by itself (the rows of §3a whose condition is
    /// an event rather than a window).
    private func applyEdge(_ event: DetectionEvent, now: Date) -> [DetectionEffect] {
        switch event {
        case let .vehicleEnter(at, _):
            return handleVehicleEnter(at: at, now: now)
        case .vehicleExit:
            return handleVehicleExit(now: now)
        case .walkingEnter:
            return noteConfirmationSignal(walking: true, now: now)
        case .stationaryEnter:
            return noteConfirmationSignal(walking: false, now: now)
        case let .carLinkConnected(_, kind):
            return handleCarLinkConnected(kind: kind, now: now)
        case let .carLinkDisconnected(_, kind):
            return handleCarLinkDisconnected(kind: kind, now: now)
        case .userConfirmedParking:
            return resolveCandidate(confirmed: true, now: now)
        case .userRejectedParking:
            return resolveCandidate(confirmed: false, now: now)
        case .userSavedParking:
            // Answered in `handle` before anything else; never reaches here.
            return []
        case .location:
            return applyFixEdge(now: now)
        // §3a has no row for these: `stationary_exit` inside a drive is a car leaving a
        // light, a degradation is supporting evidence, and a tick is only an invitation to
        // re-examine the windows.
        case .stationaryExit, .locationQualityDegraded, .timerTick:
            return []
        }
    }

    /// Every §3a row whose condition is a window rather than an event.
    ///
    /// Run to a fixed point because a single wake can be minutes after the last one and
    /// has to catch up more than one boundary: a drive that promoted, went quiet and then
    /// let its transition window close is three rows in one tick. The iteration cap is a
    /// bug-containment device, not a rule — every branch below moves the state.
    private func tick(now: Date) -> [DetectionEffect] {
        var effects = expireLeftBehindCandidate(now: now)
        for _ in 0 ..< Self.maximumTickCascade {
            let step = tickOnce(now: now)
            if step.isEmpty { break }
            effects += step
        }
        return effects
    }

    /// Enough to walk `DRIVING_CANDIDATE → DRIVING → PARKING_TRANSITION → IDLE` and stop.
    private static let maximumTickCascade = 6

    /// docs/05 §10 (2026-09-27): the 45 minutes bound the candidate whatever the state.
    ///
    /// A candidate left behind by `CANDIDATE_PENDING → DRIVING_CANDIDATE` stays answerable
    /// (§3a), and used to stay for ever if the new drive never produced one of its own:
    /// only `CANDIDATE_PENDING` looked at the clock. Withdrawn here without moving the
    /// state — the drive under way is not the candidate's business.
    private func expireLeftBehindCandidate(now: Date) -> [DetectionEffect] {
        guard checkpoint.state != .candidatePending,
              let candidate = pendingCandidate,
              candidate.isExpired(now: now)
        else { return [] }
        pendingCandidate = nil
        return [.withdrawCandidate(id: candidate.id)]
    }

    /// docs/05 §3a "Window rows are stamped at their deadline" (2026-09-27).
    ///
    /// A window-driven row happens when its window closed, not when some later event
    /// noticed. Stamped at observation, the next window started late by however long the
    /// device happened to be quiet, so the same trace replayed differently with and without
    /// an unrelated tick — the non-determinism §3a's timeout section exists to rule out.
    /// Never earlier than the current state's own entry, never later than now.
    private func stamp(deadline: Date, now: Date) -> Date {
        min(now, max(deadline, checkpoint.stateEnteredAt))
    }

    private func tickOnce(now: Date) -> [DetectionEffect] {
        // §3a "DECIDED 2026-09-20: a connected car link suppresses `movementIdleWindow`".
        // A phone still attached to the car's audio during a 180-second gap is at a red
        // light, in a tunnel or on a ramp — not in a car that has been left. Without it the
        // row fires on every underground drive the moment anything ticks, because movement
        // evidence only advances on a location fix and there are none there: "no sky" would
        // read as "not moving".
        //
        // Only that row. The 2-hour ceiling still applies, and so does
        // `drivingCandidateWindow` — a link connected with no drive is someone sitting in a
        // parked car with the radio on, which is exactly what that row is for.
        //
        // The latch cannot outlive the link on this platform: `CarLinkMonitor` samples
        // `AVAudioSession.currentRoute` at every wake and derives the edge, so a link that
        // vanished while the process was dead produces a disconnect on the next wake.
        let carLinkHolds = !connectedCarLinks.isEmpty
        switch checkpoint.state {
        case .drivingCandidate:
            // Promotion is checked first, and that ordering is a decision: both windows
            // can be elapsed on the same late wake, and promotion's condition became true
            // first (`minimumVehicleDuration` is 90 s, `drivingCandidateWindow` is 300 s).
            // `subway_commute_underground.json` is exactly that case — the first event
            // after `vehicle_enter` is 303 s later — and reading the timeout first would
            // send a 45-minute ride back to `IDLE`.
            if isVehicleActive, let session = driving,
               now.timeIntervalSince(max(session.startedAt, vehicleActiveSince ?? session.startedAt))
               >= DrivingConfirmationPolicy.minimumVehicleDuration {
                return promoteToDriving(now: now)
            }
            let lapse = checkpoint.stateEnteredAt.addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow)
            if now >= lapse {
                driving = nil
                let at = stamp(deadline: lapse, now: now)
                return [.sessionEnded(reason: .vehicleEvidenceExpired, at: at), .stopLocationCapture]
                    + moveTo(.idle, now: at)
            }
            return []

        case .driving:
            guard let evidence = driving,
                  let reason = DrivingSessionTimeoutPolicy.boundReason(for: evidence, now: now)
            else { return [] }
            // The link outranks an inference from absence, but only this one: the 2-hour
            // ceiling is a bound on the session itself and holds either way.
            if carLinkHolds, reason == .movementIdle { return [] }
            let endedAt = stamp(deadline: Self.deadline(of: reason, for: evidence) ?? now, now: now)
            return endDrivingSession(reason: reason, now: endedAt)

        case .parkingTransition:
            guard let transition,
                  ParkingTransitionPolicy.hasElapsed(enteredAt: transition.enteredAt, now: now)
            else { return [] }
            // §3a `PARKING_TRANSITION → IDLE`. Silent by contract: nothing was persisted on
            // the way in beyond the checkpoint and nothing was shown, so there is nothing
            // to take back.
            let lapse = stamp(
                deadline: transition.enteredAt.addingTimeInterval(ParkingTransitionPolicy.transitionWindow),
                now: now
            )
            return closeTransition() + [.candidateRuleUnmet] + moveTo(.idle, now: lapse)

        case .candidatePending:
            // The resume window closing moves nothing — the candidate stands — but it is
            // judged first and does not end the pass, so an expiry due on the same late
            // wake is still seen.
            return lapseCandidateResume(now: now) + candidatePendingWindows(now: now)

        case .departureCandidate:
            return departureWindows(now: now)

        case .parked:
            guard let evidence = driving, departureBarsCleared(evidence, now: now) else { return [] }
            return moveTo(.departureCandidate, now: now)

        case .idle:
            return []
        }
    }

    /// §10's 45 minutes, for the state that holds the candidate.
    private func candidatePendingWindows(now: Date) -> [DetectionEffect] {
            guard let candidate = pendingCandidate else {
                // The checkpoint says a candidate is pending and its file is gone. §10's
                // 45 minutes still bound the state, and leaving it here forever would
                // make every later drive invisible — so the bound is applied to the state
                // rather than to a candidate nobody can read.
                let expiry = checkpoint.stateEnteredAt.addingTimeInterval(ParkingCandidatePolicy.expiry)
                guard now >= expiry else { return [] }
                return moveTo(.idle, now: stamp(deadline: expiry, now: now))
            }
            guard candidate.isExpired(now: now) else { return [] }
            // docs/05 §10: no record is created and nothing is reported — docs/17 §2 has
            // no event for a guess that went unanswered.
            return retirePendingCandidate(now: stamp(deadline: candidate.expiresAt, now: now))
    }

    /// docs/05 §3a "A stop-only candidate can still be a long light": the drive's own
    /// `transitionWindow` ran out with the car still standing, so the capture kept for it
    /// is released. Not a state change — the candidate is exactly as valid as before.
    private func lapseCandidateResume(now: Date) -> [DetectionEffect] {
        guard let hold = candidateResume, now >= hold.deadline else { return [] }
        return closeCandidateResume()
    }

    /// §11's departure rows.
    private func departureWindows(now: Date) -> [DetectionEffect] {
            guard let evidence = driving else { return moveTo(.parked, now: now) }
            // §11 "departure confirmed" is §7's guard in full — the one bar this project
            // has for a meaningful driving session, movement clause included. Leaving a
            // parking record open is recoverable; ending one the user is still sitting in
            // is not, which is why departure is the one place the stricter guard is right.
            if evidence.meetsDrivingConfirmation(now: now) {
                return confirmDeparture(now: now)
            }
            // Vehicle evidence went quiet without ever becoming a drive: the phone woke up
            // in a parked car. Back to `PARKED`, silently, having ended nothing.
            //
            // Strictly *past* the window (docs/05 §11, 2026-09-27): §7's guard still counts
            // evidence exactly `vehicleEvidenceMaxAge` old as recent, so at that instant the
            // departure may still confirm, and the lapse must not pre-empt it. Android's
            // settle used to lapse first on the same boundary.
            let lapse = (evidence.lastVehicleEvidenceAt ?? evidence.startedAt)
                .addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow)
            guard now > lapse else { return [] }
            driving = nil
            return [.stopLocationCapture] + moveTo(.parked, now: stamp(deadline: lapse, now: now))
    }

    /// When a `DRIVING` bound's window closed. `nil` for a reason with no deadline of its own.
    private static func deadline(of reason: DrivingSessionEndReason, for evidence: DrivingEvidence) -> Date? {
        switch reason {
        case .maximumDurationReached:
            evidence.startedAt.addingTimeInterval(DrivingSessionTimeoutPolicy.maximumDuration)
        case .movementIdle:
            evidence.lastMovingSampleAt?.addingTimeInterval(ParkingTransitionPolicy.movementIdleWindow)
        case .walkingDetected, .vehicleExit, .carLinkDisconnected, .vehicleEvidenceExpired,
             .authorizationLost, .captureFailed, .smartDetectionDisabled, .fieldTestStopped:
            nil
        }
    }

    /// §11 `DEPARTURE_CANDIDATE → DRIVING`, and the one effect that closes the parking.
    ///
    /// The record ends when the car pulled away — `checkpoint.stateEnteredAt` is when §11's
    /// two bars were first cleared — not now, which is however long §7's guard took to be
    /// satisfied afterwards.
    ///
    /// **The drive is confirmed here too.** §7's guard has just been met in full, which is a
    /// stronger bar than the 90 s latch `promoteToDriving` sets. Leaving the latch unset made
    /// `endDrivingSession` read this drive as unconfirmed and go straight to `IDLE`, so the
    /// parking at the end of it was never detected — and since nearly every drive starts from
    /// a parking, after the first one no parking was (iPhone, 2026-09-26: auto-ended at
    /// 20:35, parked at 21:01, no candidate).
    private func confirmDeparture(now: Date) -> [DetectionEffect] {
        let departedAt = checkpoint.stateEnteredAt
        hasProducedCandidateInSession = false
        driving?.markConfirmed(at: now)
        return [.endActiveParking(at: departedAt)] + moveTo(.driving, now: now) + [.drivingConfirmed(at: now)]
    }

    /// §11 `PARKED → DEPARTURE_CANDIDATE`: vehicle ≥ 90 s **and** movement ≥ 500 m.
    ///
    /// Both, because a phone that woke up in a parked car satisfies the first on its own.
    private func departureBarsCleared(_ evidence: DrivingEvidence, now: Date) -> Bool {
        guard isVehicleActive,
              now.timeIntervalSince(max(evidence.startedAt, vehicleActiveSince ?? evidence.startedAt))
              >= DrivingConfirmationPolicy.minimumVehicleDuration
        else { return false }
        return evidence.distanceMeters >= Self.departureMovementMeters
    }

    /// §11's movement bar. Deliberately lower than §7's 800 m: this only opens a candidate
    /// state, and §7's guard in full is what actually ends the parking.
    private static let departureMovementMeters: Double = 500

    // MARK: - §3a rows driven by events

    private func handleVehicleEnter(at date: Date, now: Date) -> [DetectionEffect] {
        isVehicleActive = true
        vehicleActiveSince = vehicleActiveSince ?? date
        driving?.noteVehicleEvidence(at: date)
        let isNewerEvidence = (checkpoint.lastAutomotiveAt ?? .distantPast) < date
        checkpoint.lastAutomotiveAt = max(checkpoint.lastAutomotiveAt ?? date, date)

        switch checkpoint.state {
        case .idle:
            return openDrivingCandidate(vehicleEvidenceAt: date, now: now)
        case .parkingTransition:
            // §3a `PARKING_TRANSITION → DRIVING`: the red light is over. Restored as
            // `DRIVING` and not `DRIVING_CANDIDATE` — this session was already confirmed
            // before it stopped, and making it re-earn confirmation would let a car in
            // stop-start traffic never reach a parking transition at all.
            return resumeDrivingFromTransition(vehicleEvidenceAt: date, reanchorIdleAt: now, now: now)
        case .candidatePending:
            // §3a "Leaving a pending candidate behind": a new journey starts while a
            // prompt is still unanswered. Without this the engine sat here for up to
            // forty-five minutes with detection dead.
            //
            // The candidate is deliberately *not* retired. `vehicle_enter` is a noisy
            // signal — a bus passing, a passenger seat, the OS guessing — and retiring on
            // it would delete the answer to a question the user is still holding. It is
            // superseded where §10a puts it: when this new session actually produces a
            // candidate of its own.
            //
            // Except inside a stop-only candidate's resume window (§3a, 2026-09-27): there
            // the vehicle never ended, and its evidence returning is the long light ending —
            // the same row `PARKING_TRANSITION` has.
            if candidateResume != nil {
                return resumeFromStopOnlyCandidate(vehicleEvidenceAt: date, now: now)
            }
            return openDrivingCandidate(vehicleEvidenceAt: date, now: now)
        case .parked:
            // §11: getting back in. The session opens here so the bars have something to
            // measure, and the state does not move until they are cleared — `PARKED` is
            // where a phone that merely woke up in a parked car has to stay.
            if driving == nil {
                driving = DrivingEvidence(startedAt: date, lastVehicleEvidenceAt: date)
                checkpoint.travelDistanceEstimate = 0
                return [.startBoundedLocationCapture, persistedCheckpoint()]
            }
            return isNewerEvidence ? [persistedCheckpoint()] : []
        case .drivingCandidate, .driving, .departureCandidate:
            // No row moves here, but the freshest vehicle observation is still worth
            // remembering: it is the anchor a relaunch replays motion history from.
            return isNewerEvidence ? [persistedCheckpoint()] : []
        }
    }

    /// §11: the engine was watching a possible departure and the vehicle ended first. The
    /// parking was never left, so nothing is closed and nothing is said.
    private func abandonDeparture(now: Date) -> [DetectionEffect] {
        driving = nil
        return [.stopLocationCapture] + moveTo(.parked, now: now)
    }

    private func handleVehicleExit(now: Date) -> [DetectionEffect] {
        isVehicleActive = false
        vehicleActiveSince = nil

        switch checkpoint.state {
        case .drivingCandidate:
            // §3a `DRIVING_CANDIDATE → IDLE`. §12's short-trip guard: a ride that never
            // cleared the 90 s bar produces no candidate at all, rather than a
            // low-confidence one the user has to dismiss.
            driving = nil
            return [.sessionEnded(reason: .vehicleExit, at: now), .stopLocationCapture]
                + moveTo(.idle, now: now)
        case .driving:
            return endDrivingSession(reason: .vehicleExit, now: now)
        case .departureCandidate:
            return abandonDeparture(now: now)
        case .parked:
            // Got in, got out again. §11 never moved, so there is nothing to undo beyond
            // releasing the capture this session opened.
            guard driving != nil else { return [] }
            driving = nil
            return [.stopLocationCapture, persistedCheckpoint()]
        case .parkingTransition:
            // docs/05 §8b: an explicit exit that arrives while a `movementIdle` transition
            // is open is still this drive's exit, and earns `vehicle_exit_detected`. It is
            // not a confirming signal — §3a names walking, stationary and a location stop.
            transition?.evidence.vehicleExitDetected = true
            return []
        case .candidatePending:
            // An explicit exit is the vehicle ending after all: movement after it is
            // somebody else's journey, not this drive resuming (§3a).
            return closeCandidateResume()
        case .idle:
            return []
        }
    }

    /// §3a `PARKING_TRANSITION → CANDIDATE_PENDING`: "any of `walking_enter`,
    /// `stationary_enter`, location stop — within `transitionWindow`".
    ///
    /// Only signals observed **at or after** the transition was entered count. A walk
    /// recorded before the drive ended is not evidence that this drive ended in a parking,
    /// and taking it would let the signal that caused the transition also end it.
    private func noteConfirmationSignal(walking: Bool, now: Date) -> [DetectionEffect] {
        if walking, checkpoint.state == .candidatePending {
            // §3a rule 4 (round 3): the person has left the car, so vehicle evidence after
            // this is a bus or a lift, not the jam moving on. The candidate stands. Stillness
            // does not close it — the Transition API reports STILL inside a car at a light.
            return closeCandidateResume()
        }
        guard var current = transition, checkpoint.state == .parkingTransition else { return [] }
        if walking {
            current.evidence.walkingAfterVehicle = true
        } else {
            current.evidence.stationaryAfterVehicle = true
        }
        transition = current
        return createCandidate(from: current, now: now)
    }

    /// §3a "The car link", row 1: `IDLE → DRIVING_CANDIDATE`, and row 3:
    /// `CANDIDATE_PENDING → DRIVING`.
    ///
    /// Connecting deliberately does **not** promote to `DRIVING`. People sit in parked
    /// cars, and the link is not vehicle activity — the 90-second sustain still has to be
    /// earned by motion, so getting in and changing your mind runs the
    /// `drivingCandidateWindow` out and produces nothing.
    private func handleCarLinkConnected(kind: CarLinkKind, now: Date) -> [DetectionEffect] {
        connectedCarLinks.insert(kind)
        switch checkpoint.state {
        case .idle:
            return openDrivingCandidate(vehicleEvidenceAt: nil, now: now)
        case .candidatePending:
            // The fuel stop (§17 fixture 3): disconnect, pump, get back in. The candidate
            // is retired and its notification withdrawn before it is worth anything, and
            // the trip can still produce the real parking later — as the same trip, so
            // its drive is read before the retirement clears it.
            let drive = candidateDrive
            let captureRunning = candidateResume != nil
            return retirePendingCandidate(now: now)
                + resumeDrivingFromCandidate(drive, captureRunning: captureRunning, now: now)
        case .parkingTransition:
            // docs/05 §3a car-link table (2026-09-27): the phone rejoining the car inside
            // the window is the red light ending, stated by the strongest signal there is.
            return resumeDrivingFromTransition(vehicleEvidenceAt: nil, reanchorIdleAt: now, now: now)
        case .parked:
            // §11b, and the mirror of the disconnect row below: the phone rejoining the car
            // is the strongest departure signal there is, and waiting for 500 m of GPS to
            // say the same thing is waiting for evidence that is already in.
            //
            // It opens the candidate and nothing more. `DEPARTURE_CANDIDATE` shows nothing
            // and ends nothing until §7's guard is satisfied, so sitting in a parked car
            // with the radio on costs the record nothing — the evidence goes stale and the
            // machine returns to `PARKED`.
            return openDepartureFromCarLink(now: now)
        case .drivingCandidate, .driving, .departureCandidate:
            return []
        }
    }

    /// §11b. Opens a departure on the link, reusing whatever session `PARKED` already had.
    ///
    /// The session is the one the fold's `vehicle_enter` would have opened; there may be
    /// none yet, in which case the link itself is the first vehicle evidence this departure
    /// has and the evidence starts here.
    private func openDepartureFromCarLink(now: Date) -> [DetectionEffect] {
        if driving == nil {
            driving = DrivingEvidence(startedAt: now, lastVehicleEvidenceAt: now)
        }
        return moveTo(.departureCandidate, now: now)
    }

    /// §3a "The car link", row 2: `DRIVING → CANDIDATE_PENDING`, skipping
    /// `PARKING_TRANSITION`.
    ///
    /// Waiting for a walk would lose exactly the case §3a was corrected for — an
    /// underground car park where no walk is ever detected — and the link has already told
    /// us the engine stopped and the phone left the car.
    private func handleCarLinkDisconnected(kind: CarLinkKind, now: Date) -> [DetectionEffect] {
        connectedCarLinks.remove(kind)
        switch checkpoint.state {
        case .driving:
            return openCandidateOnDisconnect(now: now)
        case .drivingCandidate:
            // docs/05 §3a car-link table (2026-09-27): the driver got in and changed their
            // mind. Same outcome as `vehicle_exit` in this state — §12's short-trip guard,
            // nothing produced.
            isVehicleActive = false
            vehicleActiveSince = nil
            driving = nil
            return [.sessionEnded(reason: .carLinkDisconnected, at: now), .stopLocationCapture]
                + moveTo(.idle, now: now)
        case .parkingTransition:
            // docs/05 §3a car-link table (2026-09-27): the disconnect is §6's fourth
            // confirming signal, and — as in `DRIVING` — it is also the vehicle's end.
            guard var current = transition else { return [] }
            current.evidence.carProjectionDisconnected = true
            current.evidence.vehicleExitDetected = true
            transition = current
            return createCandidate(from: current, now: now)
        case .idle, .candidatePending, .parked, .departureCandidate:
            return []
        }
    }

    /// §3a "The car link", row 2: `DRIVING → CANDIDATE_PENDING`, skipping
    /// `PARKING_TRANSITION`.
    ///
    /// Waiting for a walk would lose exactly the case §3a was corrected for — an
    /// underground car park where no walk is ever detected — and the link has already told
    /// us the engine stopped and the phone left the car.
    private func openCandidateOnDisconnect(now: Date) -> [DetectionEffect] {
        // The link is a vehicle signal, so its loss ends the vehicle session as surely as
        // a Core Motion exit does.
        isVehicleActive = false
        vehicleActiveSince = nil

        let drive = driving ?? DrivingEvidence(startedAt: checkpoint.stateEnteredAt)
        driving = nil
        var effects: [DetectionEffect] = [
            .sessionEnded(reason: .carLinkDisconnected, at: now),
            .stopLocationCapture
        ]
        var direct = ParkingTransition(
            enteredAt: now,
            entryReason: .carLinkDisconnected,
            drive: drive,
            driveDurationAtEnd: drive.duration(now: now),
            sessionStartedAt: drive.startedAt,
            vehicleExitDetected: true,
            isCapturing: false
        )
        direct.evidence.carProjectionDisconnected = true
        transition = direct
        effects += createCandidate(from: direct, now: now)
        // §12 already allowed this trip one candidate, so the rule refused a second. The
        // drive is over either way and the state may not stay on a session nobody owns.
        if checkpoint.state == .driving {
            transition = nil
            effects += moveTo(.idle, now: now)
        }
        return effects
    }

    private func resolveCandidate(confirmed: Bool, now: Date) -> [DetectionEffect] {
        guard checkpoint.state == .candidatePending else { return [] }
        let released = closeCandidateResume()
        candidateDrive = nil
        pendingCandidate = nil
        checkpoint.candidateId = nil
        hasProducedCandidateInSession = false
        return released + moveTo(confirmed ? .parked : .idle, now: now)
    }

    /// §3a `*any* → PARKED` (§11c): the user saved a parking themselves.
    ///
    /// The user has said where the car is, and nothing the engine was inferring outranks
    /// that — so whatever was in flight is dropped without a report. No `sessionEnded`, no
    /// candidate: the user just answered the question those were building toward. And no
    /// `endActiveParking` either, because the app's save flow has already closed any
    /// previous record, and emitting it here would close the one just written.
    ///
    /// A pending candidate is withdrawn the way expiry withdraws it (§10), not the way a
    /// rejection does: the user did not say "not parked", they said "parked, here". It is
    /// inlined rather than routed through `retirePendingCandidate`, which would detour the
    /// state through `IDLE` and write a checkpoint nobody needs.
    private func adoptUserSavedParking(now: Date) -> [DetectionEffect] {
        var effects: [DetectionEffect] = []
        if let candidate = pendingCandidate {
            pendingCandidate = nil
            effects.append(.withdrawCandidate(id: candidate.id))
        }
        // A bounded capture runs exactly while `driving` is set — including the one
        // `PARKED` opens on `vehicle_enter` to measure §11's bars — or while a transition
        // is still deciding.
        if driving != nil || transition?.isCapturing == true || candidateResume != nil {
            effects.append(.stopLocationCapture)
        }
        driving = nil
        transition = nil
        candidateDrive = nil
        candidateResume = nil
        // Vehicle activity is over: the next `vehicle_enter` opens a departure's evidence,
        // which §11's bars and §7's guard then have to earn from scratch.
        isVehicleActive = false
        vehicleActiveSince = nil
        hasProducedCandidateInSession = false
        return effects + moveTo(.parked, now: now)
    }

    // MARK: - Session lifecycle

    private func openDrivingCandidate(vehicleEvidenceAt: Date?, now: Date) -> [DetectionEffect] {
        transition = nil
        hasProducedCandidateInSession = false
        driving = DrivingEvidence(startedAt: now, lastVehicleEvidenceAt: vehicleEvidenceAt)
        checkpoint.lastAutomotiveAt = vehicleEvidenceAt ?? checkpoint.lastAutomotiveAt
        checkpoint.travelDistanceEstimate = 0
        return moveTo(.drivingCandidate, now: now) + [.startBoundedLocationCapture]
    }

    /// §3a `DRIVING_CANDIDATE → DRIVING`: vehicle activity sustained ≥ `minimumVehicleDuration`.
    ///
    /// **Movement evidence deliberately does not gate this.** §3a records why: the drive
    /// recorded on 2026-09-19 at 14:26 is the textbook signature and carries zero location
    /// events, and gated on movement it never leaves `DRIVING_CANDIDATE`. Underground car
    /// parks, tunnels and urban canyons are this product's main setting and are exactly
    /// where GPS Doppler speed never arrives. Movement belongs in the confidence bucket,
    /// not in the transition.
    private func promoteToDriving(now: Date) -> [DetectionEffect] {
        driving?.markConfirmed(at: now)
        checkpoint.travelDistanceEstimate = driving?.distanceMeters ?? checkpoint.travelDistanceEstimate
        return moveTo(.driving, now: now) + [.drivingConfirmed(at: now)]
    }

    /// Ends the bounded session and routes to whatever §3a says comes next.
    ///
    /// Also the entry point for the ends the *adapter* decides: a lost authorization, a
    /// capture failure, the Smart Detection opt-out, and — on iOS only — Core Motion going
    /// silent. Those describe the app losing the drive rather than the drive ending, and
    /// `entersParkingTransition` keeps them out of the candidate path.
    func endDrivingSession(reason: DrivingSessionEndReason, now: Date) -> [DetectionEffect] {
        if checkpoint.state == .parkingTransition {
            return endInsideTransition(reason: reason, now: now)
        }
        if checkpoint.state == .candidatePending {
            // Every adapter-decided end — a derived exit, a lost capture, the opt-out —
            // closes a stop-only candidate's resume window. The candidate itself stays.
            return closeCandidateResume()
        }
        guard driving != nil || checkpoint.state == .drivingCandidate || checkpoint.state == .driving else {
            return []
        }
        let drive = driving
        let wasConfirmed = drive?.isConfirmed ?? (checkpoint.state == .driving)
        driving = nil
        // `movementIdle` is not an exit (docs/05 §8b): the vehicle *level* is still on, so a
        // later walk can still be derived as this drive's exit, and a later `vehicle_enter`
        // is the red light ending rather than a new trip.
        if reason != .vehicleExit, reason != .movementIdle {
            isVehicleActive = false
            vehicleActiveSince = nil
        }

        var effects: [DetectionEffect] = [.sessionEnded(reason: reason, at: now)]
        checkpoint.travelDistanceEstimate = drive?.distanceMeters ?? checkpoint.travelDistanceEstimate

        guard entersParkingTransition(reason: reason, wasConfirmed: wasConfirmed) else {
            isVehicleActive = false
            vehicleActiveSince = nil
            return effects + [.stopLocationCapture] + moveTo(.idle, now: now)
        }
        // docs/05 §3a / §19 (2026-09-27): the capture is **not** stopped here. Two of this
        // state's three exits are location rows — a location stop confirms, returning
        // movement resumes — and with the capture stopped at the entry neither could ever
        // fire on a device. It stops when the transition decides.
        let finished = drive ?? DrivingEvidence(
            startedAt: checkpoint.stateEnteredAt,
            lastVehicleEvidenceAt: checkpoint.lastAutomotiveAt
        )
        transition = ParkingTransition(
            enteredAt: now,
            entryReason: reason,
            drive: finished,
            driveDurationAtEnd: drive.map { $0.duration(now: now) },
            sessionStartedAt: drive?.startedAt,
            vehicleExitDetected: ParkingTransition.isExplicitExit(reason),
            isCapturing: true
        )
        effects += moveTo(.parkingTransition, now: now)
        return effects
    }

    /// An adapter-decided end that lands while `PARKING_TRANSITION` is already deciding.
    ///
    /// The drive is over, so none of these ends it again. What each one *means* still
    /// applies: a derived exit is this drive's exit (§8b), a lost capture stops the capture
    /// but leaves motion free to confirm, and the opt-out drops the inference outright.
    private func endInsideTransition(reason: DrivingSessionEndReason, now: Date) -> [DetectionEffect] {
        guard var current = transition else { return [] }
        switch reason {
        case .walkingDetected, .vehicleEvidenceExpired, .vehicleExit:
            isVehicleActive = false
            vehicleActiveSince = nil
            current.evidence.vehicleExitDetected = true
            transition = current
            return []
        case .authorizationLost, .captureFailed:
            guard current.isCapturing else { return [] }
            current.isCapturing = false
            transition = current
            return [.stopLocationCapture]
        case .smartDetectionDisabled, .fieldTestStopped, .maximumDurationReached:
            return [.sessionEnded(reason: reason, at: now)] + closeTransition() + moveTo(.idle, now: now)
        case .movementIdle, .carLinkDisconnected:
            return []
        }
    }

    /// Leaves `PARKING_TRANSITION` by any door except a resume: the capture it kept is
    /// released and the vehicle level ends with the trip. The caller moves the state.
    private func closeTransition(keepingCapture: Bool = false) -> [DetectionEffect] {
        let wasCapturing = transition?.isCapturing == true
        transition = nil
        isVehicleActive = false
        vehicleActiveSince = nil
        return wasCapturing && !keepingCapture ? [.stopLocationCapture] : []
    }

    /// Ends a stop-only candidate's resume window, releasing the capture it kept. The
    /// candidate is untouched.
    private func closeCandidateResume() -> [DetectionEffect] {
        guard candidateResume != nil else { return [] }
        candidateResume = nil
        return [.stopLocationCapture]
    }

    /// §3a. Only a **confirmed** `DRIVING` session goes on to decide whether it parked;
    /// `DRIVING_CANDIDATE` leaves straight for `IDLE`.
    private func entersParkingTransition(reason: DrivingSessionEndReason, wasConfirmed: Bool) -> Bool {
        guard wasConfirmed else { return false }
        switch reason {
        case .vehicleExit, .walkingDetected, .vehicleEvidenceExpired, .movementIdle:
            return true
        case .carLinkDisconnected:
            // Handled by `handleCarLinkDisconnected`, which skips the transition entirely.
            return false
        case .maximumDurationReached, .authorizationLost, .captureFailed,
             .smartDetectionDisabled, .fieldTestStopped:
            return false
        }
    }

    /// §3a `PARKING_TRANSITION → DRIVING`, by returning movement, vehicle evidence or the
    /// car link.
    ///
    /// docs/05 §3a "Resuming keeps the drive" (2026-09-27): the drive that stopped at the
    /// light is the drive that continues. It keeps its start — so the 2-hour ceiling, the
    /// §8 duration and §5's inheritance bound all measure the trip — its distance, and its
    /// confirmation. iOS used to open a fresh drive here, which reset all three at every
    /// long light.
    ///
    /// `reanchorIdleAt` gives a resume on vehicle or link evidence a full
    /// `movementIdleWindow`; a resume on a moving fix is anchored by that fix already.
    private func resumeDrivingFromTransition(
        vehicleEvidenceAt: Date?,
        reanchorIdleAt: Date?,
        now: Date
    ) -> [DetectionEffect] {
        guard let current = transition else { return [] }
        transition = nil
        var drive = current.drive
        if let vehicleEvidenceAt {
            drive.noteVehicleEvidence(at: vehicleEvidenceAt)
        }
        if let reanchorIdleAt {
            drive.reanchorMovementIdle(at: reanchorIdleAt)
        }
        drive.markConfirmed(at: now)
        driving = drive
        var effects = moveTo(.driving, now: now)
        if !current.isCapturing {
            effects.append(.startBoundedLocationCapture)
        }
        return effects
    }

    private func resumeDrivingFromCandidate(
        _ drive: DrivingEvidence?,
        captureRunning: Bool,
        now: Date
    ) -> [DetectionEffect] {
        var evidence = drive ?? DrivingEvidence(startedAt: now, lastVehicleEvidenceAt: now)
        evidence.noteVehicleEvidence(at: now)
        evidence.reanchorMovementIdle(at: now)
        evidence.markConfirmed(at: now)
        driving = evidence
        isVehicleActive = true
        vehicleActiveSince = now
        let capture: [DetectionEffect] = captureRunning ? [] : [.startBoundedLocationCapture]
        return moveTo(.driving, now: now) + capture + [.drivingConfirmed(at: now)]
    }

    /// docs/05 §3a "A stop-only candidate can still be a long light" (2026-09-27):
    /// `CANDIDATE_PENDING → DRIVING`, the stop-only twin of `PARKING_TRANSITION → DRIVING`.
    ///
    /// The candidate is withdrawn the way §10 withdraws an expired one — no record, no
    /// report — and the drive it came from continues as the same travel session, exactly as
    /// a resume from the transition keeps it. One checkpoint write, straight to `DRIVING`:
    /// the trip never passed through `IDLE`.
    ///
    /// On vehicle evidence the idle clock is re-anchored at the resume, as in the
    /// transition; a resume on a moving fix is anchored by that fix, already folded.
    private func resumeFromStopOnlyCandidate(vehicleEvidenceAt: Date?, now: Date) -> [DetectionEffect] {
        guard candidateResume != nil, var drive = candidateDrive else { return [] }
        var effects: [DetectionEffect] = []
        if let candidate = pendingCandidate {
            effects.append(.withdrawCandidate(id: candidate.id))
        }
        pendingCandidate = nil
        candidateResume = nil
        hasProducedCandidateInSession = false
        if let vehicleEvidenceAt {
            drive.noteVehicleEvidence(at: vehicleEvidenceAt)
            drive.reanchorMovementIdle(at: now)
        }
        drive.markConfirmed(at: now)
        driving = drive
        isVehicleActive = true
        vehicleActiveSince = vehicleActiveSince ?? now
        // The window holds the capture it kept (§3a), so the resumed drive already has one.
        return effects + moveTo(.driving, now: now)
    }

    // MARK: - Fixes

    /// Folds a fix into whichever drive is recording: the open one, or the one a
    /// `PARKING_TRANSITION` is still deciding about (§3a). Fixes outside both are ignored.
    ///
    /// §6's selection runs only on fixes a drive accepted — never on an outlier — and a fix
    /// inside the transition may update the spot: a car standing in a bay is exactly the
    /// point the user will look for.
    private func recordFix(_ fix: LocationFix, now: Date) -> [DetectionEffect] {
        let verdict: FixVerdict?
        if var evidence = driving {
            verdict = Self.fold(fix, into: &evidence)
            driving = evidence
        } else if var current = transition {
            verdict = Self.fold(fix, into: &current.drive)
            transition = current
        } else if candidateResume != nil, var resumable = candidateDrive {
            // §3a: the stop-only candidate's drive is still recording, so a resume keeps
            // its anchors and its distance. The reliable location is *not* updated: the
            // candidate's spot is the car, and the person walking away from it must not
            // drag the point a later candidate of this trip would inherit.
            lastFixVerdict = Self.fold(fix, into: &resumable)
            candidateDrive = resumable
            return []
        } else {
            return []
        }
        lastFixVerdict = verdict
        guard verdict != nil else { return [] }
        return updateReliableLocation(with: fix, now: now)
    }

    private static func fold(_ fix: LocationFix, into evidence: inout DrivingEvidence) -> FixVerdict? {
        let movingBefore = evidence.movingSampleCount
        guard evidence.record(fix: fix) else { return nil }
        let moved = evidence.movingSampleCount > movingBefore
        let reportsStop = fix.speed.map { $0 < DrivingConfirmationPolicy.movingSpeedThreshold } ?? false
        let reportsMoving = fix.speed.map { $0 >= DrivingConfirmationPolicy.movingSpeedThreshold } ?? false
        return FixVerdict(
            timestamp: fix.timestamp,
            moved: moved,
            reportedMoving: reportsMoving,
            stopped: !moved && reportsStop
        )
    }

    /// The two location rows of §3a's `PARKING_TRANSITION`, judged after the windows so a
    /// fix that lands past `transitionWindow` finds `IDLE` and decides nothing.
    ///
    /// * **Movement returns** — the fix cleared §7's movement bar, by speed or by the
    ///   distance fallback: the red light is over.
    /// * **Location stop** — the fix reported a speed below `movingSpeedThreshold`, at or
    ///   after the transition's entry (the rule every confirming signal shares). A fix
    ///   with no speed says nothing: underground there is no Doppler, and silence is not
    ///   stillness (§7).
    ///
    /// The two cannot both hold for one fix, so their order is immaterial.
    private func applyFixEdge(now: Date) -> [DetectionEffect] {
        if checkpoint.state == .candidatePending {
            return applyFixEdgeToStopOnlyCandidate(now: now)
        }
        guard checkpoint.state == .parkingTransition,
              var current = transition,
              let verdict = lastFixVerdict,
              verdict.timestamp >= current.enteredAt
        else { return [] }
        if verdict.moved {
            return resumeDrivingFromTransition(vehicleEvidenceAt: nil, reanchorIdleAt: nil, now: now)
        }
        guard verdict.stopped else { return [] }
        current.evidence.locationStopConfirmed = true
        transition = current
        return createCandidate(from: current, now: now)
    }

    /// §3a "A stop-only candidate can still be a long light": the second fix that *reported*
    /// moving speed, at or after the drive's end, inside the resume window. Judged after
    /// the windows, like the transition's rows, so a fix past the deadline decides nothing.
    private func applyFixEdgeToStopOnlyCandidate(now: Date) -> [DetectionEffect] {
        guard var hold = candidateResume,
              let verdict = lastFixVerdict,
              verdict.reportedMoving,
              verdict.timestamp >= hold.driveEndedAt
        else { return [] }
        hold.reportedMovingFixes += 1
        candidateResume = hold
        guard hold.reportedMovingFixes >= MovementEvidencePolicy.minimumMovingSamples else { return [] }
        return resumeFromStopOnlyCandidate(vehicleEvidenceAt: nil, now: now)
    }

    private func updateReliableLocation(with fix: LocationFix, now: Date) -> [DetectionEffect] {
        let incumbent = checkpoint.lastReliableLocation
        switch ReliableLocationPolicy.evaluate(candidate: fix, incumbent: incumbent, now: now) {
        case let .rejected(reason):
            reliableLocationRejectCount += 1
            lastReliableLocationRejection = reason
            return []
        case let .accepted(selected):
            reliableLocationUpdateCount += 1
            lastReliableLocationRejection = nil
            return acceptReliableLocation(selected, incumbent: incumbent)
        }
    }

    private func acceptReliableLocation(
        _ selected: LastReliableLocation,
        incumbent: LastReliableLocation?
    ) -> [DetectionEffect] {
        checkpoint.lastReliableLocation = selected
        checkpoint.lastLocationAt = selected.capturedAt
        checkpoint.travelDistanceEstimate = driving?.distanceMeters ?? checkpoint.travelDistanceEstimate

        // docs/05 §14 writes on a *materially* better fix. Holding the improved value in
        // memory either way means a write we skipped is never a value we lost: the session
        // end persists whatever the last accepted fix was.
        guard ReliableLocationPolicy.isMateriallyBetter(selected, than: incumbent) else { return [] }
        return [persistedCheckpoint()]
    }

    /// A significant change arrived. Not a §3a row — it only moves the freshness anchor the
    /// motion replay window is measured from.
    func noteSignificantChange(at date: Date) -> [DetectionEffect] {
        guard date > (checkpoint.lastLocationAt ?? .distantPast) else { return [] }
        checkpoint.lastLocationAt = date
        return [persistedCheckpoint()]
    }

    // MARK: - Candidates (§10a)

    /// §3a `PARKING_TRANSITION → CANDIDATE_PENDING`, and §10a's posting rules.
    ///
    /// The effect order is deliberate and is what the permission-denied path rests on: the
    /// candidate is written first, then the checkpoint, then the notification. Denied
    /// notifications, a notification service that throws, a process killed a millisecond
    /// later — none of them can cost the user the candidate, which is what §10a means by
    /// §5 "The fix a candidate inherits must belong to the drive that just ended".
    ///
    /// Both conditions, because they catch different things. The session bound refuses a
    /// fix from a previous trip or from the origin of this one — the origin is not the
    /// destination. The age bound refuses a long drive's only good fix when it came near
    /// the start, because being on the motorway at minute two says nothing about where the
    /// car stopped at minute ninety.
    ///
    /// Measured on Android on 2026-09-20: without this, a candidate created at 17:32
    /// carried a fix from 12:00, and the confirmation screen would have drawn it on a map
    /// with its accuracy printed beside it. A wrong coordinate is worse than none, and
    /// `위치 없음` is a state that screen already renders properly.
    private func inheritableLocation(sessionStartedAt: Date?, now: Date) -> LastReliableLocation? {
        guard let fix = checkpoint.lastReliableLocation else { return nil }
        guard now.timeIntervalSince(fix.capturedAt) <= ParkingCandidatePolicy.staleLocationWindow
        else { return nil }
        // No session start to bound it against — a transition rebuilt from a checkpoint —
        // and the age check above is the whole guard.
        guard let sessionStartedAt else { return fix }
        return fix.capturedAt >= sessionStartedAt ? fix : nil
    }

    /// "notification permission is not required for correctness".
    private func createCandidate(from transition: ParkingTransition, now: Date) -> [DetectionEffect] {
        let evidence = scoredEvidence(for: transition)
        guard evidence.confirmationSignals > 0 else { return [] }

        // §12 / §3a "One candidate per travel session". The trip has to pass through
        // `IDLE` first — and a confirming signal the rule refuses *is* that passage, as it
        // is on Android: staying in the transition would only wait for a window to say so.
        guard !hasProducedCandidateInSession else {
            return closeTransition() + moveTo(.idle, now: now)
        }

        let inherited = inheritableLocation(sessionStartedAt: transition.sessionStartedAt, now: now)
        let accuracyBucket = inherited
            .flatMap { LocationAccuracyBucket(horizontalAccuracy: $0.horizontalAccuracy) }
        guard let candidate = ParkingCandidatePolicy.evaluate(
            evidence,
            id: makeCandidateId(),
            detectedAt: now,
            lastReliableLocation: inherited,
            accuracyBucket: accuracyBucket
        ) else {
            return closeTransition() + [.candidateRuleUnmet] + moveTo(.idle, now: now)
        }

        // §3a "A stop-only candidate can still be a long light": when nothing but absence
        // ended the drive, the capture it kept outlives the transition until the drive's
        // own window runs out, so the car moving on can still take the candidate back.
        //
        // Only while that capture is still running: a transition that lost it opens no
        // window, so an open window always holds its capture — on Android the window's
        // existence is that bit (R4-B1).
        let resumable = transition.isCapturing && Self.isStopOnly(transition, evidence: evidence)
        var effects = closeTransition(keepingCapture: resumable)
        // §10a: a new travel session's candidate retires the older one first. A stale
        // prompt about a previous trip is worse than no prompt.
        if let outstanding = pendingCandidate {
            effects.append(.withdrawCandidate(id: outstanding.id))
        }
        pendingCandidate = candidate
        hasProducedCandidateInSession = true
        checkpoint.candidateId = candidate.id

        effects.append(.createCandidate(candidate))
        effects += moveTo(.candidatePending, now: now)
        candidateDrive = transition.drive
        candidateResume = resumable
            ? CandidateResume(
                driveEndedAt: transition.enteredAt,
                deadline: transition.enteredAt.addingTimeInterval(ParkingTransitionPolicy.transitionWindow)
            )
            : nil
        // §9: `low` posts nothing. The candidate above is already written, so the app still
        // shows it when opened.
        if candidate.isNotifiable {
            effects.append(.issueCandidateNotification(candidate))
        }
        return effects
    }

    /// docs/05 §3a: a candidate that nothing but absence produced — the transition was
    /// entered by `movementIdle`, and neither an exit (explicit, derived or a link
    /// disconnect) nor a walk arrived. The vehicle level never ended, so the car moving on
    /// is this drive continuing. A walk is excluded because it is the strongest evidence
    /// the person left the car: movement after it is more likely a different vehicle.
    private static func isStopOnly(_ transition: ParkingTransition, evidence: ParkingEvidence) -> Bool {
        transition.entryReason == .movementIdle
            && !evidence.vehicleExitDetected
            && !evidence.walkingAfterVehicle
            && !evidence.carProjectionDisconnected
    }

    /// docs/05 §8b: the transition's accumulated flags, plus the evidence that is judged
    /// against the drive's end — the moment this transition was entered.
    ///
    /// * `location_stopped`: the entry *was* `movementIdle`, or a reported stop came after
    ///   the last moving sample and no earlier than `nearEndHorizon` before the end. A red
    ///   light twenty minutes back is not how this drive ended.
    /// * `location_quality_degraded`: a fall into `poor` in that same horizon, or since.
    /// * duration and distance: frozen at the end (`ParkingTransition`).
    private func scoredEvidence(for transition: ParkingTransition) -> ParkingEvidence {
        var evidence = transition.evidence
        evidence.locationStopped = transition.entryReason == .movementIdle
            || transition.drive.stoppedNearEnd(endedAt: transition.enteredAt)
        evidence.gpsQualityDegraded = transition.drive.degradedNearEnd(endedAt: transition.enteredAt)
        evidence.driveDuration = transition.driveDurationAtEnd
        evidence.driveDistanceMeters = transition.driveDistanceAtEnd
        return evidence
    }

    private func retirePendingCandidate(now: Date) -> [DetectionEffect] {
        guard let candidate = pendingCandidate else { return [] }
        pendingCandidate = nil
        checkpoint.candidateId = nil
        hasProducedCandidateInSession = false
        var effects: [DetectionEffect] = [.withdrawCandidate(id: candidate.id)]
        if checkpoint.state == .candidatePending {
            effects += moveTo(.idle, now: now)
        }
        return effects
    }

    // MARK: - Checkpoint

    /// docs/05 §14: every state change is a checkpoint write.
    private func moveTo(_ state: DetectionState, now: Date) -> [DetectionEffect] {
        if state == .idle {
            // §3a "One candidate per travel session": the trip has to pass through `IDLE`
            // before it may produce another, and this is that passage.
            hasProducedCandidateInSession = false
        }
        if state != .candidatePending {
            checkpoint.candidateId = nil
            candidateDrive = nil
            candidateResume = nil
        }
        checkpoint.state = state
        checkpoint.stateEnteredAt = now
        return [persistedCheckpoint()]
    }

    private func persistedCheckpoint() -> DetectionEffect {
        checkpoint.revision += 1
        return .persistCheckpoint(checkpoint)
    }
}

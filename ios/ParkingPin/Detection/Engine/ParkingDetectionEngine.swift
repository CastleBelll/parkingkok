import Foundation

/// Everything the engine knows, projected for diagnostics and for the adapter.
///
/// A value, not a window into the actor: the coordinator reads it after every batch of
/// effects and copies what the §19 field checklist needs into `RehydrationSnapshot`.
struct DetectionEngineSnapshot: Sendable, Equatable {
    var checkpoint: DetectionCheckpoint
    var driving: DrivingEvidence?
    var parkingTransitionEnteredAt: Date?
    var isVehicleActive: Bool
    var connectedCarLinks: Set<CarLinkKind>
    var pendingCandidateId: UUID?
    /// §6 selection outcomes. Zero updates against a non-zero fix count is how a
    /// mis-set accuracy gate announces itself in the field.
    var reliableLocationUpdateCount: Int
    var reliableLocationRejectCount: Int
    var lastReliableLocationRejection: ReliableLocationRejection?
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
        /// The finished drive, kept so the candidate can still say how long and how far it
        /// ran and whether movement had stopped by the end.
        let drive: DrivingEvidence?
        var evidence: ParkingEvidence
    }

    // MARK: - Lifecycle

    func snapshot() -> DetectionEngineSnapshot {
        DetectionEngineSnapshot(
            checkpoint: checkpoint,
            driving: driving,
            parkingTransitionEnteredAt: transition?.enteredAt,
            isVehicleActive: isVehicleActive,
            connectedCarLinks: connectedCarLinks,
            pendingCandidateId: checkpoint.candidateId,
            reliableLocationUpdateCount: reliableLocationUpdateCount,
            reliableLocationRejectCount: reliableLocationRejectCount,
            lastReliableLocationRejection: lastReliableLocationRejection
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
        // The evidence is thinner than the original — `driveDuration` is not a checkpoint
        // field and is gone — and it says so by leaving the value `nil` rather than
        // guessing one. An absent duration costs a reason code and an analytics bucket; a
        // fabricated one would cost the meaning of both.
        transition = ParkingTransition(
            enteredAt: checkpoint.stateEnteredAt,
            entryReason: .vehicleEvidenceExpired,
            drive: nil,
            evidence: ParkingEvidence(
                hasMeaningfulVehicleSession: true,
                vehicleEnded: true,
                reliableLocationCaptured: checkpoint.lastReliableLocation != nil,
                driveDistanceMeters: checkpoint.travelDistanceEstimate
            )
        )
        return []
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
            return recordDrivingFix(fix, now: now)
        case let .locationQualityDegraded(_, _, to):
            // §8 "GPS quality degraded near end", §13's underground pattern. Supporting
            // evidence only: §6 will not let it satisfy the candidate rule alone.
            if to == nil || to == .poor {
                transition?.evidence.gpsQualityDegraded = true
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
            return adoptUserSavedParking(now: now)
        // §3a has no row for these: `stationary_exit` inside a drive is a car leaving a
        // light, a fix is evidence, a degradation is supporting evidence, and a tick is
        // only an invitation to re-examine the windows.
        case .stationaryExit, .location, .locationQualityDegraded, .timerTick:
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
        var effects: [DetectionEffect] = []
        for _ in 0 ..< Self.maximumTickCascade {
            let step = tickOnce(now: now)
            if step.isEmpty { break }
            effects += step
        }
        return effects
    }

    /// Enough to walk `DRIVING_CANDIDATE → DRIVING → PARKING_TRANSITION → IDLE` and stop.
    private static let maximumTickCascade = 6

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
            if now.timeIntervalSince(checkpoint.stateEnteredAt) >= DrivingConfirmationPolicy.drivingCandidateWindow {
                driving = nil
                return [.sessionEnded(reason: .vehicleEvidenceExpired, at: now), .stopLocationCapture]
                    + moveTo(.idle, now: now)
            }
            return []

        case .driving:
            guard let evidence = driving,
                  let reason = DrivingSessionTimeoutPolicy.boundReason(for: evidence, now: now)
            else { return [] }
            // The link outranks an inference from absence, but only this one: the 2-hour
            // ceiling is a bound on the session itself and holds either way.
            if carLinkHolds, reason == .movementIdle { return [] }
            return endDrivingSession(reason: reason, now: now)

        case .parkingTransition:
            guard let transition,
                  ParkingTransitionPolicy.hasElapsed(enteredAt: transition.enteredAt, now: now)
            else { return [] }
            // §3a `PARKING_TRANSITION → IDLE`. Silent by contract: nothing was persisted on
            // the way in beyond the checkpoint and nothing was shown, so there is nothing
            // to take back.
            self.transition = nil
            return [.candidateRuleUnmet] + moveTo(.idle, now: now)

        case .candidatePending:
            guard let candidate = pendingCandidate else {
                // The checkpoint says a candidate is pending and its file is gone. §10's
                // 45 minutes still bound the state, and leaving it here forever would
                // make every later drive invisible — so the bound is applied to the state
                // rather than to a candidate nobody can read.
                guard now.timeIntervalSince(checkpoint.stateEnteredAt) >= ParkingCandidatePolicy.expiry
                else { return [] }
                return moveTo(.idle, now: now)
            }
            guard candidate.isExpired(now: now) else { return [] }
            // docs/05 §10: no record is created and nothing is reported — docs/17 §2 has
            // no event for a guess that went unanswered.
            return retirePendingCandidate(now: now)

        case .departureCandidate:
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
            guard now.timeIntervalSince(evidence.lastVehicleEvidenceAt ?? evidence.startedAt)
                >= DrivingConfirmationPolicy.drivingCandidateWindow
            else { return [] }
            driving = nil
            return [.stopLocationCapture] + moveTo(.parked, now: now)

        case .parked:
            guard let evidence = driving, departureBarsCleared(evidence, now: now) else { return [] }
            return moveTo(.departureCandidate, now: now)

        case .idle:
            return []
        }
    }

    /// §11 `DEPARTURE_CANDIDATE → DRIVING`, and the one effect that closes the parking.
    ///
    /// The record ends when the car pulled away — `checkpoint.stateEnteredAt` is when §11's
    /// two bars were first cleared — not now, which is however long §7's guard took to be
    /// satisfied afterwards.
    private func confirmDeparture(now: Date) -> [DetectionEffect] {
        let departedAt = checkpoint.stateEnteredAt
        hasProducedCandidateInSession = false
        return [.endActiveParking(at: departedAt)] + moveTo(.driving, now: now)
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
            transition = nil
            return resumeDrivingFromTransition(vehicleEvidenceAt: date, now: now)
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
        case .idle, .parkingTransition, .candidatePending:
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
            // the trip can still produce the real parking later.
            return retirePendingCandidate(now: now) + resumeDrivingFromCandidate(now: now)
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
        case .drivingCandidate, .driving, .parkingTransition, .departureCandidate:
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
        guard checkpoint.state == .driving else { return [] }
        // The link is a vehicle signal, so its loss ends the vehicle session as surely as
        // a Core Motion exit does.
        isVehicleActive = false
        vehicleActiveSince = nil

        let drive = driving
        driving = nil
        var effects: [DetectionEffect] = [
            .sessionEnded(reason: .carLinkDisconnected, at: now),
            .stopLocationCapture
        ]
        let direct = ParkingTransition(
            enteredAt: now,
            entryReason: .carLinkDisconnected,
            drive: drive,
            evidence: parkingEvidence(for: drive, carLinkDisconnected: true, now: now)
        )
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
        pendingCandidate = nil
        checkpoint.candidateId = nil
        hasProducedCandidateInSession = false
        return moveTo(confirmed ? .parked : .idle, now: now)
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
        // A bounded session is open exactly while `driving` is set — including the one
        // `PARKED` opens on `vehicle_enter` to measure §11's bars.
        if driving != nil {
            effects.append(.stopLocationCapture)
        }
        driving = nil
        transition = nil
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
        guard driving != nil || checkpoint.state == .drivingCandidate || checkpoint.state == .driving else {
            return []
        }
        let drive = driving
        let wasConfirmed = drive?.isConfirmed ?? (checkpoint.state == .driving)
        driving = nil
        if reason != .vehicleExit {
            isVehicleActive = false
            vehicleActiveSince = nil
        }

        var effects: [DetectionEffect] = [
            .sessionEnded(reason: reason, at: now),
            .stopLocationCapture
        ]
        checkpoint.travelDistanceEstimate = drive?.distanceMeters ?? checkpoint.travelDistanceEstimate

        guard entersParkingTransition(reason: reason, wasConfirmed: wasConfirmed) else {
            return effects + moveTo(.idle, now: now)
        }
        transition = ParkingTransition(
            enteredAt: now,
            entryReason: reason,
            drive: drive,
            evidence: parkingEvidence(for: drive, carLinkDisconnected: false, now: now)
        )
        effects += moveTo(.parkingTransition, now: now)
        return effects
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

    private func resumeDrivingFromTransition(vehicleEvidenceAt: Date, now: Date) -> [DetectionEffect] {
        transition = nil
        var evidence = DrivingEvidence(startedAt: now, lastVehicleEvidenceAt: vehicleEvidenceAt)
        evidence.markConfirmed(at: now)
        driving = evidence
        return moveTo(.driving, now: now) + [.startBoundedLocationCapture, .drivingConfirmed(at: now)]
    }

    private func resumeDrivingFromCandidate(now: Date) -> [DetectionEffect] {
        var evidence = DrivingEvidence(startedAt: now, lastVehicleEvidenceAt: now)
        evidence.markConfirmed(at: now)
        driving = evidence
        isVehicleActive = true
        vehicleActiveSince = now
        return moveTo(.driving, now: now) + [.startBoundedLocationCapture, .drivingConfirmed(at: now)]
    }

    // MARK: - Fixes

    private func recordDrivingFix(_ fix: LocationFix, now: Date) -> [DetectionEffect] {
        guard var evidence = driving else { return [] }
        let accepted = evidence.record(fix: fix)
        driving = evidence
        guard accepted else { return [] }
        return updateReliableLocation(with: fix, now: now)
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
    private func inheritableLocation(for drive: DrivingEvidence?, now: Date) -> LastReliableLocation? {
        guard let fix = checkpoint.lastReliableLocation else { return nil }
        guard now.timeIntervalSince(fix.capturedAt) <= ParkingCandidatePolicy.staleLocationWindow
        else { return nil }
        // No drive on the transition means nothing to bound it against; the age check above
        // is then the whole guard.
        guard let drive else { return fix }
        return fix.capturedAt >= drive.startedAt ? fix : nil
    }

    /// "notification permission is not required for correctness".
    private func createCandidate(from transition: ParkingTransition, now: Date) -> [DetectionEffect] {
        var evidence = transition.evidence
        // §8 "location movement stopped", folded in here rather than at entry. It still
        // earns its weight and its reason code; what it may not do is be the only thing
        // that ended the transition, or `DRIVING → PARKING_TRANSITION → DRIVING` — the red
        // light §3a exists to describe — could never happen.
        evidence.locationStopped = transition.entryReason == .movementIdle
            || Self.movementHasStopped(in: transition.drive)

        guard evidence.confirmationSignals > 0 else { return [] }

        // §12 / §3a "One candidate per travel session". The trip has to pass through
        // `IDLE` first.
        guard !hasProducedCandidateInSession else { return [] }

        let inherited = inheritableLocation(for: transition.drive, now: now)
        let accuracyBucket = inherited
            .flatMap { LocationAccuracyBucket(horizontalAccuracy: $0.horizontalAccuracy) }
        guard let candidate = ParkingCandidatePolicy.evaluate(
            evidence,
            id: makeCandidateId(),
            detectedAt: now,
            lastReliableLocation: inherited,
            accuracyBucket: accuracyBucket
        ) else {
            self.transition = nil
            return [.candidateRuleUnmet] + moveTo(.idle, now: now)
        }

        self.transition = nil
        var effects: [DetectionEffect] = []
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
        // §9: `low` posts nothing. The candidate above is already written, so the app still
        // shows it when opened.
        if candidate.isNotifiable {
            effects.append(.issueCandidateNotification(candidate))
        }
        return effects
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

    /// The §8 evidence a finished drive carries into the transition.
    private func parkingEvidence(
        for drive: DrivingEvidence?,
        carLinkDisconnected: Bool,
        now: Date
    ) -> ParkingEvidence {
        let endingBucket = drive?.lastFix
            .flatMap { LocationAccuracyBucket(horizontalAccuracy: $0.horizontalAccuracy) }
        return ParkingEvidence(
            hasMeaningfulVehicleSession: true,
            vehicleEnded: true,
            gpsQualityDegraded: endingBucket == .poor,
            carProjectionDisconnected: carLinkDisconnected,
            reliableLocationCaptured: checkpoint.lastReliableLocation != nil,
            driveDuration: drive.map { $0.duration(now: now) },
            driveDistanceMeters: drive?.distanceMeters
        )
    }

    /// §8's "location movement stopped", read off the drive that just ended.
    ///
    /// True when the session saw the device travelling and then stopped seeing it: the
    /// newest accepted fix did not count as movement. A session that never moved says
    /// nothing — there is no movement to have stopped — and a session whose last fix was
    /// still moving stopped for some other reason than the location stream noticing.
    private static func movementHasStopped(in drive: DrivingEvidence?) -> Bool {
        guard let drive,
              let lastMoving = drive.lastMovingSampleAt,
              let lastFix = drive.lastFix
        else { return false }
        return lastFix.timestamp > lastMoving
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

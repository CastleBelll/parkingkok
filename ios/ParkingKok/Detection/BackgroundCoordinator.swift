import Foundation

/// Why the process is running.
enum LaunchReason: String, Sendable, Equatable {
    /// The user tapped the icon, or the app was already alive.
    case userInitiated
    /// Core Location relaunched us for a significant change
    /// (`UIApplication.LaunchOptionsKey.location`).
    case significantLocationChange
}

/// Everything the rehydration and bounded-session paths learned, in a form the
/// diagnostics export can render.
///
/// Carries no coordinate by construction: `LocationQualitySample` never had one, and the
/// bounded session's `LocationFix` is reduced to accuracy and timestamps before anything
/// lands here.
struct RehydrationSnapshot: Sendable, Equatable {
    var launchReason: LaunchReason = .userInitiated
    var rehydratedAt: Date?
    /// What the *load* said. Never overwritten by a later save, so a corrupt file stays
    /// visible for the whole session instead of being papered over by the next write.
    var checkpointLoad: DetectionCheckpointLoadResult = .absent
    /// What we hold right now, restored or freshly seeded.
    var currentCheckpoint: DetectionCheckpoint?
    var checkpointAge: TimeInterval?
    var isBeyondMotionRetention = false
    var motionWindow: MotionHistoryWindow?
    var motionSamples: [MotionSample] = []
    var motionFailure: String?
    var locationFailure: String?
    var significantChangeCount = 0
    var lastLocationAt: Date?
    var lastLocationAccuracy: Double?
    /// Cached fixes rejected as too old to be live evidence.
    var staleLocationDropCount = 0
    var lastStaleLocationAge: TimeInterval?
    var lastPersistError: String?

    // ── Bounded driving session (M0A-2) ──────────────────────────────────────
    var isCapturingDrivingLocation = false
    var drivingSessionStartedAt: Date?
    /// Sessions opened since launch. A count that climbs without any confirmation is the
    /// signature of a threshold that opens sessions too eagerly.
    var drivingSessionCount = 0
    var drivingSessionResumedFromCheckpoint = false
    var drivingConfirmedAt: Date?
    var lastDrivingSessionEndReason: DrivingSessionEndReason?
    var lastDrivingSessionEndedAt: Date?
    var drivingFixCount = 0
    var drivingMovingSampleCount = 0
    var drivingOutlierDropCount = 0
    var drivingDistanceMeters: Double = 0
    /// Accepted `lastReliableLocation` updates. Zero with a non-zero fix count means the
    /// §6 gate is set wrong for this device.
    var reliableLocationUpdateCount = 0
    var reliableLocationRejectCount = 0
    var lastReliableLocationRejection: ReliableLocationRejection?
    var lastVehicleEvidenceAt: Date?
    var lastVehicleEvidenceConfidence: MotionConfidence?
    var captureFailure: String?
}

/// Owns the detection state that outlives any one screen or callback.
///
/// An `actor` because the checkpoint is shared mutable state touched from a background
/// relaunch, a delegate callback, and the UI (docs/03_SYSTEM_ARCHITECTURE.md §4).
///
/// Scope note: this coordinates rehydration and the bounded driving session. Candidate
/// creation, notifications, confidence scoring and the full state machine are M3.
actor BackgroundCoordinator {
    private let checkpointStore: any DetectionCheckpointStoring
    private let motionHistory: any MotionHistoryProviding
    private let locationCapture: (any BoundedLocationCapturing)?
    private let dateProvider: any DateProviding

    private var checkpoint: DetectionCheckpoint?
    private var snapshot = RehydrationSnapshot()
    /// Non-nil exactly while a bounded session is open.
    private var drivingEvidence: DrivingEvidence?
    /// Newest vehicle observation that has already opened a session. Motion history is
    /// replayed on every wake, so without this the same `automotive` sample would reopen
    /// a session the walking transition had just closed.
    private var consumedVehicleEvidenceAt: Date?

    init(
        checkpointStore: any DetectionCheckpointStoring,
        motionHistory: any MotionHistoryProviding,
        locationCapture: (any BoundedLocationCapturing)? = nil,
        dateProvider: any DateProviding = SystemDateProvider()
    ) {
        self.checkpointStore = checkpointStore
        self.motionHistory = motionHistory
        self.locationCapture = locationCapture
        self.dateProvider = dateProvider
    }

    func currentSnapshot() -> RehydrationSnapshot {
        snapshot
    }

    var isDrivingSessionOpen: Bool {
        drivingEvidence != nil
    }

    // MARK: - Rehydration

    /// The rehydration path from docs/04_IOS_IMPLEMENTATION.md §6.
    ///
    /// 1. read checkpoint  2. validate age  3. query motion history from
    /// `max(checkpoint.time, now - 30m)`  4. feed the engine  5. start/stop live location.
    ///
    /// Never throws: a failure at any step degrades to a recorded reason, because the
    /// app must keep working with manual parking (CLAUDE.md Hard Constraints). No network
    /// request happens here — docs/04 §7 forbids it on the background path.
    func rehydrate(launchReason: LaunchReason) async {
        let now = dateProvider.now
        snapshot.launchReason = launchReason
        snapshot.rehydratedAt = now

        let load = checkpointStore.load()
        snapshot.checkpointLoad = load

        let restored = load.checkpoint
        checkpoint = restored
        snapshot.currentCheckpoint = restored

        switch load {
        case let .restored(loaded):
            snapshot.checkpointAge = loaded.age(now: now)
            snapshot.isBeyondMotionRetention = MotionHistoryWindowPolicy.isBeyondMotionRetention(
                checkpointDate: loaded.latestTimestamp,
                now: now
            )
            snapshot.lastLocationAt = loaded.lastLocationAt
        case .absent:
            // First run on this install: give process death something to restore.
            persist(DetectionCheckpoint.initial(at: now))
        case let .failed(failure):
            // Deliberately does *not* seed. Overwriting here would hide the damage and
            // make a real data-loss bug look like a fresh install.
            AppLog.detection.error("checkpoint load failed: \(failure.diagnosticDescription, privacy: .public)")
        }

        // Anchored on the *restored* checkpoint only. A checkpoint seeded a moment ago
        // would collapse the window to zero and skip the replay entirely.
        await reconstructMotionHistory(now: now, anchor: restored?.latestTimestamp)

        // docs/04 §3: "Sessions must be recreated on relevant background relaunch." A
        // checkpoint that says we were driving means the previous process owned a live
        // session that died with it; nothing else will ever reopen it.
        await resumeDrivingSessionIfInterrupted(now: now)
        await evaluateMotionEvidence(now: now)
    }

    /// A significant change arrived.
    ///
    /// Beyond recording the fix quality, this is the wake that has to decide whether a
    /// drive is under way: the app may have been dead for the whole trip so far, so the
    /// motion history is re-read here and not only at launch (docs/04 §6).
    func handleSignificantChange(_ sample: LocationQualitySample) async {
        let now = dateProvider.now

        // Core Location replays its cached fix when monitoring starts. Persisting that
        // as a live arrival walks lastLocationAt backwards and would surface an hours-old
        // point as the parking spot. Counted rather than dropped quietly: a rising count
        // with no fresh samples is the signal that the freshness bound is set wrong.
        guard LocationFreshnessPolicy.isFresh(sample, now: now) else {
            snapshot.staleLocationDropCount += 1
            snapshot.lastStaleLocationAge = now.timeIntervalSince(sample.timestamp)
            return
        }

        snapshot.significantChangeCount += 1
        snapshot.lastLocationAt = sample.timestamp
        snapshot.lastLocationAccuracy = sample.horizontalAccuracy

        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        updated.lastLocationAt = sample.timestamp
        updated.revision += 1
        persist(updated)

        await reconstructMotionHistory(now: now, anchor: checkpoint?.latestTimestamp)
        await evaluateMotionEvidence(now: now)
    }

    /// Core Location told us it could not produce a fix. Recorded, not escalated.
    func recordLocationFailure(_ description: String) {
        snapshot.locationFailure = description
    }

    /// Asking for motion permission *is* running a query, so this shares the
    /// reconstruction path rather than querying on the side: whatever comes back —
    /// samples or the reason it failed — lands in the snapshot and reaches the screen.
    ///
    /// Anchored on nothing on purpose. Anchoring on the live checkpoint would collapse
    /// the window to zero right after a seed, `samples(in:)` would return early without
    /// touching Core Motion, and the prompt would never appear.
    func requestMotionHistoryAccess() async {
        await reconstructMotionHistory(now: dateProvider.now, anchor: nil)
    }

    // MARK: - Bounded driving session

    /// One fix from the bounded session (docs/04 §3 DRIVING).
    ///
    /// Everything time-dependent is decided here rather than on a timer: at ~1 Hz the fix
    /// stream is itself the clock, and the watchdog in `evaluateDrivingTimeouts()` only
    /// has to cover the case where fixes stop arriving.
    func handleDrivingFix(_ fix: LocationFix) async {
        guard var evidence = drivingEvidence else { return }
        let now = dateProvider.now

        let accepted = evidence.record(fix: fix)
        drivingEvidence = evidence
        snapshot.drivingFixCount = evidence.fixCount
        snapshot.drivingMovingSampleCount = evidence.movingSampleCount
        snapshot.drivingOutlierDropCount = evidence.outlierCount
        snapshot.drivingDistanceMeters = evidence.distanceMeters

        if accepted {
            updateReliableLocation(with: fix, now: now)
        }

        confirmDrivingIfReady(now: now)
        await evaluateDrivingTimeouts(now: now)
    }

    /// Watchdog entry point for the case the fix stream goes silent — a tunnel, a denied
    /// authorization, a Core Location stall. Without it a session could stay open with no
    /// fixes to close it.
    func evaluateDrivingTimeouts() async {
        await evaluateDrivingTimeouts(now: dateProvider.now)
    }

    func handleCaptureAuthorizationLost() async {
        snapshot.captureFailure = "authorization denied for live updates"
        await endDrivingSession(reason: .authorizationLost, now: dateProvider.now)
    }

    func handleCaptureFailure(_ description: String) async {
        snapshot.captureFailure = description
        await endDrivingSession(reason: .captureFailed, now: dateProvider.now)
    }

    /// Smart Detection was switched off. The session must not outlive the opt-in.
    func stopDrivingSessionForOptOut() async {
        await endDrivingSession(reason: .smartDetectionDisabled, now: dateProvider.now)
    }

    #if PK_DEV
        /// Opens a bounded session without waiting for Core Motion.
        ///
        /// The Core Location half of this milestone — `CLServiceSession`,
        /// `CLBackgroundActivitySession`, `liveUpdates` delivery, the `UIBackgroundModes`
        /// entitlement — cannot be exercised in the simulator and otherwise cannot be
        /// exercised at all until someone drives a car. This lets the field-test checklist
        /// prove the plumbing works first, so a failed drive means a detection problem rather
        /// than a wiring problem.
        ///
        /// DEV configuration only: `PK_DEV` is defined in `Config/Dev.xcconfig` and in no
        /// other configuration, so nothing here is compiled into STAGING or PROD.
        func startDrivingSessionForFieldTest() async {
            guard drivingEvidence == nil else { return }
            let now = dateProvider.now
            await startDrivingSession(at: now, now: now)
        }

        func stopDrivingSessionForFieldTest() async {
            await endDrivingSession(reason: .fieldTestStopped, now: dateProvider.now)
        }
    #endif

    // MARK: - Session lifecycle

    private func resumeDrivingSessionIfInterrupted(now: Date) async {
        guard drivingEvidence == nil,
              let restored = checkpoint,
              restored.state == .driving || restored.state == .drivingCandidate
        else { return }

        let resumed = DrivingEvidence(
            startedAt: restored.stateEnteredAt,
            lastVehicleEvidenceAt: restored.lastAutomotiveAt
        )
        // A session that would time out the instant it reopens is not worth the GPS. Let
        // the ordinary expiry rule decide, so there is one place that defines "too old".
        if let expiry = DrivingSessionTimeoutPolicy.expiryReason(for: resumed, now: now) {
            closeStaleDrivingState(reason: expiry, now: now)
            return
        }

        drivingEvidence = resumed
        consumedVehicleEvidenceAt = restored.lastAutomotiveAt
        snapshot.drivingSessionResumedFromCheckpoint = true
        await beginCapture(startedAt: restored.stateEnteredAt, now: now)
    }

    /// Turns replayed motion history into the two decisions this milestone makes: open a
    /// bounded session, or close one.
    ///
    /// Evaluated on every wake, so the same history is seen repeatedly. Two guards keep
    /// that idempotent: walking is only read relative to the vehicle evidence it follows,
    /// and a vehicle sample that already opened a session never opens another.
    private func evaluateMotionEvidence(now: Date) async {
        let samples = snapshot.motionSamples
        let vehicle = MotionEvidenceReader.latestVehicleEvidence(in: samples)

        if let vehicle {
            snapshot.lastVehicleEvidenceAt = vehicle.timestamp
            snapshot.lastVehicleEvidenceConfidence = vehicle.confidence
            noteVehicleEvidence(at: vehicle.timestamp, now: now)
        }

        if let evidence = drivingEvidence {
            // Walking after the vehicle evidence is the parking transition (docs/05 §8).
            // In M0A-2 it ends the session and preserves the fix; creating the candidate
            // and notifying it is M3.
            let reference = evidence.lastVehicleEvidenceAt ?? evidence.startedAt
            if MotionEvidenceReader.latestWalkingEvidence(in: samples, after: reference) != nil {
                await endDrivingSession(reason: .walkingDetected, now: now)
            }
            return
        }

        guard let vehicle,
              now.timeIntervalSince(vehicle.timestamp) <= DrivingConfirmationPolicy.vehicleEvidenceMaxAge,
              vehicle.timestamp > (consumedVehicleEvidenceAt ?? .distantPast),
              // Already walked away from the car: replaying that history must not reopen
              // a session for a drive that is over.
              MotionEvidenceReader.latestWalkingEvidence(in: samples, after: vehicle.timestamp) == nil
        else { return }

        // Confidence is deliberately not gated here. docs/05 §7 puts the rigor in the
        // *confirmation* guard, and a session opened on a weak signal costs at most one
        // `vehicleEvidenceTimeout` window — while a missed one costs the whole trip.
        // Revisit once the field data in §18 exists.
        await startDrivingSession(at: vehicle.timestamp, now: now)
    }

    private func noteVehicleEvidence(at date: Date, now: Date) {
        guard var evidence = drivingEvidence else { return }
        evidence.noteVehicleEvidence(at: date)
        drivingEvidence = evidence

        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        if updated.lastAutomotiveAt != date {
            updated.lastAutomotiveAt = date
            updated.revision += 1
            persist(updated)
        }
    }

    private func startDrivingSession(at vehicleEvidenceAt: Date, now: Date) async {
        let evidence = DrivingEvidence(startedAt: now, lastVehicleEvidenceAt: vehicleEvidenceAt)
        drivingEvidence = evidence
        consumedVehicleEvidenceAt = vehicleEvidenceAt
        snapshot.drivingSessionCount += 1

        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        updated.state = .drivingCandidate
        updated.stateEnteredAt = now
        updated.lastAutomotiveAt = vehicleEvidenceAt
        updated.travelDistanceEstimate = 0
        updated.revision += 1
        persist(updated)

        await beginCapture(startedAt: now, now: now)
    }

    private func beginCapture(startedAt: Date, now: Date) async {
        snapshot.drivingSessionStartedAt = startedAt
        snapshot.drivingConfirmedAt = nil
        snapshot.drivingFixCount = 0
        snapshot.drivingMovingSampleCount = 0
        snapshot.drivingOutlierDropCount = 0
        snapshot.drivingDistanceMeters = 0
        snapshot.captureFailure = nil

        await locationCapture?.start()
        snapshot.isCapturingDrivingLocation = await locationCapture?.isActive() ?? false
        AppLog.detection.notice(
            "bounded driving session started, age=\(Int(now.timeIntervalSince(startedAt)), privacy: .public)s"
        )
    }

    /// docs/04 §3 "Stop aggressive tracking": cancel the updates task, invalidate the
    /// background session, return to significant-change monitoring.
    ///
    /// Idempotent, and it tears the capture down even when no session is open — a
    /// mismatch between our bookkeeping and Core Location's must always resolve towards
    /// *off*, never towards a session nobody owns.
    private func endDrivingSession(reason: DrivingSessionEndReason, now: Date) async {
        let hadSession = drivingEvidence != nil
        let distance = drivingEvidence?.distanceMeters ?? 0
        if let last = drivingEvidence?.lastVehicleEvidenceAt {
            consumedVehicleEvidenceAt = max(consumedVehicleEvidenceAt ?? last, last)
        }
        drivingEvidence = nil

        await locationCapture?.stop()
        snapshot.isCapturingDrivingLocation = await locationCapture?.isActive() ?? false

        guard hadSession else { return }

        snapshot.drivingSessionStartedAt = nil
        snapshot.lastDrivingSessionEndReason = reason
        snapshot.lastDrivingSessionEndedAt = now

        // docs/05 §14: the parking transition is a checkpoint write. The state returns to
        // IDLE because creating the candidate is M3 — what M0A-2 preserves is the fix and
        // the distance, which is exactly what a candidate will be built from.
        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        updated.state = .idle
        updated.stateEnteredAt = now
        updated.travelDistanceEstimate = distance
        updated.revision += 1
        persist(updated)

        AppLog.detection.notice("bounded driving session ended: \(reason.rawValue, privacy: .public)")
    }

    /// A restored checkpoint that is already expired: close the state without pretending
    /// a session ran in this process.
    private func closeStaleDrivingState(reason: DrivingSessionEndReason, now: Date) {
        snapshot.lastDrivingSessionEndReason = reason
        snapshot.lastDrivingSessionEndedAt = now

        guard var updated = checkpoint else { return }
        updated.state = .idle
        updated.stateEnteredAt = now
        updated.revision += 1
        persist(updated)
    }

    private func evaluateDrivingTimeouts(now: Date) async {
        guard let evidence = drivingEvidence,
              let reason = DrivingSessionTimeoutPolicy.expiryReason(for: evidence, now: now)
        else { return }
        await endDrivingSession(reason: reason, now: now)
    }

    // MARK: - Reliable location and confirmation

    private func updateReliableLocation(with fix: LocationFix, now: Date) {
        let incumbent = checkpoint?.lastReliableLocation
        switch ReliableLocationPolicy.evaluate(candidate: fix, incumbent: incumbent, now: now) {
        case let .accepted(selected):
            snapshot.reliableLocationUpdateCount += 1
            snapshot.lastReliableLocationRejection = nil

            var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
            updated.lastReliableLocation = selected
            updated.lastLocationAt = selected.capturedAt
            updated.travelDistanceEstimate = drivingEvidence?.distanceMeters ?? updated.travelDistanceEstimate
            snapshot.lastLocationAt = selected.capturedAt
            snapshot.lastLocationAccuracy = selected.horizontalAccuracy

            // docs/05 §14 writes on a *materially* better fix. Holding the improved value
            // in memory either way means a write we skipped is never a value we lost: the
            // session end persists whatever the last accepted fix was.
            if ReliableLocationPolicy.isMateriallyBetter(selected, than: incumbent) {
                updated.revision += 1
                persist(updated)
            } else {
                checkpoint = updated
                snapshot.currentCheckpoint = updated
            }

        case let .rejected(reason):
            snapshot.reliableLocationRejectCount += 1
            snapshot.lastReliableLocationRejection = reason
        }
    }

    private func confirmDrivingIfReady(now: Date) {
        guard var evidence = drivingEvidence,
              !evidence.isConfirmed,
              DrivingConfirmationPolicy.isConfirmed(evidence, now: now)
        else { return }

        evidence.markConfirmed(at: now)
        drivingEvidence = evidence
        snapshot.drivingConfirmedAt = now

        // docs/05 §14: "driving confirmed" is a checkpoint write.
        //
        // `stateEnteredAt` moves to the confirmation instant because the contract defines
        // it as when *this* state was entered. A session recreated after process death
        // therefore measures its ceiling from confirmation rather than from the original
        // start — still bounded, and duration no longer decides anything for a session
        // that is already confirmed.
        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        updated.state = .driving
        updated.stateEnteredAt = now
        updated.travelDistanceEstimate = evidence.distanceMeters
        updated.revision += 1
        persist(updated)

        AppLog.detection.notice(
            "driving confirmed after \(Int(evidence.duration(now: now)), privacy: .public)s"
        )
    }

    // MARK: - Shared helpers

    private func reconstructMotionHistory(now: Date, anchor: Date?) async {
        let window = MotionHistoryWindowPolicy.window(now: now, checkpointDate: anchor)
        snapshot.motionWindow = window

        do {
            let samples = try await motionHistory.samples(in: window)
            snapshot.motionSamples = samples
            snapshot.motionFailure = nil
            AppLog.detection.notice("motion history restored: \(samples.count, privacy: .public) samples")
        } catch let error as MotionHistoryError {
            snapshot.motionSamples = []
            snapshot.motionFailure = error.diagnosticDescription
        } catch {
            snapshot.motionSamples = []
            let nsError = error as NSError
            snapshot.motionFailure = "unexpected: \(nsError.domain)(\(nsError.code))"
        }
    }

    private func persist(_ checkpoint: DetectionCheckpoint) {
        self.checkpoint = checkpoint
        snapshot.currentCheckpoint = checkpoint
        do {
            try checkpointStore.save(checkpoint)
            snapshot.lastPersistError = nil
        } catch {
            let description = String(describing: error)
            snapshot.lastPersistError = description
            AppLog.detection.error("checkpoint save failed: \(description, privacy: .public)")
        }
    }
}

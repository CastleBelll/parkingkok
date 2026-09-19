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
    /// Fresh samples that arrived older than the checkpoint already knew about.
    var supersededLocationDropCount = 0
    var lastPersistError: String?
    /// Last trace-recording write failure. Recording is best-effort, so the failure has to
    /// be visible somewhere or it is silent (docs/05 §9).
    var traceFailure: String?
    /// Location observations the recorder refused because it had already recorded that
    /// instant — Core Location replaying a cached fix, most often after a relaunch.
    var traceReplayDropCount = 0

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
    /// Accepted fixes that did and did not carry a Core Location speed estimate, and how
    /// much of `drivingMovingSampleCount` the distance fallback contributed. Together
    /// they separate "the device never moved" from "the platform never reported a speed",
    /// which the September 2026 field traces showed are not the same failure.
    var drivingSpeedAvailableCount = 0
    var drivingSpeedMissingCount = 0
    var drivingDerivedMovingSampleCount = 0
    /// Why the distance fallback last declined a fix.
    var movementEvidenceRejectReason: MovementEvidenceRejection?
    var drivingOutlierDropCount = 0
    var drivingDistanceMeters: Double = 0
    /// Legs §7's noise floor kept out of `drivingDistanceMeters`. Large next to a distance
    /// that never grows means the floor is wrong for this device, not that nothing moved.
    var drivingDistanceNoiseFloorRejectCount = 0
    /// Accepted `lastReliableLocation` updates. Zero with a non-zero fix count means the
    /// §6 gate is set wrong for this device.
    var reliableLocationUpdateCount = 0
    var reliableLocationRejectCount = 0
    var lastReliableLocationRejection: ReliableLocationRejection?
    var lastVehicleEvidenceAt: Date?
    var lastVehicleEvidenceConfidence: MotionConfidence?
    var captureFailure: String?
    /// When `PARKING_TRANSITION` was entered, while it is still deciding. `nil` otherwise.
    var parkingTransitionEnteredAt: Date?
    /// Candidates created in this process, and candidates §6's rule refused.
    ///
    /// The pair is the whole of "why did a drive produce nothing": a refusal count that
    /// grows while the created count stays at zero says the transition window is closing
    /// before Core Motion reports the walk, which is a tuning problem, not a wiring one.
    var candidateCreatedCount = 0
    var candidateRuleUnmetCount = 0
    /// Bucket of the newest candidate, so the field checklist can read it without the file.
    var lastCandidateConfidence: ConfidenceBucket?
    /// Last candidate write failure. Best-effort like every other persistence path here,
    /// so the failure has to be visible or it is silent.
    var candidateStoreFailure: String?
}

/// Owns the detection state that outlives any one screen or callback.
///
/// An `actor` because the checkpoint is shared mutable state touched from a background
/// relaunch, a delegate callback, and the UI (docs/03_SYSTEM_ARCHITECTURE.md §4).
///
/// Scope note: this coordinates rehydration, the bounded driving session, and the
/// `DRIVING → PARKING_TRANSITION → CANDIDATE_PENDING` half of the docs/05 §3a table.
/// Departure (`PARKED → DEPARTURE_CANDIDATE`, §11) is not implemented here yet.
actor BackgroundCoordinator {
    private let checkpointStore: any DetectionCheckpointStoring
    private let motionHistory: any MotionHistoryProviding
    private let locationCapture: (any BoundedLocationCapturing)?
    private let dateProvider: any DateProviding
    /// Where a created candidate is persisted so a screen opened minutes later can read
    /// it. `nil` disables candidates and nothing else.
    private let candidateStore: (any ParkingCandidateStoring)?
    private let candidateNotifier: (any CandidateNotifying)?
    /// docs/17 §2 `parking_candidate_created`. Reported from here rather than from the UI
    /// because the candidate is created in a process that may have no UI at all.
    private let analytics: any AnalyticsRecording
    /// Field-data recorder (docs/05 §9). `nil` disables recording entirely, which is what
    /// the tests that are not about tracing use.
    private var traceRecorder: TraceRecorder?
    /// Smart Detection's opt-in, mirrored here because §9 makes it a trace boundary: while
    /// it is off nothing is recorded, and turning it off closes the open session.
    private var isTraceRecordingEnabled = true

    private var checkpoint: DetectionCheckpoint?
    private var snapshot = RehydrationSnapshot()
    /// Non-nil exactly while a bounded session is open.
    private var drivingEvidence: DrivingEvidence?
    /// Newest vehicle observation that has already opened a session. Motion history is
    /// replayed on every wake, so without this the same `automotive` sample would reopen
    /// a session the walking transition had just closed.
    private var consumedVehicleEvidenceAt: Date?
    /// Non-nil exactly while the state is `PARKING_TRANSITION`.
    private var parkingTransition: ParkingTransition?

    init(
        checkpointStore: any DetectionCheckpointStoring,
        motionHistory: any MotionHistoryProviding,
        locationCapture: (any BoundedLocationCapturing)? = nil,
        dateProvider: any DateProviding = SystemDateProvider(),
        traceRecorder: TraceRecorder? = nil,
        candidateStore: (any ParkingCandidateStoring)? = nil,
        candidateNotifier: (any CandidateNotifying)? = nil,
        analytics: any AnalyticsRecording = DisabledAnalyticsRecorder()
    ) {
        self.checkpointStore = checkpointStore
        self.motionHistory = motionHistory
        self.locationCapture = locationCapture
        self.dateProvider = dateProvider
        self.traceRecorder = traceRecorder
        self.candidateStore = candidateStore
        self.candidateNotifier = candidateNotifier
        self.analytics = analytics
    }

    /// The drive that just ended, held while `PARKING_TRANSITION` decides what it was.
    ///
    /// docs/05 §3a: "codes accumulate as evidence arrives and travel with the candidate".
    /// `evidence` is that accumulation — it is added to as signals show up and handed to
    /// the policy unchanged, never recomputed at the end from the final state.
    private struct ParkingTransition {
        let enteredAt: Date
        /// Newest vehicle observation of the drive. "After the vehicle" is measured from
        /// here, not from `enteredAt`, so a walk that began before the session formally
        /// closed still counts.
        let vehicleReference: Date
        let entryReason: DrivingSessionEndReason
        var evidence: ParkingEvidence
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
        restoreParkingTransitionIfInterrupted(now: now)
        await expirePendingCandidateIfDue(now: now)
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
        // The trace records what arrived either way: it describes what the device saw,
        // and its own watermark decides what to keep.
        recordTrace { $0.record(qualitySample: sample) }

        // Fresh enough is not the same as newest. The 300s bound rejects an hours-old
        // cached fix, but Core Location also replays recent ones — that replay is what
        // put duplicate fixes in the trace — and a 200s-old sample arriving after a
        // bounded fix from 10s ago clears the bound while still being older than what the
        // checkpoint already holds. Writing it would walk `lastLocationAt` backwards,
        // which widens the motion replay window and ages the checkpoint on paper.
        //
        // Android reaches the same rule from the other side: its reliable-location
        // selector refuses a sample that is not newer, so the value it writes alongside
        // can never regress.
        guard sample.timestamp > (checkpoint?.lastLocationAt ?? .distantPast) else {
            snapshot.supersededLocationDropCount += 1
            return
        }

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
        recordTrace { $0.record(fix: fix) }
        snapshot.drivingFixCount = evidence.fixCount
        snapshot.drivingMovingSampleCount = evidence.movingSampleCount
        snapshot.drivingSpeedAvailableCount = evidence.speedAvailableCount
        snapshot.drivingSpeedMissingCount = evidence.speedMissingCount
        snapshot.drivingDerivedMovingSampleCount = evidence.derivedMovingSampleCount
        snapshot.movementEvidenceRejectReason = evidence.movementEvidenceRejection
        snapshot.drivingOutlierDropCount = evidence.outlierCount
        snapshot.drivingDistanceMeters = evidence.distanceMeters
        snapshot.drivingDistanceNoiseFloorRejectCount = evidence.distanceNoiseFloorRejectCount

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

    /// docs/05 §9: "smart detection을 끄면 열린 세션을 즉시 닫는다."
    ///
    /// The opt-in is the one boundary the user controls, and it is the only reason a trace
    /// ever ends without an event arriving to end it. Turning recording back on does not
    /// reopen anything — the next event starts a new session, which is the point.
    func setTraceRecordingEnabled(_ enabled: Bool) {
        isTraceRecordingEnabled = enabled
        guard !enabled, var recorder = traceRecorder else { return }
        recorder.closeOpenSession()
        traceRecorder = recorder
        snapshot.traceFailure = recorder.lastFailure
        snapshot.traceReplayDropCount = recorder.replayDropCount
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

        /// Creates a candidate from synthetic evidence, **through the real path**.
        ///
        /// The notification and the confirmation screen cannot be exercised without a
        /// parked car, and a car cannot be parked on demand in a simulator or in a
        /// meeting. So this builds the evidence a drive would have produced and hands it
        /// to `createCandidate`, which then applies §6's rule, §8's weights, §9's buckets
        /// and the `low`-posts-nothing gate exactly as it would for a real trip. **No gate
        /// is bypassed** — that is the whole point, and it is why `walking` is the only
        /// parameter: it is the difference between a `medium` that notifies and a `low`
        /// that is recorded in silence.
        ///
        /// DEV configuration only: `PK_DEV` is defined in `Config/Dev.xcconfig` and
        /// nowhere else, so none of this is compiled into STAGING or PROD.
        func injectCandidateForFieldTest(walking: Bool) async {
            let now = dateProvider.now
            let transition = ParkingTransition(
                enteredAt: now,
                vehicleReference: now,
                entryReason: .walkingDetected,
                evidence: ParkingEvidence(
                    hasMeaningfulVehicleSession: true,
                    vehicleEnded: true,
                    walkingAfterVehicle: walking,
                    stationaryAfterVehicle: !walking,
                    reliableLocationCaptured: checkpoint?.lastReliableLocation != nil,
                    driveDuration: 15 * 60,
                    driveDistanceMeters: 5000
                )
            )
            await createCandidate(from: transition, now: now)
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
        // Nothing to do for the trace here. §9's boundary is a property of the event
        // stream, so the recorder picks the open session back up from disk when the next
        // event arrives — a drive interrupted by process death is still one trip.
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

        // Unconditional, and before any session decision. docs/05 §9: recording is not
        // gated on vehicle evidence — a walk, a subway ride and a stationary transition are
        // exactly the negative cases §17 needs, and gating on the bounded driving session
        // is what dropped them. It also has to happen before the walking transition can end
        // a session, or the one event that explains the ending would be missing.
        recordTrace { $0.record(motionSamples: samples) }

        if let vehicle {
            snapshot.lastVehicleEvidenceAt = vehicle.timestamp
            snapshot.lastVehicleEvidenceConfidence = vehicle.confidence
            noteVehicleEvidence(at: vehicle.timestamp, now: now)
        }

        if drivingEvidence != nil {
            // docs/05 §3a promotes on sustained vehicle activity alone, which is a matter
            // of elapsed time rather than of fixes arriving — so it has to be asked on
            // every wake and not only when the bounded session produces one. A drive
            // through an underground car park produces no fixes at all, and that is the
            // drive this product exists for.
            confirmDrivingIfReady(now: now)
        }

        if let evidence = drivingEvidence {
            // Walking after the vehicle evidence is what docs/05 §3a calls the entry to
            // `PARKING_TRANSITION`: the drive is over, and the walk is already in hand, so
            // `endDrivingSession` hands straight on to the state that decides.
            let reference = evidence.lastVehicleEvidenceAt ?? evidence.startedAt
            if MotionEvidenceReader.latestWalkingEvidence(in: samples, after: reference) != nil {
                await endDrivingSession(reason: .walkingDetected, now: now)
            }
            return
        }

        if parkingTransition != nil {
            await evaluateParkingTransition(samples: samples, now: now)
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
        // A new travel session supersedes whatever the last one was still deciding.
        parkingTransition = nil
        snapshot.parkingTransitionEnteredAt = nil
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
        snapshot.drivingSpeedAvailableCount = 0
        snapshot.drivingSpeedMissingCount = 0
        snapshot.drivingDerivedMovingSampleCount = 0
        snapshot.movementEvidenceRejectReason = nil
        snapshot.drivingOutlierDropCount = 0
        snapshot.drivingDistanceMeters = 0
        snapshot.drivingDistanceNoiseFloorRejectCount = 0
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
        let wasConfirmed = drivingEvidence?.isConfirmed ?? false
        let vehicleReference = drivingEvidence?.lastVehicleEvidenceAt
        let driveDuration = drivingEvidence.map { $0.duration(now: now) }
        let endingAccuracyBucket = drivingEvidence?.lastFix
            .flatMap { LocationAccuracyBucket(horizontalAccuracy: $0.horizontalAccuracy) }
        if let last = drivingEvidence?.lastVehicleEvidenceAt {
            consumedVehicleEvidenceAt = max(consumedVehicleEvidenceAt ?? last, last)
        }
        drivingEvidence = nil

        await locationCapture?.stop()
        snapshot.isCapturingDrivingLocation = await locationCapture?.isActive() ?? false

        guard hadSession else { return }
        // The trace deliberately stays open: the walk away from the car is the other half
        // of the parking transition a fixture is cut from, and §9 lets the idle gap decide
        // when the trip is actually over.
        snapshot.drivingSessionStartedAt = nil
        snapshot.lastDrivingSessionEndReason = reason
        snapshot.lastDrivingSessionEndedAt = now

        // docs/05 §14: the parking transition is a checkpoint write.
        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        updated.state = entersParkingTransition(reason: reason, wasConfirmed: wasConfirmed)
            ? .parkingTransition
            : .idle
        updated.stateEnteredAt = now
        updated.travelDistanceEstimate = distance
        updated.revision += 1
        persist(updated)

        AppLog.detection.notice("bounded driving session ended: \(reason.rawValue, privacy: .public)")

        guard updated.state == .parkingTransition else { return }
        parkingTransition = ParkingTransition(
            enteredAt: now,
            vehicleReference: vehicleReference ?? now,
            entryReason: reason,
            evidence: ParkingEvidence(
                hasMeaningfulVehicleSession: true,
                vehicleEnded: true,
                // §8 "GPS quality degraded near end", and §13's underground pattern. It is
                // supporting evidence only — §6 will not let it satisfy the rule alone.
                gpsQualityDegraded: endingAccuracyBucket == .poor,
                reliableLocationCaptured: updated.lastReliableLocation != nil,
                driveDuration: driveDuration,
                driveDistanceMeters: distance
            )
        )
        snapshot.parkingTransitionEnteredAt = now
        // The walk that ended the session is already evidence; evaluate now rather than
        // waiting for a wake that may be minutes away.
        await evaluateParkingTransition(samples: snapshot.motionSamples, now: now)
    }

    /// docs/05 §3a. Only a **confirmed** `DRIVING` session goes on to decide whether it
    /// parked; `DRIVING_CANDIDATE` leaves straight for `IDLE`.
    ///
    /// That split is what §12's short-trip guard is made of: a three-minute ride that never
    /// cleared §7's bar produces no candidate at all, rather than a low-confidence one the
    /// user has to dismiss. The reasons that are *not* here — a lost authorization, a
    /// capture failure, the opt-out, the hard duration ceiling — all describe the app
    /// losing the drive rather than the drive ending, and none of them is evidence that a
    /// car stopped anywhere.
    private func entersParkingTransition(reason: DrivingSessionEndReason, wasConfirmed: Bool) -> Bool {
        guard wasConfirmed else { return false }
        switch reason {
        case .walkingDetected, .vehicleEvidenceExpired, .movementIdle:
            return true
        case .maximumDurationReached, .authorizationLost, .captureFailed,
             .smartDetectionDisabled, .fieldTestStopped:
            return false
        }
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
        // Same reason as in `evaluateMotionEvidence`: promotion is a clock, and the
        // watchdog is the only thing still ticking when the fix stream is not.
        confirmDrivingIfReady(now: now)
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

    // MARK: - Parking transition and candidate (docs/05 §3a, §10a)

    /// The three edges out of `PARKING_TRANSITION`, in the order the red light demands.
    ///
    /// **Vehicle evidence is checked first, and that ordering is the whole guard against
    /// the false positive §3a warns about.** Core Motion reports `automotive` and
    /// `stationary` together at a long red light, so a transition entered on
    /// `movementIdle` will usually have a `stationary` sample sitting in it. Asking
    /// "did the car move again?" before "did the person stop?" is what makes that a drive
    /// rather than a parking.
    private func evaluateParkingTransition(samples: [MotionSample], now: Date) async {
        guard var transition = parkingTransition else { return }

        // 1. `PARKING_TRANSITION → DRIVING`: the vehicle came back inside the window.
        if let resumed = MotionEvidenceReader.latestVehicleEvidence(in: samples),
           resumed.timestamp > transition.enteredAt,
           !ParkingTransitionPolicy.hasElapsed(enteredAt: transition.enteredAt, now: now) {
            await resumeDrivingFromTransition(vehicleEvidenceAt: resumed.timestamp, now: now)
            return
        }

        // 2. `PARKING_TRANSITION → CANDIDATE_PENDING`: a confirming signal arrived.
        let reference = transition.vehicleReference
        if MotionEvidenceReader.latestWalkingEvidence(in: samples, after: reference) != nil {
            transition.evidence.walkingAfterVehicle = true
        }
        if MotionEvidenceReader.latestStationaryEvidence(in: samples, after: reference) != nil {
            transition.evidence.stationaryAfterVehicle = true
        }
        parkingTransition = transition

        // **The signal that caused the transition cannot also be the signal that ends
        // it.** §8's "location movement stopped" is folded in when the candidate is built,
        // not here: a transition entered *because* movement stopped would otherwise leave
        // the same instant it arrived, and `DRIVING → PARKING_TRANSITION → DRIVING` — the
        // red light §3a exists to describe — could never happen. What this waits for is a
        // signal observed inside the window.
        if transition.evidence.confirmationSignals > 0 {
            await createCandidate(from: transition, now: now)
            return
        }

        // 3. `PARKING_TRANSITION → IDLE`: the window closed with nothing to show.
        if ParkingTransitionPolicy.hasElapsed(enteredAt: transition.enteredAt, now: now) {
            closeParkingTransition(now: now)
        }
    }

    /// docs/05 §3a `PARKING_TRANSITION → CANDIDATE_PENDING`, and §10a's posting rules.
    ///
    /// The order is deliberate and is what the permission-denied path rests on: the
    /// candidate is **written first**, then the checkpoint, then the notification. Denied
    /// notifications, a notification service that throws, a process killed a millisecond
    /// later — none of them can cost the user the candidate, which is what §10a means by
    /// "notification permission is not required for correctness".
    private func createCandidate(from transition: ParkingTransition, now: Date) async {
        parkingTransition = nil
        snapshot.parkingTransitionEnteredAt = nil

        var evidence = transition.evidence
        // §8 "location movement stopped", added now rather than at entry — see
        // `evaluateParkingTransition`. It still earns its weight and its reason code; what
        // it may not do is be the only thing that ended the transition.
        evidence.locationStopped = transition.entryReason == .movementIdle

        let accuracyBucket = checkpoint?.lastReliableLocation
            .flatMap { LocationAccuracyBucket(horizontalAccuracy: $0.horizontalAccuracy) }
        guard let candidate = ParkingCandidatePolicy.evaluate(
            evidence,
            id: UUID(),
            // Stamped with when the evidence says it happened, not with the wake that
            // noticed: expiry runs from the parking, and a lazy evaluation must not buy
            // the user extra minutes.
            detectedAt: now,
            lastReliableLocation: checkpoint?.lastReliableLocation,
            accuracyBucket: accuracyBucket
        ) else {
            // §6's rule was not met. An ordinary outcome, counted rather than logged as a
            // failure — but counted, because a rising number here with no candidates is
            // how a mis-tuned transition window announces itself.
            snapshot.candidateRuleUnmetCount += 1
            closeParkingTransition(now: now)
            return
        }

        // §10a: "If a new travel session produces a candidate while an older one is still
        // pending, the older candidate expires immediately and its notification is
        // withdrawn. A stale prompt about a previous trip is worse than no prompt."
        await supersedePendingCandidate()

        do {
            try candidateStore?.save(candidate)
            snapshot.candidateStoreFailure = nil
        } catch {
            snapshot.candidateStoreFailure = String(describing: error)
            AppLog.detection.error("candidate save failed: \(String(describing: error), privacy: .public)")
        }

        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        updated.state = .candidatePending
        updated.stateEnteredAt = now
        updated.candidateId = candidate.id
        updated.revision += 1
        persist(updated)

        snapshot.candidateCreatedCount += 1
        snapshot.lastCandidateConfidence = candidate.confidenceBucket
        // docs/17 §2. Reported for every candidate, including the `low` one nobody sees:
        // the created/rejected ratio is only readable if both halves are counted.
        analytics.record(.parkingCandidateCreated(candidate.analyticsProperties))

        // §9: `low` posts nothing. The candidate above is already on disk, so the app
        // still shows it when opened.
        if candidate.isNotifiable {
            await candidateNotifier?.post(candidate)
        }
        AppLog.detection.notice(
            "candidate created, confidence=\(candidate.confidenceBucket.rawValue, privacy: .public)"
        )
    }

    /// docs/05 §3a `PARKING_TRANSITION → DRIVING`. The red light is over.
    ///
    /// Restores `DRIVING` rather than `DRIVING_CANDIDATE`: this session was already
    /// confirmed before it stopped, and making it re-earn confirmation would let a car in
    /// stop-start traffic never reach a parking transition at all.
    private func resumeDrivingFromTransition(vehicleEvidenceAt: Date, now: Date) async {
        parkingTransition = nil
        snapshot.parkingTransitionEnteredAt = nil

        var evidence = DrivingEvidence(startedAt: now, lastVehicleEvidenceAt: vehicleEvidenceAt)
        evidence.markConfirmed(at: now)
        drivingEvidence = evidence
        consumedVehicleEvidenceAt = vehicleEvidenceAt
        snapshot.drivingSessionCount += 1

        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        updated.state = .driving
        updated.stateEnteredAt = now
        updated.lastAutomotiveAt = vehicleEvidenceAt
        updated.revision += 1
        persist(updated)

        await beginCapture(startedAt: now, now: now)
        snapshot.drivingConfirmedAt = now
    }

    /// `PARKING_TRANSITION → IDLE`. Silent by contract: nothing was persisted on the way
    /// in beyond the checkpoint, and nothing was shown, so there is nothing to take back.
    private func closeParkingTransition(now: Date) {
        parkingTransition = nil
        snapshot.parkingTransitionEnteredAt = nil

        var updated = checkpoint ?? DetectionCheckpoint.initial(at: now)
        guard updated.state == .parkingTransition else { return }
        updated.state = .idle
        updated.stateEnteredAt = now
        updated.revision += 1
        persist(updated)
    }

    /// Rebuilds the transition a dead process left behind.
    ///
    /// The evidence is thinner than the original — `driveDuration` is not a checkpoint
    /// field and is gone — and it says so by leaving the value `nil` rather than guessing
    /// one. An absent duration costs a reason code and an analytics bucket; a fabricated
    /// one would cost the meaning of both.
    private func restoreParkingTransitionIfInterrupted(now: Date) {
        guard parkingTransition == nil,
              let restored = checkpoint,
              restored.state == .parkingTransition
        else { return }

        guard !ParkingTransitionPolicy.hasElapsed(enteredAt: restored.stateEnteredAt, now: now) else {
            closeParkingTransition(now: now)
            return
        }
        parkingTransition = ParkingTransition(
            enteredAt: restored.stateEnteredAt,
            vehicleReference: restored.lastAutomotiveAt ?? restored.stateEnteredAt,
            entryReason: .vehicleEvidenceExpired,
            evidence: ParkingEvidence(
                hasMeaningfulVehicleSession: true,
                vehicleEnded: true,
                reliableLocationCaptured: restored.lastReliableLocation != nil,
                driveDistanceMeters: restored.travelDistanceEstimate
            )
        )
        snapshot.parkingTransitionEnteredAt = restored.stateEnteredAt
    }

    /// docs/05 §10: the 45 minutes ran out with nobody answering.
    ///
    /// Evaluated on every wake rather than on a timer — see `CandidateModel` for why the
    /// whole engine treats deadlines lazily. No record is created and nothing is reported:
    /// docs/17 §2 has no event for a guess that went unanswered.
    private func expirePendingCandidateIfDue(now: Date) async {
        guard let store = candidateStore, let candidate = store.load() else { return }
        guard candidate.isExpired(now: now) else { return }
        await retirePendingCandidate(candidate, now: now)
        AppLog.detection.notice("candidate expired unanswered")
    }

    private func supersedePendingCandidate() async {
        guard let candidate = candidateStore?.load() else { return }
        await retirePendingCandidate(candidate, now: dateProvider.now)
    }

    private func retirePendingCandidate(_ candidate: ParkingCandidate, now: Date) async {
        try? candidateStore?.clear()
        await candidateNotifier?.withdraw(candidateId: candidate.id)
        moveCandidateStateTo(.idle, now: now)
    }

    /// The user answered (docs/05 §3a: `CANDIDATE_PENDING → PARKED` or `→ IDLE`).
    ///
    /// Called from `CandidateModel` through `DetectionRuntime`, because the screen and the
    /// lock screen are where the answer arrives and the checkpoint is what has to remember
    /// it across process death.
    func resolveCandidate(_ outcome: CandidateOutcome) {
        moveCandidateStateTo(outcome == .confirmed ? .parked : .idle, now: dateProvider.now)
    }

    /// Leaves `CANDIDATE_PENDING` exactly once, and always forgets the candidate.
    ///
    /// The two halves are guarded differently on purpose. Clearing `candidateId` is
    /// unconditional — whatever the engine is doing now, that candidate has been answered.
    /// Moving the *state* only happens while the state is still the candidate's: the
    /// expiry sweep, the notification action and the screen can all reach this for the
    /// same candidate, and a user who has already started driving again must not have that
    /// new trip rolled back to `IDLE` by an answer to the last one.
    private func moveCandidateStateTo(_ state: DetectionState, now: Date) {
        guard var updated = checkpoint, updated.candidateId != nil else { return }
        updated.candidateId = nil
        if updated.state == .candidatePending {
            updated.state = state
            updated.stateEnteredAt = now
        }
        updated.revision += 1
        persist(updated)
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

    /// The single place recording touches the coordinator's state.
    ///
    /// Best-effort by construction: `TraceRecorder` swallows its own write failures into
    /// `lastFailure`, and this lifts that into the snapshot so a trace that stopped being
    /// written is visible in diagnostics instead of silent (docs/05 §9).
    private func recordTrace(_ body: (inout TraceRecorder) -> Void) {
        guard isTraceRecordingEnabled, var recorder = traceRecorder else { return }
        body(&recorder)
        traceRecorder = recorder
        snapshot.traceFailure = recorder.lastFailure
        snapshot.traceReplayDropCount = recorder.replayDropCount
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

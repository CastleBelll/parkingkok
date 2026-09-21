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
    /// docs/04_IOS §3a. When the engine last asked for a capture, as distinct from when the
    /// adapter managed to start one — the 2026-09-20 drive could not tell those apart.
    var captureRequestedAt: Date?
    var captureHealth = BoundedCaptureHealth.none
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
    /// Car links the adapter currently observes (docs/05 §3a "The car link"). Exported so
    /// the field checklist can tell "the phone never saw the car" from "the link was seen
    /// and the engine ignored it".
    var connectedCarLinks: Set<CarLinkKind> = []
    /// Whether the audio route could be read at all. `nil` means it has not been sampled
    /// yet; a description means the sample failed, which is the difference between "no car"
    /// and "no answer".
    var carLinkFailure: String?
}

/// The adapter between the platform and `ParkingDetectionEngine`.
///
/// An `actor` because the state it holds is touched from a background relaunch, a delegate
/// callback and the UI (docs/03_SYSTEM_ARCHITECTURE.md §4).
///
/// ### What moved out of here
/// The §3a transition table used to live in this type. It is now `ParkingDetectionEngine`,
/// and the split is what lets `platform-tests/*.json` be replayed through the very code the
/// device runs. What remains is everything that is genuinely about *this platform*:
///
/// * turning Core Motion history and Core Location callbacks into the §2 normalized events,
///   including the `vehicle_exit` edge iOS has to derive because it polls history rather
///   than receiving transitions,
/// * performing the `DetectionEffect`s — files, notifications, analytics, the bounded
///   capture,
/// * trace recording (§9) and the diagnostics snapshot.
///
/// Departure (`PARKED → DEPARTURE_CANDIDATE`, §11) is not implemented yet on either side.
actor BackgroundCoordinator {
    private let checkpointStore: any DetectionCheckpointStoring
    private let motionHistory: any MotionHistoryProviding
    private let locationCapture: (any BoundedLocationCapturing)?
    private let dateProvider: any DateProviding
    /// Where a created candidate is persisted so a screen opened minutes later can read
    /// it. `nil` disables candidates and nothing else.
    private let candidateStore: (any ParkingCandidateStoring)?
    /// docs/10 §7b's bell (docs/05 §10a "History"). The engine retires a candidate the
    /// user never answered — superseded by a newer trip, timed out, or overtaken by the
    /// car link coming back — and those resolutions never reach `CandidateModel`, because
    /// the file is already gone by the time a screen looks. `nil` disables the history and
    /// nothing else.
    private let candidateHistory: (any CandidateHistoryStoring)?
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

    private let engine: ParkingDetectionEngine
    private var snapshot = RehydrationSnapshot()
    /// Newest vehicle observation already handed to the engine. Motion history is replayed
    /// on every wake, so without this the same `automotive` sample would send a second
    /// `vehicle_enter` for a drive that is already under way.
    private var consumedVehicleEvidenceAt: Date?
    /// Newest non-automotive observation already handed to the engine, for the same reason.
    private var consumedVehicleExitAt: Date?

    init(
        checkpointStore: any DetectionCheckpointStoring,
        motionHistory: any MotionHistoryProviding,
        locationCapture: (any BoundedLocationCapturing)? = nil,
        dateProvider: any DateProviding = SystemDateProvider(),
        traceRecorder: TraceRecorder? = nil,
        candidateStore: (any ParkingCandidateStoring)? = nil,
        candidateHistory: (any CandidateHistoryStoring)? = nil,
        candidateNotifier: (any CandidateNotifying)? = nil,
        analytics: any AnalyticsRecording = DisabledAnalyticsRecorder(),
        engine: ParkingDetectionEngine = ParkingDetectionEngine()
    ) {
        self.checkpointStore = checkpointStore
        self.motionHistory = motionHistory
        self.locationCapture = locationCapture
        self.dateProvider = dateProvider
        self.traceRecorder = traceRecorder
        self.candidateStore = candidateStore
        self.candidateHistory = candidateHistory
        self.candidateNotifier = candidateNotifier
        self.analytics = analytics
        self.engine = engine
    }

    func currentSnapshot() -> RehydrationSnapshot {
        snapshot
    }

    var isDrivingSessionOpen: Bool {
        snapshot.drivingSessionStartedAt != nil
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
            break
        case let .failed(failure):
            // The engine is *not* seeded from a failure. Overwriting here would hide the
            // damage and make a real data-loss bug look like a fresh install.
            AppLog.detection.error("checkpoint load failed: \(failure.diagnosticDescription, privacy: .public)")
        }

        if restored?.state == .driving || restored?.state == .drivingCandidate {
            snapshot.drivingSessionResumedFromCheckpoint = true
            snapshot.drivingSessionStartedAt = restored?.stateEnteredAt
        }
        await apply(
            engine.restore(
                restored,
                pendingCandidate: candidateStore?.load(),
                // A failed load is not an absent one: seeding here would hide the damage
                // and make a real data-loss bug look like a fresh install.
                seedIfAbsent: {
                    if case .failed = load { return false }
                    return true
                }(),
                now: now
            ),
            now: now
        )

        // Anchored on the *restored* checkpoint only. A checkpoint seeded a moment ago
        // would collapse the window to zero and skip the replay entirely.
        await reconstructMotionHistory(now: now, anchor: restored?.latestTimestamp)
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
        let effects = await engine.noteSignificantChange(at: sample.timestamp)
        guard !effects.isEmpty else {
            snapshot.supersededLocationDropCount += 1
            return
        }

        snapshot.lastLocationAt = sample.timestamp
        snapshot.lastLocationAccuracy = sample.horizontalAccuracy
        await apply(effects, now: now)

        await reconstructMotionHistory(now: now, anchor: snapshot.currentCheckpoint?.latestTimestamp)
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
    /// Everything time-dependent is decided by the engine's own windows rather than on a
    /// timer: at ~1 Hz the fix stream is itself the clock, and `evaluateDrivingTimeouts()`
    /// only has to cover the case where fixes stop arriving.
    func handleDrivingFix(_ fix: LocationFix) async {
        let now = dateProvider.now
        guard await engine.snapshot().driving != nil else { return }
        recordTrace { $0.record(fix: fix) }
        await apply(engine.handle(.location(fix), now: now), now: now)
    }

    /// Watchdog entry point for the case the fix stream goes silent — a tunnel, a denied
    /// authorization, a Core Location stall. Without it a session could stay open with no
    /// fixes to close it.
    func evaluateDrivingTimeouts() async {
        let now = dateProvider.now
        await apply(engine.handle(.timerTick(at: now)), now: now)
        await applySilenceBound(now: now)
        // The history in hand may already answer a transition either of the two just
        // opened; §3a's window is lazy, not a reason to be late.
        let samples = snapshot.motionSamples
        await applyConfirmationSignals(
            samples: samples,
            vehicle: MotionEvidenceReader.latestVehicleEvidence(in: samples),
            now: now
        )
        await releaseCaptureIfIdle()
    }

    func handleCaptureAuthorizationLost() async {
        snapshot.captureFailure = "authorization denied for live updates"
        await endDrivingSession(reason: .authorizationLost)
    }

    func handleCaptureFailure(_ description: String) async {
        snapshot.captureFailure = description
        await endDrivingSession(reason: .captureFailed)
    }

    /// Smart Detection was switched off. The session must not outlive the opt-in.
    func stopDrivingSessionForOptOut() async {
        await endDrivingSession(reason: .smartDetectionDisabled)
    }

    private func endDrivingSession(reason: DrivingSessionEndReason) async {
        let now = dateProvider.now
        await apply(engine.endDrivingSession(reason: reason, now: now), now: now)
        // A mismatch between our bookkeeping and Core Location's must always resolve
        // towards *off*, never towards a session nobody owns.
        await releaseCaptureIfIdle()
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

    /// The user answered (docs/05 §3a: `CANDIDATE_PENDING → PARKED` or `→ IDLE`).
    ///
    /// Called from `CandidateModel` through `DetectionRuntime`, because the screen and the
    /// lock screen are where the answer arrives and the checkpoint is what has to remember
    /// it across process death.
    func resolveCandidate(_ outcome: CandidateOutcome) async {
        let now = dateProvider.now
        let event: DetectionEvent = outcome == .confirmed
            ? .userConfirmedParking(at: now)
            : .userRejectedParking(at: now)
        await apply(engine.handle(event), now: now)
    }

    // MARK: - The car link (docs/05 §3a "The car link")

    /// The adapter's only entry for a car link.
    ///
    /// **The link is an optional signal.** Every §3a transition still stands on motion and
    /// location alone, and this method existing changes nothing when it is never called —
    /// which is the ordinary case on iOS, where the audio route is all that is reachable
    /// (see `CarLinkMonitor`).
    func handleCarLink(_ state: CarLinkObservation) async {
        let now = dateProvider.now
        snapshot.carLinkFailure = state.failure
        let observed = state.connected
        let previous = snapshot.connectedCarLinks
        guard observed != previous else { return }
        snapshot.connectedCarLinks = observed

        for kind in observed.subtracting(previous).sorted(by: { $0.rawValue < $1.rawValue }) {
            await apply(engine.handle(.carLinkConnected(at: now, kind: kind)), now: now)
        }
        for kind in previous.subtracting(observed).sorted(by: { $0.rawValue < $1.rawValue }) {
            await apply(engine.handle(.carLinkDisconnected(at: now, kind: kind)), now: now)
        }
        await releaseCaptureIfIdle()
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
            let now = dateProvider.now
            guard await engine.snapshot().driving == nil else { return }
            consumedVehicleEvidenceAt = now
            await apply(engine.handle(.vehicleEnter(at: now, confidence: .high)), now: now)
        }

        func stopDrivingSessionForFieldTest() async {
            await endDrivingSession(reason: .fieldTestStopped)
        }

        /// Drives the **real** state machine from `IDLE` to a candidate, in one call.
        ///
        /// A car cannot be parked on demand in a simulator or in a meeting, so this feeds
        /// the §2 events a drive would have produced — `vehicle_enter`, a promotion past
        /// the 90 s bar, `vehicle_exit`, then the confirming signal — straight into
        /// `ParkingDetectionEngine`. **No gate is bypassed**: §3a's table, §6's rule, §8's
        /// weights, §9's buckets and the `low`-posts-nothing rule all apply exactly as
        /// they do for a real trip, which is why `walking` is the only parameter — it is
        /// the difference between a `medium` that notifies and a `low` that is recorded in
        /// silence.
        ///
        /// DEV configuration only: `PK_DEV` is defined in `Config/Dev.xcconfig` and
        /// nowhere else, so none of this is compiled into STAGING or PROD.
        func injectCandidateForFieldTest(walking: Bool) async {
            let now = dateProvider.now
            let start = now.addingTimeInterval(-15 * 60)
            // From `IDLE`, so the injected trip is a trip of its own rather than a
            // continuation of whatever the device happened to be doing. Both routes back
            // are real §3a rows — a rejection and a session end — so re-running the hook
            // never reaches a state the engine could not have reached on its own.
            if await engine.state == .candidatePending {
                await apply(engine.handle(.userRejectedParking(at: start)), now: start)
            }
            await apply(engine.endDrivingSession(reason: .fieldTestStopped, now: start), now: start)
            consumedVehicleEvidenceAt = now
            consumedVehicleExitAt = now
            await apply(engine.handle(.vehicleEnter(at: start, confidence: .high), now: start), now: start)
            // Past §3a's 90 s bar, which is what promotes the session to `DRIVING`.
            await apply(engine.handle(.timerTick(at: now), now: now), now: now)
            await apply(engine.handle(.vehicleExit(at: now, confidence: .high), now: now), now: now)
            let signal: DetectionEvent = walking
                ? .walkingEnter(at: now, confidence: .high)
                : .stationaryEnter(at: now, confidence: .high)
            await apply(engine.handle(signal, now: now), now: now)
            await releaseCaptureIfIdle()
        }
    #endif

    // MARK: - Normalizing Core Motion into §2 events

    /// Turns replayed motion history into the §2 events the engine understands.
    ///
    /// Evaluated on every wake, so the same history is seen repeatedly. Two watermarks
    /// keep that idempotent — one for the newest vehicle observation already sent, one for
    /// the newest observation that ended it — because a replayed sample must not open a
    /// second session or close the same one twice.
    private func evaluateMotionEvidence(now: Date) async {
        let samples = snapshot.motionSamples

        // Unconditional, and before any session decision. docs/05 §9: recording is not
        // gated on vehicle evidence — a walk, a subway ride and a stationary transition are
        // exactly the negative cases §17 needs, and gating on the bounded driving session
        // is what dropped them. It also has to happen before the walking transition can end
        // a session, or the one event that explains the ending would be missing.
        recordTrace { $0.record(motionSamples: samples) }

        let vehicle = MotionEvidenceReader.latestVehicleEvidence(in: samples)
        if let vehicle {
            snapshot.lastVehicleEvidenceAt = vehicle.timestamp
            snapshot.lastVehicleEvidenceConfidence = vehicle.confidence
        }

        if let vehicle,
           vehicle.timestamp > (consumedVehicleEvidenceAt ?? .distantPast),
           // Confidence is deliberately not gated. docs/05 §7 puts the rigor in the
           // promotion bar, and a session opened on a weak signal costs at most one
           // `drivingCandidateWindow` — while a missed one costs the whole trip.
           now.timeIntervalSince(vehicle.timestamp) <= DrivingConfirmationPolicy.vehicleEvidenceMaxAge,
           // Already walked away from the car: replaying that history must not reopen a
           // session for a drive that is over.
           MotionEvidenceReader.latestWalkingEvidence(in: samples, after: vehicle.timestamp) == nil {
            consumedVehicleEvidenceAt = vehicle.timestamp
            await apply(
                engine.handle(.vehicleEnter(at: vehicle.timestamp, confidence: vehicle.confidence), now: now),
                now: now
            )
        }

        // Lets the lazily-evaluated §3a windows catch up before any exit is derived: a
        // session that cleared the 90 s bar while the process was dead has to be `DRIVING`
        // before the walk that ended it is applied, or the drive leaves for `IDLE` instead
        // of deciding whether it parked.
        await apply(engine.handle(.timerTick(at: now)), now: now)

        await deriveVehicleExit(samples: samples, vehicle: vehicle, now: now)
        // Before the confirming signals, not after: a session the silence bound just
        // closed has entered `PARKING_TRANSITION`, and the walk or the stillness that
        // answers it is already in this same history. Waiting for the next wake would
        // cost minutes of a 300 s window for no reason.
        await applySilenceBound(now: now)
        await applyConfirmationSignals(samples: samples, vehicle: vehicle, now: now)
        await releaseCaptureIfIdle()
    }

    /// The `vehicle_exit` edge iOS has to invent.
    ///
    /// Android receives IN_VEHICLE EXIT from the Transition API. Core Motion has no such
    /// thing: it answers "what is the device doing", and the drive ends by a newer sample
    /// saying something else. That newer sample is the exit, and the reason it carries —
    /// `walkingDetected` — is what the field diagnostics need in order to tell a parking
    /// transition apart from a session that merely ran out of evidence.
    private func deriveVehicleExit(samples: [MotionSample], vehicle: MotionSample?, now: Date) async {
        guard await engine.snapshot().isVehicleActive else { return }
        let reference = vehicle?.timestamp ?? consumedVehicleEvidenceAt ?? .distantPast
        guard let walk = MotionEvidenceReader.latestWalkingEvidence(in: samples, after: reference),
              walk.timestamp > (consumedVehicleExitAt ?? .distantPast)
        else { return }
        consumedVehicleExitAt = walk.timestamp
        await apply(engine.endDrivingSession(reason: .walkingDetected, now: now), now: now)
        await apply(engine.handle(.walkingEnter(at: walk.timestamp, confidence: walk.confidence), now: now), now: now)
    }

    /// The §3a confirming signals, forwarded only while the engine is in
    /// `PARKING_TRANSITION` — the one state that is waiting for them.
    private func applyConfirmationSignals(samples: [MotionSample], vehicle: MotionSample?, now: Date) async {
        guard await engine.state == .parkingTransition else { return }
        let reference = vehicle?.timestamp ?? consumedVehicleEvidenceAt ?? .distantPast
        if let walk = MotionEvidenceReader.latestWalkingEvidence(in: samples, after: reference) {
            await apply(engine.handle(.walkingEnter(at: walk.timestamp, confidence: walk.confidence), now: now), now: now)
            return
        }
        if let still = MotionEvidenceReader.latestStationaryEvidence(in: samples, after: reference) {
            await apply(
                engine.handle(.stationaryEnter(at: still.timestamp, confidence: still.confidence), now: now),
                now: now
            )
        }
    }

    /// `DrivingSessionTimeoutPolicy.silenceReason`, which is this adapter's alone.
    ///
    /// Core Motion going quiet is how iOS learns a drive is over when no walk is ever
    /// reported — an underground car park with the phone in a bag. It is expressed as the
    /// session end it is, and not as a `vehicle_exit`, because the reason is what the field
    /// diagnostics read.
    private func applySilenceBound(now: Date) async {
        guard let evidence = await engine.snapshot().driving,
              let reason = DrivingSessionTimeoutPolicy.silenceReason(for: evidence, now: now)
        else { return }
        await apply(engine.endDrivingSession(reason: reason, now: now), now: now)
        await releaseCaptureIfIdle()
    }

    // MARK: - Performing the effects (docs/05 §15)

    private func apply(_ effects: [DetectionEffect], now: Date) async {
        for effect in effects {
            await perform(effect, now: now)
        }
        // Unconditional, including for an empty list: a fix the §6 gate *rejected* produces
        // no effect at all, and the rejection reason is precisely what the field report
        // needs in order to tell a bad GPS environment from a badly-set threshold.
        await refreshDrivingDiagnostics()
    }

    private func perform(_ effect: DetectionEffect, now: Date) async {
        switch effect {
        case .startBoundedLocationCapture:
            await beginCapture(now: now)

        case .stopLocationCapture:
            await locationCapture?.stop()
            snapshot.isCapturingDrivingLocation = await locationCapture?.isActive() ?? false
            snapshot.captureHealth = await locationCapture?.health() ?? .none
            snapshot.drivingSessionStartedAt = nil

        case let .persistCheckpoint(checkpoint):
            persist(checkpoint)

        case let .drivingConfirmed(at):
            snapshot.drivingConfirmedAt = at
            AppLog.detection.notice("driving confirmed at wake")

        case let .sessionEnded(reason, at):
            snapshot.lastDrivingSessionEndReason = reason
            snapshot.lastDrivingSessionEndedAt = at
            AppLog.detection.notice("bounded driving session ended: \(reason.rawValue, privacy: .public)")

        case let .createCandidate(candidate):
            saveCandidate(candidate)

        case let .issueCandidateNotification(candidate):
            await candidateNotifier?.post(candidate)

        case let .withdrawCandidate(id):
            // §7b's `응답 없음`. Read before the clear, because the raised-at time the row
            // shows lives only in the file this is about to delete. `append` is idempotent
            // by candidate id, so a screen that retired the same candidate a moment
            // earlier does not produce a second row.
            if let candidate = candidateStore?.load(), candidate.id == id {
                candidateHistory?.append(CandidateHistoryEntry(candidate: candidate, outcome: .expired))
            }
            try? candidateStore?.clear()
            await candidateNotifier?.withdraw(candidateId: id)

        case .candidateRuleUnmet:
            // §6's rule was not met. An ordinary outcome, counted rather than logged as a
            // failure — but counted, because a rising number here with no candidates is
            // how a mis-tuned transition window announces itself.
            snapshot.candidateRuleUnmetCount += 1
        }
    }

    private func saveCandidate(_ candidate: ParkingCandidate) {
        do {
            try candidateStore?.save(candidate)
            snapshot.candidateStoreFailure = nil
        } catch {
            snapshot.candidateStoreFailure = String(describing: error)
            AppLog.detection.error("candidate save failed: \(String(describing: error), privacy: .public)")
        }
        snapshot.candidateCreatedCount += 1
        snapshot.lastCandidateConfidence = candidate.confidenceBucket
        // docs/17 §2. Reported for every candidate, including the `low` one nobody sees:
        // the created/rejected ratio is only readable if both halves are counted.
        analytics.record(.parkingCandidateCreated(candidate.analyticsProperties))
        AppLog.detection.notice(
            "candidate created, confidence=\(candidate.confidenceBucket.rawValue, privacy: .public)"
        )
    }

    private func beginCapture(now: Date) async {
        // Stamped before anything can fail, so a capture the engine asked for and never got
        // is visible as a request with no matching `captureHealth.startedAt`.
        snapshot.captureRequestedAt = now
        let wasOpen = snapshot.drivingSessionStartedAt != nil
        snapshot.drivingSessionStartedAt = await engine.snapshot().driving?.startedAt ?? now
        if !wasOpen {
            snapshot.drivingSessionCount += 1
            snapshot.drivingConfirmedAt = nil
            snapshot.captureFailure = nil
        }
        await locationCapture?.start()
        snapshot.isCapturingDrivingLocation = await locationCapture?.isActive() ?? false
        snapshot.captureHealth = await locationCapture?.health() ?? .none
    }

    /// Core Location must never be left running for a session the engine no longer has.
    private func releaseCaptureIfIdle() async {
        guard await engine.snapshot().driving == nil else { return }
        await locationCapture?.stop()
        snapshot.isCapturingDrivingLocation = await locationCapture?.isActive() ?? false
        snapshot.captureHealth = await locationCapture?.health() ?? .none
        snapshot.drivingSessionStartedAt = nil
    }

    /// Copies the §7 counters out of the engine's evidence so the field checklist can read
    /// them. They are diagnostics, never inputs.
    private func refreshDrivingDiagnostics() async {
        let state = await engine.snapshot()
        snapshot.currentCheckpoint = state.checkpoint
        snapshot.parkingTransitionEnteredAt = state.parkingTransitionEnteredAt
        snapshot.connectedCarLinks = state.connectedCarLinks
        snapshot.reliableLocationUpdateCount = state.reliableLocationUpdateCount
        snapshot.reliableLocationRejectCount = state.reliableLocationRejectCount
        snapshot.lastReliableLocationRejection = state.lastReliableLocationRejection
        guard let evidence = state.driving else { return }
        snapshot.drivingFixCount = evidence.fixCount
        snapshot.drivingMovingSampleCount = evidence.movingSampleCount
        snapshot.drivingSpeedAvailableCount = evidence.speedAvailableCount
        snapshot.drivingSpeedMissingCount = evidence.speedMissingCount
        snapshot.drivingDerivedMovingSampleCount = evidence.derivedMovingSampleCount
        snapshot.movementEvidenceRejectReason = evidence.movementEvidenceRejection
        snapshot.drivingOutlierDropCount = evidence.outlierCount
        snapshot.drivingDistanceMeters = evidence.distanceMeters
        snapshot.drivingDistanceNoiseFloorRejectCount = evidence.distanceNoiseFloorRejectCount
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

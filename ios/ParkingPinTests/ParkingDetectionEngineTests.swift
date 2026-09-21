import Foundation
import Testing
@testable import ParkingPin

/// `docs/05_PARKING_DETECTION_ENGINE.md` §3a, row by row.
///
/// Driven through `ParkingDetectionEngine` directly rather than through the coordinator,
/// because the table is about states and windows and nothing else — the platform mapping
/// is `BackgroundCoordinatorTests`' subject, and the parity fixtures are
/// `ParityFixtureTests`'.
@Suite("§3a transition table")
struct ParkingDetectionEngineTests {
    private let t0 = TestTime.offset(0)

    private func at(_ seconds: TimeInterval) -> Date {
        t0.addingTimeInterval(seconds)
    }

    /// `IDLE → DRIVING_CANDIDATE → DRIVING`: enter the vehicle, hold it past the 90 s bar.
    private func drivingEngine() async -> ParkingDetectionEngine {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.vehicleEnter(at: t0))
        _ = await engine.handle(.timerTick(at: at(DrivingConfirmationPolicy.minimumVehicleDuration)))
        return engine
    }

    private func effectStates(_ effects: [DetectionEffect]) -> [DetectionState] {
        effects.compactMap {
            if case let .persistCheckpoint(checkpoint) = $0 { return checkpoint.state }
            return nil
        }
    }

    /// A fix at the fixture origin. Coordinates never leave this file.
    private func fix(at date: Date, accuracy: Double) -> LocationFix {
        LocationFix(
            timestamp: date,
            latitude: 37.5,
            longitude: 127.0,
            horizontalAccuracy: accuracy,
            speed: nil
        )
    }

    private func candidates(_ effects: [DetectionEffect]) -> [ParkingCandidate] {
        effects.compactMap {
            if case let .createCandidate(candidate) = $0 { return candidate }
            return nil
        }
    }

    // MARK: - Rows out of IDLE and DRIVING_CANDIDATE

    @Test("IDLE → DRIVING_CANDIDATE on vehicle_enter, and the capture opens with it")
    func vehicleEnterOpensCandidate() async {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)

        // Act
        let effects = await engine.handle(.vehicleEnter(at: t0))

        // Assert
        #expect(await engine.state == .drivingCandidate)
        #expect(effects.contains(.startBoundedLocationCapture))
    }

    @Test("DRIVING_CANDIDATE → DRIVING once vehicle activity is sustained past 90 s")
    func sustainedVehicleActivityPromotes() async {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.vehicleEnter(at: t0))

        // Act — one second short, then on the bar.
        _ = await engine.handle(.timerTick(at: at(89)))
        #expect(await engine.state == .drivingCandidate)
        let effects = await engine.handle(.timerTick(at: at(90)))

        // Assert
        #expect(await engine.state == .driving)
        #expect(effects.contains(.drivingConfirmed(at: at(90))))
    }

    /// §3a's "Movement evidence does not gate promotion": the 2026-09-19 14:26 drive
    /// carries zero location events and must still be detected.
    @Test("A drive with no location fix at all still promotes")
    func promotionDoesNotNeedMovementEvidence() async {
        // Arrange / Act
        let engine = await drivingEngine()

        // Assert
        #expect(await engine.state == .driving)
        #expect(await engine.snapshot().driving?.fixCount == 0)
    }

    @Test("DRIVING_CANDIDATE → IDLE on vehicle_exit, with nothing persisted or shown")
    func vehicleExitAbandonsCandidate() async {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.vehicleEnter(at: t0))

        // Act — §12's short-trip guard: a ride below the bar produces no candidate at all.
        let effects = await engine.handle(.vehicleExit(at: at(30)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(candidates(effects).isEmpty)
        #expect(effects.contains(.stopLocationCapture))
    }

    @Test("DRIVING_CANDIDATE → IDLE when drivingCandidateWindow passes with no promotion")
    func unpromotedCandidateWindowExpires() async {
        // Arrange — a car link opens the state; sitting in the car is not vehicle activity.
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))
        #expect(await engine.state == .drivingCandidate)

        // Act
        let window = DrivingConfirmationPolicy.drivingCandidateWindow
        _ = await engine.handle(.timerTick(at: at(window - 1)))
        #expect(await engine.state == .drivingCandidate)
        let effects = await engine.handle(.timerTick(at: at(window)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(effects.contains(.stopLocationCapture))
    }

    // MARK: - Rows out of DRIVING

    @Test("DRIVING → PARKING_TRANSITION on vehicle_exit, silently")
    func vehicleExitEntersTransition() async {
        // Arrange
        let engine = await drivingEngine()

        // Act
        let effects = await engine.handle(.vehicleExit(at: at(300)))

        // Assert — nothing is persisted beyond the checkpoint and nothing is shown.
        #expect(await engine.state == .parkingTransition)
        #expect(candidates(effects).isEmpty)
        #expect(!effects.contains { if case .issueCandidateNotification = $0 { true } else { false } })
    }

    @Test("DRIVING → PARKING_TRANSITION when movement evidence goes quiet for movementIdleWindow")
    func movementIdleEntersTransition() async {
        // Arrange — one fix fast enough to count as movement, then silence.
        let engine = await drivingEngine()
        _ = await engine.handle(.location(TestGeo.fix(at: at(100), metersNorth: 0, accuracy: 8, speed: 9)))

        // Act
        let idle = ParkingTransitionPolicy.movementIdleWindow
        _ = await engine.handle(.timerTick(at: at(100 + idle - 1)))
        #expect(await engine.state == .driving)
        _ = await engine.handle(.timerTick(at: at(100 + idle)))

        // Assert
        #expect(await engine.state == .parkingTransition)
    }

    /// A session that has never moved is not idle: there is no movement to have stopped,
    /// and treating silence there as a parking would open a candidate for a car nobody drove.
    @Test("A session that never moved does not fall into PARKING_TRANSITION")
    func neverMovedIsNotMovementIdle() async {
        // Arrange
        let engine = await drivingEngine()

        // Act
        _ = await engine.handle(.timerTick(at: at(90 + ParkingTransitionPolicy.movementIdleWindow + 60)))

        // Assert
        #expect(await engine.state == .driving)
    }

    // MARK: - Rows out of PARKING_TRANSITION

    // MARK: - §5: the fix a candidate inherits

    /// The 2026-09-20 Android trace, replayed against this engine: the last fix good enough
    /// to be admitted came at noon, the car was driven and parked five hours later, and
    /// nothing underground was ever good enough to replace it.
    @Test("A candidate refuses the fix left behind by an earlier drive")
    func candidateRefusesAFixFromBeforeTheDrive() async throws {
        // Arrange — drive one, above ground, leaves a good fix on the state.
        let engine = await drivingEngine()
        _ = await engine.handle(.location(fix(at: at(600), accuracy: 17.7)))
        _ = await engine.handle(.vehicleExit(at: at(900)))
        let first = try #require(candidates(await engine.handle(.walkingEnter(at: at(930)))).first)
        #expect(first.lastReliableLocation != nil, "the control: drive one did keep its fix")
        _ = await engine.handle(.userRejectedParking(at: at(960)))

        // Drive two, an hour later and entirely underground: not one fix arrives.
        let second = at(3600 + 960)
        _ = await engine.handle(.vehicleEnter(at: second))
        _ = await engine.handle(
            .timerTick(at: second.addingTimeInterval(DrivingConfirmationPolicy.minimumVehicleDuration))
        )
        _ = await engine.handle(.vehicleExit(at: second.addingTimeInterval(1200)))

        // Act
        let effects = await engine.handle(.walkingEnter(at: second.addingTimeInterval(1230)))

        // Assert — a candidate, and no coordinate on it. Drive one's fix is where the car
        // was an hour ago; a wrong coordinate is worse than none, because the confirmation
        // screen draws it on a map with its accuracy printed beside it.
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.lastReliableLocation == nil)
    }

    /// A ninety-minute motorway run whose only good fix came at minute two. Being on the
    /// motorway then says nothing about where the car stopped at minute ninety.
    @Test("A fix from inside the drive but older than the window is refused")
    func candidateRefusesAFixOlderThanTheWindow() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.location(fix(at: at(120), accuracy: 9)))
        _ = await engine.handle(.vehicleExit(at: at(90 * 60)))

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(90 * 60 + 30)))

        // Assert
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.lastReliableLocation == nil)
    }

    @Test("A fix taken during the drive is inherited")
    func candidateInheritsAFixFromTheDrive() async throws {
        // Arrange — the control: same shape, fix taken after the wheels turned and inside
        // the window.
        let engine = await drivingEngine()
        _ = await engine.handle(.location(fix(at: at(1140), accuracy: 12)))
        _ = await engine.handle(.vehicleExit(at: at(1200)))

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(1230)))

        // Assert
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.lastReliableLocation?.horizontalAccuracy == 12)
    }

    @Test("PARKING_TRANSITION → CANDIDATE_PENDING on walking_enter inside the window")
    func walkingConfirmsCandidate() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(330)))

        // Assert
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.walkingAfterVehicle))
        #expect(effects.contains(.issueCandidateNotification(candidate)))
    }

    @Test("PARKING_TRANSITION → CANDIDATE_PENDING on stationary_enter inside the window")
    func stationaryConfirmsCandidate() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))

        // Act
        let effects = await engine.handle(.stationaryEnter(at: at(330)))

        // Assert
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.stationaryAfterVehicle))
    }

    /// §3a's red light: the whole reason `PARKING_TRANSITION` is a state of its own.
    @Test("PARKING_TRANSITION → DRIVING when the vehicle comes back inside the window")
    func redLightReturnsToDriving() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))

        // Act
        let effects = await engine.handle(.vehicleEnter(at: at(400)))

        // Assert — restored as DRIVING, not DRIVING_CANDIDATE: the session was already
        // confirmed, and nothing was ever shown to take back.
        #expect(await engine.state == .driving)
        #expect(candidates(effects).isEmpty)
        #expect(effects.contains(.startBoundedLocationCapture))
    }

    @Test("PARKING_TRANSITION → IDLE when transitionWindow closes with no confirming signal")
    func transitionWindowClosesToIdle() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))

        // Act
        let window = ParkingTransitionPolicy.transitionWindow
        let effects = await engine.handle(.timerTick(at: at(300 + window)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(candidates(effects).isEmpty)
        #expect(effects.contains(.candidateRuleUnmet))
    }

    @Test("A walk that arrives after the window confirms nothing")
    func lateWalkConfirmsNothing() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))

        // Act
        let effects = await engine.handle(
            .walkingEnter(at: at(300 + ParkingTransitionPolicy.transitionWindow + 1))
        )

        // Assert
        #expect(await engine.state == .idle)
        #expect(candidates(effects).isEmpty)
    }

    // MARK: - Rows out of CANDIDATE_PENDING

    @Test("CANDIDATE_PENDING → PARKED when the user confirms")
    func userConfirmationParks() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        _ = await engine.handle(.walkingEnter(at: at(330)))

        // Act
        let effects = await engine.handle(.userConfirmedParking(at: at(360)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(effectStates(effects).last == .parked)
        #expect(await engine.snapshot().checkpoint.candidateId == nil)
    }

    @Test("CANDIDATE_PENDING → IDLE when the user rejects")
    func userRejectionReturnsToIdle() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        _ = await engine.handle(.walkingEnter(at: at(330)))

        // Act
        _ = await engine.handle(.userRejectedParking(at: at(360)))

        // Assert
        #expect(await engine.state == .idle)
    }

    @Test("CANDIDATE_PENDING → IDLE at the 45-minute expiry, withdrawing the notification")
    func expiryWithdrawsCandidate() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        let created = try #require(candidates(await engine.handle(.walkingEnter(at: at(330)))).first)

        // Act
        let effects = await engine.handle(.timerTick(at: at(330 + ParkingCandidatePolicy.expiry)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(effects.contains(.withdrawCandidate(id: created.id)))
        #expect(await engine.snapshot().checkpoint.candidateId == nil)
    }

    // MARK: - One candidate per travel session (§12)

    @Test("A session that already produced a candidate cannot produce a second")
    func oneCandidatePerTravelSession() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        let first = candidates(await engine.handle(.walkingEnter(at: at(330))))

        // Act — more confirming signals arrive for the same trip.
        let second = candidates(await engine.handle(.stationaryEnter(at: at(360))))
        let third = candidates(await engine.handle(.walkingEnter(at: at(400))))

        // Assert
        #expect(first.count == 1)
        #expect(second.isEmpty)
        #expect(third.isEmpty)
        #expect(await engine.state == .candidatePending)
    }

    /// The trip has to pass through `IDLE` first — which rejection is.
    @Test("A new trip after a rejection may produce its own candidate")
    func newTripMayProduceItsOwnCandidate() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        _ = await engine.handle(.walkingEnter(at: at(330)))
        _ = await engine.handle(.userRejectedParking(at: at(360)))

        // Act — a second trip.
        _ = await engine.handle(.vehicleEnter(at: at(400)))
        _ = await engine.handle(.timerTick(at: at(500)))
        _ = await engine.handle(.vehicleExit(at: at(600)))
        let effects = await engine.handle(.walkingEnter(at: at(630)))

        // Assert
        #expect(candidates(effects).count == 1)
        #expect(await engine.state == .candidatePending)
    }

    /// §3a "Leaving a pending candidate behind". Without this row the engine sat in
    /// `CANDIDATE_PENDING` for up to forty-five minutes with detection dead, which is the
    /// whole cost of ignoring one prompt.
    @Test("Driving again while a prompt is unanswered starts a new session")
    func drivingAgainWhilePendingOpensANewSession() async {
        // Arrange — a candidate is pending and the user never answered it.
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        _ = await engine.handle(.walkingEnter(at: at(330)))
        #expect(await engine.state == .candidatePending)

        // Act
        _ = await engine.handle(.vehicleEnter(at: at(900)))

        // Assert
        #expect(await engine.state == .drivingCandidate)
    }

    /// The candidate is not retired at `vehicle_enter`: that signal is noisy, and §10a
    /// supersedes only when the new session produces a candidate of its own.
    @Test("The unanswered candidate survives the new session until it is superseded")
    func pendingCandidateSurvivesUntilSuperseded() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        _ = await engine.handle(.walkingEnter(at: at(330)))

        // Act — a new journey begins, then earns its own candidate.
        let onEnter = await engine.handle(.vehicleEnter(at: at(900)))
        _ = await engine.handle(.timerTick(at: at(1000)))
        _ = await engine.handle(.vehicleExit(at: at(1200)))
        let onSecond = candidates(await engine.handle(.walkingEnter(at: at(1230))))

        // Assert — nothing was withdrawn when the drive began, and the second trip
        // produced a candidate of its own.
        let withdrew = onEnter.contains { effect in
            if case .withdrawCandidate = effect { true } else { false }
        }
        #expect(!withdrew)
        #expect(onSecond.count == 1)
    }
}

/// The edge of the §3a table that only the iOS adapter can get wrong: Core Motion has no
/// IN_VEHICLE EXIT, so the drive ends by evidence going quiet, and the signal that answers
/// the transition is usually already in the same replayed history.
@Suite("Deriving the exit iOS is not given")
struct DerivedVehicleExitTests {
    private let reference = TestTime.offset(0)

    private func automotive(at offset: TimeInterval) -> MotionSample {
        MotionSample(
            timestamp: reference.addingTimeInterval(offset),
            automotive: true,
            stationary: true,
            confidence: .high
        )
    }

    /// docs/05 §13's underground pattern end to end: the car goes into a basement, the
    /// phone stays in a bag, Core Motion never reports a walk, and the only two facts are
    /// that automotive evidence stopped and the device went still.
    @Test("Silence ends the drive and the stillness in the same history confirms it, on one wake")
    func silenceThenStillnessProducesACandidateOnOneWake() async throws {
        // Arrange — a drive that confirmed, then nothing for longer than the silence bound.
        let checkpoints = StubCheckpointStore(loadResult: .absent)
        let candidates = StubParkingCandidateStore()
        let clock = MutableDateProvider(reference)
        let motion = StubMotionHistoryProvider(result: .success([automotive(at: -30)]))
        let coordinator = BackgroundCoordinator(
            checkpointStore: checkpoints,
            motionHistory: motion,
            locationCapture: StubBoundedLocationCapture(),
            dateProvider: clock,
            candidateStore: candidates,
            candidateNotifier: StubCandidateNotifier()
        )
        await coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act — one wake, well past the silence bound, whose history carries the stillness.
        let silence = DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout
        motion.setResult(.success([
            automotive(at: -30),
            MotionSample(timestamp: reference.addingTimeInterval(silence - 60), stationary: true, confidence: .high)
        ]))
        clock.advance(by: silence)
        await coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: clock.now, horizontalAccuracy: 20)
        )
        let snapshot = await coordinator.currentSnapshot()

        // Assert — the candidate exists now, not one wake later.
        let stored = try #require(candidates.load())
        #expect(stored.reasonCodes.contains(.stationaryAfterVehicle))
        #expect(snapshot.lastDrivingSessionEndReason == .vehicleEvidenceExpired)
        #expect(snapshot.currentCheckpoint?.state == .candidatePending)
        #expect(!snapshot.isCapturingDrivingLocation)
    }
}

/// What a process that died mid-trip is handed back (docs/05 §16 `restore`).
@Suite("Restoring a checkpoint")
struct DetectionEngineRestoreTests {
    private let t0 = TestTime.offset(0)

    /// The checkpoint and the candidate file are two files and can disagree after a crash.
    /// §10's 45 minutes bound the state either way — otherwise every later drive is
    /// invisible because nothing can leave `CANDIDATE_PENDING`.
    @Test("A CANDIDATE_PENDING checkpoint whose candidate file is gone still expires")
    func pendingWithoutACandidateFileStillExpires() async {
        // Arrange
        let engine = ParkingDetectionEngine()
        let checkpoint = DetectionCheckpoint(
            state: .candidatePending,
            stateEnteredAt: t0,
            candidateId: UUID()
        )

        // Act — restored inside the window, then again past it.
        _ = await engine.restore(checkpoint, pendingCandidate: nil, seedIfAbsent: false, now: t0.addingTimeInterval(60))
        #expect(await engine.state == .candidatePending)
        _ = await engine.handle(.timerTick(at: t0.addingTimeInterval(ParkingCandidatePolicy.expiry)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(await engine.snapshot().checkpoint.candidateId == nil)
    }

    /// An expired candidate must not cost the drive that is also in the checkpoint.
    @Test("An expired candidate is retired without losing a restored driving session")
    func expiredCandidateDoesNotCancelARestoredDrive() async {
        // Arrange — a checkpoint that says DRIVING, beside a candidate file left behind.
        let engine = ParkingDetectionEngine()
        let stale = ParkingCandidate(
            id: UUID(),
            detectedAt: t0.addingTimeInterval(-ParkingCandidatePolicy.expiry - 60),
            confidenceBucket: .medium,
            reasonCodes: [],
            lastReliableLocation: nil,
            expiresAt: t0.addingTimeInterval(-60),
            score: 70,
            driveDuration: nil,
            driveDistanceMeters: nil,
            accuracyBucket: nil
        )
        let checkpoint = DetectionCheckpoint(state: .driving, stateEnteredAt: t0, lastAutomotiveAt: t0)

        // Act
        let effects = await engine.restore(
            checkpoint,
            pendingCandidate: stale,
            seedIfAbsent: false,
            now: t0.addingTimeInterval(30)
        )

        // Assert
        #expect(effects.contains(.withdrawCandidate(id: stale.id)))
        #expect(await engine.state == .driving)
        #expect(effects.contains(.startBoundedLocationCapture))
    }
}

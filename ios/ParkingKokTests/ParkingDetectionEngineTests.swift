import Foundation
import Testing
@testable import ParkingKok

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

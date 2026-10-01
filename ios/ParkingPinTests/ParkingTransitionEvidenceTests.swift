import Foundation
import Testing
@testable import ParkingPin

/// docs/05_PARKING_DETECTION_ENGINE.md §3a's `PARKING_TRANSITION` rows and §8b's evidence
/// definitions — the ones the 2026-09-27 field-draft replay found the two engines reading
/// differently.
///
/// Every test drives the real engine with the fewest events that pin one rule. The rules
/// are cross-platform: Android's `ParkingDetectionEngineTest` holds the same statements, and
/// a divergence here is a divergence in the product, not in a test.
@Suite("§3a PARKING_TRANSITION rows and §8b evidence")
struct ParkingTransitionEvidenceTests {
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

    private func fix(
        _ seconds: TimeInterval,
        north: Double = 0,
        accuracy: Double = 8,
        speed: Double?
    ) -> DetectionEvent {
        .location(TestGeo.fix(at: at(seconds), metersNorth: north, accuracy: accuracy, speed: speed))
    }

    /// A drive that moved at t=100 and has been still since: `movementIdleWindow` puts it
    /// in `PARKING_TRANSITION` at t=280, which the tick at t=280 observes on time.
    private func idleTransitionEngine() async -> ParkingDetectionEngine {
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(.timerTick(at: at(100 + ParkingTransitionPolicy.movementIdleWindow)))
        return engine
    }

    private func upgrades(_ effects: [DetectionEffect]) -> [ParkingCandidate] {
        effects.compactMap {
            if case let .upgradeCandidate(candidate) = $0 {
                return candidate
            }
            return nil
        }
    }

    private func candidates(_ effects: [DetectionEffect]) -> [ParkingCandidate] {
        effects.compactMap {
            if case let .createCandidate(candidate) = $0 {
                return candidate
            }
            return nil
        }
    }

    // MARK: - §3a "location stop" (F1)

    @Test("A stopped fix inside a movementIdle transition confirms the parking")
    func stoppedFixConfirmsIdleTransition() async throws {
        // Arrange
        let engine = await idleTransitionEngine()
        #expect(await engine.state == .parkingTransition)

        // Act
        let effects = await engine.handle(fix(300, speed: 0.5))

        // Assert — §8b: no exit and no walk, so 25 + 10 (stopped) + 5 (280 s is twice §7's
        // 120 s) = 40, `low`. The twin Android test pins the same score (docs/05 §8b).
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.locationStopped))
        #expect(!candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(candidate.score == 40)
        #expect(candidate.confidenceBucket == .low)
    }

    @Test("A stopped fix after vehicle_exit confirms the parking")
    func stoppedFixConfirmsExitTransition() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(.vehicleExit(at: at(200)))

        // Act
        let effects = await engine.handle(fix(230, speed: 0.3))

        // Assert
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(candidate.reasonCodes.contains(.locationStopped))
    }

    /// Underground there is no Doppler speed, and silence is not stillness (§7).
    @Test("A fix with no speed is not a location stop")
    func speedlessFixIsNotALocationStop() async {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        _ = await engine.handle(fix(300, speed: nil))

        // Assert
        #expect(await engine.state == .parkingTransition)
    }

    @Test("A fix with an invalid accuracy confirms nothing")
    func invalidFixIsNotALocationStop() async {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        _ = await engine.handle(fix(300, accuracy: -1, speed: 0))

        // Assert
        #expect(await engine.state == .parkingTransition)
    }

    /// §3a location stop, condition 1: the drive's §5 gate must accept the fix. Android
    /// twin: `an outlier fix that reports a stop confirms nothing`.
    @Test("An outlier fix that reports a stop confirms nothing")
    func outlierStopConfirmsNothing() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 12))
        _ = await engine.handle(.vehicleExit(at: at(110)))

        // Act — 10 km in 10 s, reporting speed 0: a GPS jump on the way underground.
        let effects = await engine.handle(fix(120, north: 10_000, speed: 0))

        // Assert
        #expect(await engine.state == .parkingTransition)
        #expect(candidates(effects).isEmpty)
    }

    /// §3a location stop, condition 3: at or after the transition's entry. Android twin:
    /// `a stopped fix timestamped before the transition confirms nothing`.
    @Test("A stopped fix timestamped before the transition confirms nothing")
    func stopBeforeTheTransitionConfirmsNothing() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 12))
        _ = await engine.handle(.vehicleExit(at: at(190)))

        // Act — a fix from before the exit, delivered late.
        let effects = await engine.handle(fix(140, speed: 0))

        // Assert
        #expect(await engine.state == .parkingTransition)
        #expect(candidates(effects).isEmpty)
    }

    // MARK: - §3a "movement evidence returns" (F2, F07)

    @Test("A moving fix inside the transition returns to DRIVING and keeps the drive")
    func movingFixResumesTheSameDrive() async throws {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act — the light turns green.
        _ = await engine.handle(fix(350, north: 600, speed: 9))

        // Assert — the same drive, not a new one: its start and confirmation survive.
        #expect(await engine.state == .driving)
        #expect(await engine.snapshot().driving?.startedAt == t0)
        _ = await engine.handle(.vehicleExit(at: at(500)))
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(510)))).first)
        #expect(candidate.driveDuration == 500)
    }

    @Test("A speedless fix that clears §7's movement bar also returns to DRIVING")
    func derivedMovementResumesDriving() async {
        // Arrange — a stopped fix at 200 anchors the distance fallback without moving.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(fix(200, speed: 1))
        _ = await engine.handle(.timerTick(at: at(280)))
        #expect(await engine.state == .parkingTransition)

        // Act — 500 m in 90 s at 10 m accuracy: travel, by §7's fallback.
        _ = await engine.handle(fix(290, north: 500, accuracy: 10, speed: nil))

        // Assert
        #expect(await engine.state == .driving)
    }

    /// Android oscillated here: the resume kept a stale idle anchor and the next settle
    /// sent the drive straight back to `PARKING_TRANSITION`.
    @Test("vehicle_enter after a movementIdle transition gets a full idle window")
    func vehicleEnterResumeReanchorsTheIdleClock() async {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        _ = await engine.handle(.vehicleEnter(at: at(300)))

        // Assert
        #expect(await engine.state == .driving)
        #expect(await engine.snapshot().driving?.startedAt == t0)
        _ = await engine.handle(.timerTick(at: at(300 + ParkingTransitionPolicy.movementIdleWindow - 1)))
        #expect(await engine.state == .driving)
        _ = await engine.handle(.timerTick(at: at(300 + ParkingTransitionPolicy.movementIdleWindow)))
        #expect(await engine.state == .parkingTransition)
    }

    /// §5: the drive a candidate inherits from starts when the trip did, not at the last
    /// red light.
    @Test("A red-light resume does not orphan the drive's reliable fix")
    func resumeKeepsTheInheritanceBound() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(150, accuracy: 10, speed: 9))
        _ = await engine.handle(.timerTick(at: at(330)))
        #expect(await engine.state == .parkingTransition)
        _ = await engine.handle(.vehicleEnter(at: at(340)))

        // Act
        _ = await engine.handle(.vehicleExit(at: at(400)))
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(410)))).first)

        // Assert
        #expect(candidate.lastReliableLocation?.capturedAt == at(150))
        #expect(candidate.reasonCodes.contains(.reliableLocationCaptured))
    }

    // MARK: - Capture keeps running while the transition decides (F3)

    @Test("Entering the transition keeps the capture; leaving it releases it")
    func transitionKeepsTheCapture() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))

        // Act
        let entry = await engine.handle(.timerTick(at: at(280)))
        let wantedInside = await engine.snapshot().isLocationCaptureWanted
        let lapse = await engine.handle(.timerTick(at: at(280 + ParkingTransitionPolicy.transitionWindow)))

        // Assert
        #expect(!entry.contains(.stopLocationCapture))
        #expect(wantedInside)
        #expect(lapse.contains(.stopLocationCapture))
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
    }

    @Test("A candidate releases the capture the transition kept")
    func candidateReleasesTheCapture() async {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(300)))

        // Assert
        #expect(effects.contains(.stopLocationCapture))
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
    }

    @Test("A lost capture inside the transition stops the capture and leaves motion free to confirm")
    func authorizationLossInsideTransitionKeepsDeciding() async throws {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        let lost = await engine.endDrivingSession(reason: .authorizationLost, now: at(290))
        let effects = await engine.handle(.walkingEnter(at: at(300)))

        // Assert
        // The capture stops, and the loss is written so a relaunch keeps it (§11).
        #expect(lost.first == .stopLocationCapture)
        #expect(lost.count == 2)
        let recorded = lost.compactMap { if case let .persistCheckpoint(c) = $0 { c } else { nil } }.first
        #expect(recorded?.engine?.transition?.isCapturing == false)
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
        #expect(try #require(candidates(effects).first).reasonCodes.contains(.walkingAfterVehicle))
        #expect(!effects.contains(.stopLocationCapture))
    }

    @Test("Turning Smart Detection off inside the transition drops it and releases the capture")
    func optOutInsideTransitionDropsIt() async {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        let effects = await engine.endDrivingSession(reason: .smartDetectionDisabled, now: at(290))

        // Assert
        #expect(await engine.state == .idle)
        #expect(effects.contains(.stopLocationCapture))
        #expect(await candidates(engine.handle(.walkingEnter(at: at(300)))).isEmpty)
    }

    /// A process that died inside the window is handed back a transition that can still
    /// hear a fix — and that claims no exit it cannot know was detected.
    @Test("A restored transition reopens the capture and credits no exit")
    func restoredTransitionReopensTheCapture() async throws {
        // Arrange
        let engine = ParkingDetectionEngine()
        let checkpoint = DetectionCheckpoint(state: .parkingTransition, stateEnteredAt: t0, lastAutomotiveAt: t0)

        // Act
        let restored = await engine.restore(checkpoint, seedIfAbsent: false, now: at(30))
        let effects = await engine.handle(fix(60, speed: 0.4))

        // Assert
        #expect(restored.contains(.startBoundedLocationCapture))
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.locationStopped))
        #expect(!candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(!candidate.reasonCodes.contains(.vehicleDurationMet))
    }

    /// The checkpoint carries the drive's §7 distance (`travelDistanceEstimate`, written at
    /// the transition's entry). A rebuilt transition that dropped it scored every parking
    /// confirmed after a process death as a trip below 800 m.
    @Test("A restored transition keeps the distance the drive had covered")
    func restoredTransitionKeepsTheDistance() async throws {
        // Arrange
        let engine = ParkingDetectionEngine()
        let checkpoint = DetectionCheckpoint(
            state: .parkingTransition,
            stateEnteredAt: t0,
            lastAutomotiveAt: t0,
            travelDistanceEstimate: 1700
        )
        _ = await engine.restore(checkpoint, seedIfAbsent: false, now: at(30))

        // Act
        let effects = await engine.handle(fix(60, speed: 0.4))

        // Assert — 1700 m is over §7's 800 m and over twice it (+5).
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.driveDistanceMeters == 1700)
        #expect(candidate.reasonCodes.contains(.vehicleDistanceMet))
        #expect(candidate.score == 25 + 10 + 5)
    }

    // MARK: - §8b vehicle_exit_detected (F4, F03, F20)

    @Test("A movementIdle ending earns location_stopped but no vehicle exit")
    func movementIdleIsNotAVehicleExit() async throws {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(300)))

        // Assert — 25 + 30 (walk) + 10 (stopped) + 5 (280 s is twice §7's 120 s) = 70.
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.walkingAfterVehicle))
        #expect(candidate.reasonCodes.contains(.locationStopped))
        #expect(!candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(candidate.score == 70)
        #expect(candidate.confidenceBucket == .medium)
    }

    @Test("A vehicle_exit that arrives inside a movementIdle transition is credited")
    func exitInsideIdleTransitionIsCredited() async throws {
        // Arrange
        let engine = await idleTransitionEngine()
        _ = await engine.handle(.vehicleExit(at: at(290)))

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(300)))

        // Assert
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
    }

    /// iOS derives its exit from a walk; inside a `movementIdle` transition that derived
    /// exit is still this drive's exit (§8b), exactly as a fixture's `vehicle_exit` is.
    @Test("An adapter-derived exit inside a movementIdle transition is credited")
    func derivedExitInsideIdleTransitionIsCredited() async throws {
        // Arrange
        let engine = await idleTransitionEngine()
        #expect(await engine.snapshot().isVehicleActive, "movementIdle is not an exit")

        // Act
        _ = await engine.endDrivingSession(reason: .walkingDetected, now: at(295))
        let effects = await engine.handle(.walkingEnter(at: at(300)))

        // Assert
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(await engine.snapshot().isVehicleActive == false)
    }

    // MARK: - §8b location_stopped (F5, F04)

    @Test("A stop long before the end of a drive is not location_stopped")
    func earlyStopIsNotLocationStopped() async throws {
        // Arrange — a drive with no moving sample whose one stopped fix came 600 s early.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 0.5))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(!candidate.reasonCodes.contains(.locationStopped))
    }

    @Test("A stop near the end of a drive with no moving sample is location_stopped")
    func lateStopWithoutMovingSampleIsLocationStopped() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(650, speed: 0.5))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(candidate.reasonCodes.contains(.locationStopped))
    }

    @Test("A stop the drive then moved on from is not location_stopped")
    func stopFollowedByMovementIsNotLocationStopped() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(600, speed: 0.5))
        _ = await engine.handle(fix(650, north: 400, speed: 9))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(!candidate.reasonCodes.contains(.locationStopped))
    }

    // MARK: - §8b location_quality_degraded (F6, F09)

    @Test("A degradation to poor in the drive's last minutes is credited")
    func lateDegradationIsCredited() async throws {
        // Arrange — the event lands while still DRIVING; iOS used to drop it there.
        let engine = await drivingEngine()
        _ = await engine.handle(.locationQualityDegraded(at: at(680), from: .good, to: .poor))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(candidate.reasonCodes.contains(.locationQualityDegraded))
    }

    @Test("good to fair is not a degradation")
    func goodToFairIsNotDegraded() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.locationQualityDegraded(at: at(680), from: .good, to: .fair))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(!candidate.reasonCodes.contains(.locationQualityDegraded))
    }

    @Test("A degradation long before the end is not credited")
    func earlyDegradationIsNotCredited() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.locationQualityDegraded(at: at(300), from: .good, to: .poor))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(!candidate.reasonCodes.contains(.locationQualityDegraded))
    }

    @Test("A fix-to-fix drop into poor near the end is credited")
    func fixDerivedDegradationIsCredited() async throws {
        // Arrange — no event at all: the iOS adapter never sends one, so fixes must say it.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(650, accuracy: 10, speed: nil))
        _ = await engine.handle(fix(670, accuracy: 80, speed: nil))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(candidate.reasonCodes.contains(.locationQualityDegraded))
    }

    /// §8b weighs a *fall* into poor. Android twin: `a drive that was poor throughout never
    /// fell into poor`.
    @Test("A drive that was poor throughout never fell into poor")
    func poorThroughoutIsNotAFall() async throws {
        // Arrange — a long underground stretch, poor from its first fix.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(600, accuracy: 80, speed: nil))
        _ = await engine.handle(fix(650, accuracy: 90, speed: nil))
        _ = await engine.handle(.vehicleExit(at: at(700)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(710)))).first)

        // Assert
        #expect(!candidate.reasonCodes.contains(.locationQualityDegraded))
    }

    /// Android twin: `a fall into poor near the end still counts after the quality recovers`.
    @Test("A fall into poor near the end still counts after the quality recovers")
    func recoveredFallStillCounts() async throws {
        // Arrange — good, poor at 450 s, fair again at 480 s, exit at 600 s.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(400, accuracy: 10, speed: nil))
        _ = await engine.handle(fix(450, accuracy: 80, speed: nil))
        _ = await engine.handle(fix(480, accuracy: 30, speed: nil))
        _ = await engine.handle(.vehicleExit(at: at(600)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(610)))).first)

        // Assert
        #expect(candidate.reasonCodes.contains(.locationQualityDegraded))
    }

    /// §8b `location_stopped`, the re-anchored clause: a `vehicle_enter` resume moves the
    /// drive's last movement to the resume (§3a "Resuming keeps the drive"), so a stop before
    /// it belongs to a red light the drive left. Android twin: `a stop from before a vehicle
    /// enter resume is not how the drive ended`.
    @Test("A stop from before a vehicle_enter resume is not how the drive ended")
    func stopBeforeAResumeIsNotLocationStopped() async throws {
        // Arrange — stopped at 120 s, idled into a transition at 280 s, resumed at 300 s.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(fix(120, speed: 0.5))
        _ = await engine.handle(.timerTick(at: at(290)))
        #expect(await engine.state == .parkingTransition, "the control")
        _ = await engine.handle(.vehicleEnter(at: at(300)))
        #expect(await engine.state == .driving, "the control")
        _ = await engine.handle(.vehicleExit(at: at(400)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(410)))).first)

        // Assert
        #expect(!candidate.reasonCodes.contains(.locationStopped))
    }

    // MARK: - §8b reliable_location_captured (F10)

    @Test("A reliable fix left by an earlier trip does not earn reliable_location_captured")
    func earlierTripFixEarnsNoReliableCode() async throws {
        // Arrange — trip one leaves a good fix; trip two is entirely underground.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(600, accuracy: 9, speed: 9))
        _ = await engine.handle(.vehicleExit(at: at(700)))
        _ = await engine.handle(.walkingEnter(at: at(710)))
        _ = await engine.handle(.userRejectedParking(at: at(720)))
        _ = await engine.handle(.vehicleEnter(at: at(800)))
        _ = await engine.handle(.timerTick(at: at(890)))
        _ = await engine.handle(.vehicleExit(at: at(1000)))

        // Act
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(1010)))).first)

        // Assert
        #expect(candidate.lastReliableLocation == nil)
        #expect(!candidate.reasonCodes.contains(.reliableLocationCaptured))
    }

    // MARK: - The car-link rows beside §3a (F13)

    @Test("A disconnect in DRIVING_CANDIDATE ends the session")
    func disconnectInDrivingCandidateEndsTheSession() async {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))
        _ = await engine.handle(.vehicleEnter(at: at(5)))

        // Act
        let effects = await engine.handle(.carLinkDisconnected(at: at(30), kind: .bluetoothAudio))

        // Assert
        #expect(await engine.state == .idle)
        #expect(effects.contains(.stopLocationCapture))
    }

    @Test("A disconnect inside the transition confirms it as a link disconnect and an exit")
    func disconnectInTransitionConfirms() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.carLinkConnected(at: at(95), kind: .bluetoothAudio))
        _ = await engine.handle(.vehicleExit(at: at(300)))

        // Act
        let effects = await engine.handle(.carLinkDisconnected(at: at(320), kind: .bluetoothAudio))

        // Assert
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.carProjectionDisconnected))
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
    }

    @Test("A link reconnecting inside the transition resumes the drive")
    func connectInTransitionResumes() async {
        // Arrange
        let engine = await idleTransitionEngine()

        // Act
        _ = await engine.handle(.carLinkConnected(at: at(300), kind: .projection))

        // Assert
        #expect(await engine.state == .driving)
        #expect(await engine.snapshot().driving?.startedAt == t0)
    }

    // MARK: - user_saved is answered first (F14)

    @Test("user_saved is answered before any window is judged")
    func userSavedPreemptsTheWindows() async {
        // Arrange — a transition whose window has already run out.
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))

        // Act
        let effects = await engine
            .handle(.userSavedParking(at: at(300 + ParkingTransitionPolicy.transitionWindow + 60)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(!effects.contains(.candidateRuleUnmet))
    }

    // MARK: - A left-behind candidate still expires (F15)

    @Test("A candidate left behind by a new journey expires in whatever state the engine is in")
    func leftBehindCandidateExpires() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        let left = try #require(await candidates(engine.handle(.walkingEnter(at: at(330)))).first)
        _ = await engine.handle(.vehicleEnter(at: at(900)))
        _ = await engine.handle(.timerTick(at: at(1000)))
        #expect(await engine.state == .driving)

        // Act
        let effects = await engine.handle(.timerTick(at: at(330 + ParkingCandidatePolicy.expiry)))

        // Assert — withdrawn, and the drive under way is left alone.
        #expect(effects.contains(.withdrawCandidate(id: left.id)))
        #expect(await engine.state == .driving)
    }

    // MARK: - Window rows are stamped at their deadline (F22)

    @Test("movementIdle enters the transition at its deadline, not when it is noticed")
    func movementIdleIsStampedAtItsDeadline() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))

        // Act — noticed 120 s late.
        _ = await engine.handle(.timerTick(at: at(400)))

        // Assert
        #expect(await engine.snapshot().parkingTransitionEnteredAt == at(280))
    }

    @Test("The transition window runs from the deadline, so a late-noticed idle cannot stretch it")
    func lateNoticeDoesNotStretchTheWindow() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(.timerTick(at: at(400)))

        // Act — 301 s after the deadline, 181 s after it was noticed.
        let effects = await engine.handle(.walkingEnter(at: at(280 + ParkingTransitionPolicy.transitionWindow + 1)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(candidates(effects).isEmpty)
    }

    /// Android twin: `the transition lapse is stamped at its deadline`.
    @Test("The transition lapse is stamped at its deadline")
    func transitionLapseIsStampedAtItsDeadline() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(200)))

        // Act
        _ = await engine.handle(.timerTick(at: at(900)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(await engine.snapshot().checkpoint.stateEnteredAt == at(200 + ParkingTransitionPolicy.transitionWindow))
    }

    /// Android twin: `the driving candidate lapse is stamped at its deadline`.
    @Test("The driving candidate lapse is stamped at its deadline")
    func drivingCandidateLapseIsStampedAtItsDeadline() async {
        // Arrange — a link with no vehicle activity: nothing will ever promote it.
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))

        // Act
        _ = await engine.handle(.timerTick(at: at(1_000)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(await engine.snapshot().checkpoint.stateEnteredAt == at(DrivingConfirmationPolicy.drivingCandidateWindow))
    }

    /// Android twin: `the session ceiling is stamped at its deadline`.
    @Test("The session ceiling is stamped at its deadline")
    func sessionCeilingIsStampedAtItsDeadline() async {
        // Arrange
        let engine = await drivingEngine()

        // Act
        _ = await engine.handle(.timerTick(at: at(DrivingSessionTimeoutPolicy.maximumDuration + 600)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(await engine.snapshot().checkpoint.stateEnteredAt == at(DrivingSessionTimeoutPolicy.maximumDuration))
    }

    /// Android twin: `the candidate expiry is stamped at its deadline`.
    @Test("The candidate expiry is stamped at its deadline")
    func candidateExpiryIsStampedAtItsDeadline() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(300)))
        let candidate = try #require(await candidates(engine.handle(.walkingEnter(at: at(330)))).first)

        // Act
        _ = await engine.handle(.timerTick(at: candidate.expiresAt.addingTimeInterval(600)))

        // Assert
        #expect(await engine.state == .idle)
        #expect(await engine.snapshot().checkpoint.stateEnteredAt == candidate.expiresAt)
    }

    // MARK: - A silent stop that moves on is a long light (docs/05 §3a, B7)

    /// A drive that moved at t=100 and then stood still, reporting a stop, until
    /// `movementIdleWindow` put it in the transition at t=280 — where that same stopped fix
    /// confirms it. Nothing but absence says this drive ended: a jam, or a parking.
    private func stoppedInTrafficEngine() async throws -> (ParkingDetectionEngine, ParkingCandidate) {
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(fix(200, speed: 0))
        let candidate = try #require(await candidates(engine.handle(fix(280, speed: 0))).first)
        return (engine, candidate)
    }

    @Test("A candidate confirmed only by a stop keeps the capture running")
    func stopOnlyCandidateKeepsTheCapture() async throws {
        // Arrange / Act
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(fix(200, speed: 0))
        let effects = await engine.handle(fix(280, speed: 0))

        // Assert
        #expect(candidates(effects).count == 1)
        #expect(await engine.state == .candidatePending)
        #expect(!effects.contains(.stopLocationCapture))
        #expect(await engine.snapshot().isLocationCaptureWanted)
    }

    @Test("A reported moving fix after a stop-only candidate retires it and resumes the same drive")
    func movingFixRetiresStopOnlyCandidate() async throws {
        // Arrange
        let (engine, candidate) = try await stoppedInTrafficEngine()

        // Act — the queue moves. One fix is a spike (§7: one event alone never confirms);
        // the second is the car.
        _ = await engine.handle(fix(340, north: 150, speed: 8))
        let firstLeft = await engine.state
        let effects = await engine.handle(fix(355, north: 300, speed: 9))

        // Assert — withdrawn, and the trip continues with its own start.
        #expect(firstLeft == .candidatePending)
        #expect(effects.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .driving)
        #expect(await engine.snapshot().driving?.startedAt == t0)
        #expect(await engine.snapshot().isLocationCaptureWanted)
        #expect(await engine.snapshot().pendingCandidateId == nil)
    }

    @Test("vehicle_enter after a stop-only candidate resumes the same drive, not a new journey")
    func vehicleEnterRetiresStopOnlyCandidate() async throws {
        // Arrange
        let (engine, candidate) = try await stoppedInTrafficEngine()

        // Act
        let effects = await engine.handle(.vehicleEnter(at: at(330)))

        // Assert
        #expect(effects.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .driving)
        #expect(await engine.snapshot().driving?.startedAt == t0)
    }

    @Test("The resume window closes transitionWindow after the drive ended and releases the capture")
    func resumeWindowClosesAndReleasesTheCapture() async throws {
        // Arrange
        let (engine, _) = try await stoppedInTrafficEngine()
        let deadline: TimeInterval = 280 + ParkingTransitionPolicy.transitionWindow
        _ = await engine.handle(fix(deadline - 30, north: 150, speed: 8))
        let before = await engine.handle(.timerTick(at: at(deadline - 1)))
        #expect(!before.contains(.stopLocationCapture))

        // Act — the second moving fix arrives after the window closed.
        let closing = await engine.handle(.timerTick(at: at(deadline)))
        let late = await engine.handle(fix(deadline + 20, north: 300, speed: 8))

        // Assert — the candidate stands; only the capture went.
        #expect(closing.contains(.stopLocationCapture))
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
        #expect(late.isEmpty)
        #expect(await engine.state == .candidatePending)
    }

    /// The distance fallback reads jitter around a parked car as travel — after s03's real
    /// parking, its speedless walk-away fixes clear it at 2.01 m/s — so only a Doppler
    /// speed may take a candidate back.
    @Test("A speedless fix that clears the distance fallback does not retire a candidate")
    func derivedMovementDoesNotRetireACandidate() async throws {
        // Arrange
        let (engine, _) = try await stoppedInTrafficEngine()

        // Act — 500 m in 80 s from the last stopped fix, no speed.
        _ = await engine.handle(fix(360, north: 500, accuracy: 10, speed: nil))

        // Assert
        #expect(await engine.state == .candidatePending)
    }

    @Test("The walk away from the car does not move the spot a later candidate would inherit")
    func holdDoesNotUpdateTheReliableLocation() async throws {
        // Arrange
        let (engine, _) = try await stoppedInTrafficEngine()
        let spot = await engine.snapshot().checkpoint.lastReliableLocation

        // Act — a clean fix 40 m away, at walking pace.
        let effects = await engine.handle(fix(320, north: 40, accuracy: 5, speed: 1.3))

        // Assert — the drive it recorded into is written (docs/05 §14), and nothing else.
        #expect(effects.allSatisfy { if case .persistCheckpoint = $0 { true } else { false } })
        #expect(await engine.snapshot().checkpoint.lastReliableLocation == spot)
    }

    @Test("A walk-confirmed candidate is not retired by movement")
    func walkConfirmedCandidateIsNotResumable() async throws {
        // Arrange
        let engine = await idleTransitionEngine()
        let confirming = await engine.handle(.walkingEnter(at: at(300)))
        #expect(candidates(confirming).count == 1)

        // Act
        _ = await engine.handle(fix(330, north: 300, speed: 8))

        // Assert
        #expect(await engine.state == .candidatePending)
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
    }

    @Test("A candidate after an explicit exit is not retired by movement")
    func exitConfirmedCandidateIsNotResumable() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(.vehicleExit(at: at(200)))
        #expect(await candidates(engine.handle(fix(230, speed: 0.3))).count == 1)

        // Act
        _ = await engine.handle(fix(260, north: 300, speed: 8))

        // Assert
        #expect(await engine.state == .candidatePending)
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
    }

    /// §3a rule 4 (2026-09-27, round 3): the walk is the strongest evidence the person left
    /// the car, whether it arrives before the candidate (then it is not stop-only at all) or
    /// after it. Vehicle evidence after that walk is more likely a bus or a lift than this
    /// car, and must not take back the parking. On an iPhone the adapter already closes the
    /// window through the exit it derives from the walk; the engine states it itself so a
    /// platform with no derived exit — and every fixture — reads the same rule.
    @Test("A walk after a stop-only candidate closes its resume window")
    func walkClosesTheResumeWindow() async throws {
        // Arrange
        let (engine, candidate) = try await stoppedInTrafficEngine()

        // Act
        let walking = await engine.handle(.walkingEnter(at: at(300)))
        let boarding = await engine.handle(.vehicleEnter(at: at(330)))

        // Assert — the capture went with the window; the candidate stands, and the
        // vehicle evidence is a new journey that leaves it answerable (§3a).
        #expect(walking.contains(.stopLocationCapture))
        #expect(!boarding.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .drivingCandidate)
    }

    // MARK: - A stop-only candidate takes the exit that follows it (docs/05 §3a, 2026-10-01)

    @Test("A walk before the drive's window closes re-scores a stop-only candidate")
    func walkRescoresStopOnlyCandidate() async throws {
        // Arrange — on 2026-10-01 the walk came a second after the stop that confirmed the
        // transition, and the candidate stayed low: nothing was posted.
        let (engine, candidate) = try await stoppedInTrafficEngine()

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(300)))

        // Assert — the same candidate, upgraded in place and announced for the first time.
        let rescored = try #require(upgrades(effects).first)
        #expect(candidates(effects).isEmpty)
        #expect(!effects.contains(.withdrawCandidate(id: candidate.id)))
        #expect(rescored.id == candidate.id)
        #expect(rescored.confidenceBucket == .medium)
        #expect(rescored.reasonCodes.contains(.walkingAfterVehicle))
        #expect(rescored.detectedAt == candidate.detectedAt)
        #expect(rescored.expiresAt == candidate.expiresAt)
        #expect(effects.contains(.issueCandidateNotification(rescored)))
        #expect(await engine.snapshot().pendingCandidateId == candidate.id)
    }

    @Test("An exit and then a walk both count towards a stop-only candidate")
    func exitThenWalkAccumulate() async throws {
        // Arrange — the exit closes the resume window; the walk a second later must still count.
        let (engine, _) = try await stoppedInTrafficEngine()

        // Act
        let exit = await engine.handle(.vehicleExit(at: at(300)))
        let walk = await engine.handle(.walkingEnter(at: at(301)))

        // Assert — the exit alone does not move the bucket; the two together do.
        #expect(upgrades(exit).isEmpty)
        let rescored = try #require(upgrades(walk).first)
        #expect(rescored.confidenceBucket == .high)
        #expect(rescored.reasonCodes.contains(.vehicleExitDetected))
    }

    @Test("A walk after the drive's window leaves a stop-only candidate as it was")
    func lateWalkDoesNotRescore() async throws {
        // Arrange — past the window the car may have driven on and parked somewhere else.
        let (engine, candidate) = try await stoppedInTrafficEngine()

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(280 + ParkingTransitionPolicy.transitionWindow)))

        // Assert
        #expect(upgrades(effects).isEmpty)
        #expect(await engine.snapshot().pendingCandidateId == candidate.id)
    }

    @Test("An upgrade of a candidate already announced does not announce it again")
    func upgradeOfAnnouncedCandidateIsSilent() async throws {
        // Arrange — the 2026-10-01 shape: a stop-only candidate that fell into poor near the
        // end (45), an exit that lifts it to medium and announces it, then the walk.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 9))
        _ = await engine.handle(fix(150, accuracy: 50, speed: 0))
        _ = await engine.handle(fix(200, speed: 0))
        _ = try #require(await candidates(engine.handle(fix(280, speed: 0))).first)
        let exit = await engine.handle(.vehicleExit(at: at(290)))
        let announced = try #require(upgrades(exit).first)
        try #require(announced.confidenceBucket == .medium)
        try #require(exit.contains(.issueCandidateNotification(announced)))

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(291)))

        // Assert — one buzz for one parking.
        let rescored = try #require(upgrades(effects).first)
        #expect(rescored.confidenceBucket == .high)
        #expect(!effects.contains(where: { if case .issueCandidateNotification = $0 { true } else { false } }))
    }

    // MARK: - A blind fix is not a stop (docs/05 §3a, 2026-10-01)

    @Test("Blind fixes in a tunnel do not end a drive as movement idle")
    func blindFixesDoNotIdle() async {
        // Arrange — speedless 600 m fixes say the sky is gone, not that the car stopped.
        // The 2026-10-01 drive lapsed to IDLE this way and lost the parking at its end.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 14))

        // Act
        for seconds in stride(from: 130.0, through: 700, by: 30) {
            _ = await engine.handle(fix(seconds, accuracy: 600, speed: nil))
        }

        // Assert
        #expect(await engine.state == .driving)
    }

    @Test("A real stop after the tunnel still ends the drive")
    func stopAfterTunnelEndsTheDrive() async {
        // Arrange — the window runs from the last blind fix, so the clock restarts, not stops.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 14))
        for seconds in stride(from: 130.0, through: 400, by: 30) {
            _ = await engine.handle(fix(seconds, accuracy: 600, speed: nil))
        }

        // Act
        _ = await engine.handle(fix(570, speed: 0))
        let beforeWindow = await engine.state
        _ = await engine.handle(fix(590, speed: 0))

        // Assert — the last blind fix at 400 s puts the boundary at 580 s; the stop at 590 s
        // confirms the transition it opened.
        #expect(beforeWindow == .driving)
        #expect(await engine.state == .candidatePending)
    }

    @Test("A garage fix of 50 m is not blind and lets a parked car look still")
    func garageFixIsNotBlind() async {
        // Arrange — field s16: a parked car under a slab reads 30–55 m without a speed.
        let engine = await drivingEngine()
        _ = await engine.handle(fix(100, speed: 14))

        // Act
        for seconds in stride(from: 130.0, through: 400, by: 30) {
            _ = await engine.handle(fix(seconds, accuracy: 50, speed: nil))
        }

        // Assert
        #expect(await engine.state == .parkingTransition)
    }

    @Test("A vehicle_exit after a stop-only candidate closes its resume window")
    func vehicleExitClosesTheResumeWindow() async throws {
        // Arrange
        let (engine, _) = try await stoppedInTrafficEngine()

        // Act
        let exiting = await engine.handle(.vehicleExit(at: at(300)))
        _ = await engine.handle(fix(320, north: 150, speed: 8))
        _ = await engine.handle(fix(335, north: 300, speed: 9))

        // Assert
        #expect(exiting.contains(.stopLocationCapture))
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
        #expect(await engine.state == .candidatePending)
    }

    @Test("A stationary_enter after a stop-only candidate leaves the resume window open")
    func stillnessKeepsTheResumeWindow() async throws {
        // Arrange — the Transition API can report STILL inside a car at a light.
        let (engine, candidate) = try await stoppedInTrafficEngine()

        // Act
        let still = await engine.handle(.stationaryEnter(at: at(300)))
        _ = await engine.handle(fix(320, north: 150, speed: 8))
        let effects = await engine.handle(fix(335, north: 300, speed: 9))

        // Assert
        #expect(!still.contains(.stopLocationCapture))
        #expect(effects.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .driving)
    }

    /// docs/05 §3a "The window lives exactly as long as its capture" / §14 "The capture did not
    /// survive" (2026-09-29): the Core Location session dies with the process, and the relaunch
    /// reopens the capture the restored state wants — the stop-only window's too, which the
    /// reopened capture then carries. So the relaunch changes nothing: the jam moving on
    /// withdraws the candidate exactly as it does in the process that opened the window.
    @Test("A relaunch inside a stop-only window reopens its capture and keeps the resume")
    func relaunchInsideTheResumeWindowKeepsIt() async throws {
        // Arrange — the process dies 20 s into the window.
        let (engine, candidate) = try await stoppedInTrafficEngine()
        let checkpoint = await engine.snapshot().checkpoint
        let relaunched = ParkingDetectionEngine()

        // Act
        let restored = await relaunched.restore(
            checkpoint,
            pendingCandidate: candidate,
            seedIfAbsent: false,
            now: at(300)
        )
        let wantedAfterRestore = await relaunched.snapshot().isLocationCaptureWanted
        _ = await relaunched.handle(fix(320, north: 150, speed: 8))
        let moving = await relaunched.handle(fix(335, north: 300, speed: 9))

        // Assert
        #expect(restored.contains(.startBoundedLocationCapture))
        #expect(wantedAfterRestore)
        #expect(moving.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await relaunched.state == .driving)
    }

    /// The window's own deadline still bounds it across a relaunch: one that lapsed while the
    /// process was dead is settled by the restore, and no capture is reopened for it.
    @Test("A relaunch after a stop-only window lapsed reopens no capture and keeps the candidate")
    func relaunchAfterTheResumeWindowLapsedKeepsTheCandidate() async throws {
        // Arrange
        let (engine, candidate) = try await stoppedInTrafficEngine()
        let checkpoint = await engine.snapshot().checkpoint
        let deadline = try #require(checkpoint.engine?.candidateResume?.deadline)
        let relaunched = ParkingDetectionEngine()

        // Act
        let restored = await relaunched.restore(
            checkpoint,
            pendingCandidate: candidate,
            seedIfAbsent: false,
            now: deadline.addingTimeInterval(60)
        )

        // Assert
        #expect(!restored.contains(.startBoundedLocationCapture))
        #expect(await relaunched.snapshot().isLocationCaptureWanted == false)
        #expect(!restored.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await relaunched.state == .candidatePending)
    }

    /// §14 "The capture did not survive" step 3's exception: the relaunch asks for the capture,
    /// and Core Location refuses it (Always revoked while the process was dead — the adapter's
    /// `captureDidLoseAuthorization`). That is rule 4's lost capture: the window closes, the
    /// candidate stays, and the next `vehicle_enter` is a new journey, not the jam moving on.
    /// Android twin: the reboot with background location revoked in `ParkingDetectionRuntimeTest`.
    @Test("A relaunch inside a stop-only window whose capture cannot reopen closes it and keeps the candidate")
    func relaunchWithoutItsCaptureClosesTheResumeWindow() async throws {
        // Arrange
        let (engine, candidate) = try await stoppedInTrafficEngine()
        let checkpoint = await engine.snapshot().checkpoint
        let relaunched = ParkingDetectionEngine()
        let restored = await relaunched.restore(
            checkpoint,
            pendingCandidate: candidate,
            seedIfAbsent: false,
            now: at(300)
        )

        // Act
        let refused = await relaunched.endDrivingSession(reason: .authorizationLost, now: at(301))
        let boarding = await relaunched.handle(.vehicleEnter(at: at(330)))

        // Assert
        #expect(restored.contains(.startBoundedLocationCapture))
        #expect(refused.contains(.stopLocationCapture))
        #expect(await relaunched.snapshot().checkpoint.engine?.candidateResume == nil)
        #expect(!refused.contains(.withdrawCandidate(id: candidate.id)))
        #expect(!boarding.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await relaunched.state == .drivingCandidate)
    }

    /// docs/05 §3a "The window lives exactly as long as its capture" (R4-B1): a transition
    /// that lost its capture — authorization or a capture failure — opens no resume window,
    /// so "an open window always holds its capture" is true on both platforms. Android
    /// reaches the same outcome by closing the window in its runtime before the next batch.
    /// Without this, iOS withdrew the parking on the same `vehicle_enter` Android treated as
    /// a new journey.
    @Test("A stop-only candidate whose transition lost its capture opens no resume window")
    func captureLostTransitionOpensNoResumeWindow() async throws {
        // Arrange — a movementIdle transition that loses its capture, then a stop confirms it.
        let engine = await idleTransitionEngine()
        _ = await engine.endDrivingSession(reason: .authorizationLost, now: at(285))
        let confirming = await engine.handle(.stationaryEnter(at: at(300)))
        let candidate = try #require(candidates(confirming).first)

        // Act — vehicle evidence inside what would have been the window.
        let boarding = await engine.handle(.vehicleEnter(at: at(330)))

        // Assert — the candidate stands and the evidence opens a new journey.
        #expect(!confirming.contains(.stopLocationCapture))
        #expect(!boarding.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .drivingCandidate)
    }

    // MARK: - §11 "A lost capture decides nothing" in DRIVING_CANDIDATE and DRIVING (2026-09-28)

    private func lastPersisted(_ effects: [DetectionEffect]) throws -> DetectionCheckpoint {
        let persisted = effects.compactMap {
            if case let .persistCheckpoint(checkpoint) = $0 { return checkpoint }
            return nil
        }.last
        return try #require(persisted, "nothing was persisted")
    }

    /// A lost capture only stops the capture, in an unconfirmed drive (+30 s) or a confirmed
    /// one (+120 s): the drive, its vehicle level and its state stay, and the next exit and
    /// walk decide the parking as they would with the capture running and no fix arriving.
    /// Android twin: `ParkingDetectionRuntimeTest` `a drive that loses its capture still parks
    /// on the next exit`, the loss being a revoked permission that never reaches the engine.
    @Test(
        "A drive that loses its capture still parks on the next exit",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed], [30.0, 120.0]
    )
    func driveThatLosesItsCaptureStillParks(reason: DrivingSessionEndReason, lostAt: TimeInterval) async throws {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.vehicleEnter(at: t0))
        if lostAt > DrivingConfirmationPolicy.minimumVehicleDuration {
            _ = await engine.handle(.timerTick(at: at(DrivingConfirmationPolicy.minimumVehicleDuration)))
        }
        let stateBeforeLoss = await engine.state

        // Act
        let lost = await engine.endDrivingSession(reason: reason, now: at(lostAt))
        let stateAfterLoss = await engine.state
        let wantedAfterLoss = await engine.snapshot().isLocationCaptureWanted
        _ = await engine.handle(.timerTick(at: at(150)))
        _ = await engine.handle(.vehicleExit(at: at(600)))
        let walking = await engine.handle(.walkingEnter(at: at(630)))

        // Assert
        #expect(lost.contains(.stopLocationCapture))
        #expect(!lost.contains { if case .sessionEnded = $0 { true } else { false } })
        #expect(stateAfterLoss == stateBeforeLoss)
        #expect(!wantedAfterLoss)
        #expect(candidates(walking).count == 1)
        #expect(await engine.state == .candidatePending)
    }

    /// docs/05 §14: the loss survives a process death in `DRIVING` — the drive is persisted
    /// with its evidence and reopens no capture. Android twin: `ParkingDetectionRuntimeTest`
    /// `a drive that lost its capture reopens none after a process death`.
    @Test(
        "A drive that lost its capture reopens none after a process death",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func driveThatLostItsCaptureReopensNoneOnRestore(reason: DrivingSessionEndReason) async throws {
        // Arrange — confirmed at +90, moved at +100, lost at +120, died.
        let original = await drivingEngine()
        var effects = await original.handle(fix(100, speed: 9))
        effects += await original.endDrivingSession(reason: reason, now: at(120))
        let checkpoint = try lastPersisted(effects)
        let engine = ParkingDetectionEngine()

        // Act
        let restored = await engine.restore(checkpoint, now: at(130))
        let wanted = await engine.snapshot().isLocationCaptureWanted
        // `movementIdle` at +280 from the kept +100 moving sample; stillness confirms it.
        let stopped = await engine.handle(.stationaryEnter(at: at(290)))

        // Assert
        #expect(checkpoint.state == .driving)
        #expect(!restored.contains(.startBoundedLocationCapture))
        #expect(!wanted)
        #expect(candidates(stopped).count == 1)
        #expect(await engine.state == .candidatePending)
    }

    // MARK: - §11 "A lost capture stays lost for its session" (2026-09-28, N1)

    /// The permission is revoked in `DRIVING` (+200 s) and granted again (+300 s) before the
    /// exit (+600 s). The grant is not an engine event on iOS — nothing but a new session opens
    /// a capture — so the exit and the walk decide the parking as they would without a
    /// capture. Android twin: `ParkingDetectionRuntimeTest` `a drive whose permission returns
    /// before the exit decides as it would without a capture`, through the real ingestor.
    @Test(
        "A drive whose permission returns before the exit decides as it would without a capture",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func driveWhosePermissionReturnsDecidesWithoutACapture(reason: DrivingSessionEndReason) async throws {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.vehicleEnter(at: t0))
        _ = await engine.handle(.timerTick(at: at(150)))
        _ = await engine.endDrivingSession(reason: reason, now: at(200))

        // Act
        let exit = await engine.handle(.vehicleExit(at: at(600)))
        let wantedAtTheKerb = await engine.snapshot().isLocationCaptureWanted
        let walking = await engine.handle(.walkingEnter(at: at(630)))

        // Assert
        #expect(!exit.contains(.startBoundedLocationCapture))
        #expect(!wantedAtTheKerb)
        #expect(candidates(walking).count == 1)
        #expect(await engine.state == .candidatePending)
    }

    /// A drive that lost its capture (+120 s) reaches `PARKING_TRANSITION` by `movementIdle`
    /// (+280 s) and the process dies there. The restore reopens no capture, so the stop that
    /// confirms the candidate opens no resume window, and `vehicle_enter` inside what would
    /// have been one is a new journey: `DRIVING_CANDIDATE`, candidate kept. Android twin:
    /// `ParkingDetectionRuntimeTest` `a transition that lost its capture reopens none after a
    /// process death`, with the permission granted again after the death.
    @Test(
        "A transition that lost its capture reopens none after a process death",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func transitionThatLostItsCaptureReopensNoneOnRestore(reason: DrivingSessionEndReason) async throws {
        // Arrange
        let original = await drivingEngine()
        var effects = await original.handle(fix(100, speed: 9))
        effects += await original.endDrivingSession(reason: reason, now: at(120))
        effects += await original.handle(.timerTick(at: at(100 + ParkingTransitionPolicy.movementIdleWindow)))
        let checkpoint = try lastPersisted(effects)
        let engine = ParkingDetectionEngine()

        // Act
        let restored = await engine.restore(checkpoint, now: at(285))
        let wanted = await engine.snapshot().isLocationCaptureWanted
        let stopped = await engine.handle(.stationaryEnter(at: at(300)))
        let candidate = try #require(candidates(stopped).first)
        let boarding = await engine.handle(.vehicleEnter(at: at(330)))

        // Assert
        #expect(checkpoint.state == .parkingTransition)
        #expect(!restored.contains(.startBoundedLocationCapture))
        #expect(!wanted)
        #expect(!stopped.contains(.stopLocationCapture))
        #expect(!boarding.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .drivingCandidate)
    }

    /// A transition with no capture — its drive lost it, or it lost its own — resumed by
    /// `vehicle_enter` is the same session going on, so it goes on without a capture, and a
    /// relaunch keeps the loss. Android: the follow never reopens a capture the engine already
    /// wanted, and the motion policy opens none for a session it was already following.
    @Test(
        "A transition that lost its capture resumes its drive without one",
        arguments: [true, false]
    )
    func transitionThatLostItsCaptureResumesWithoutOne(lostInTheDrive: Bool) async throws {
        // Arrange
        let engine = await drivingEngine()
        var effects = await engine.handle(fix(100, speed: 9))
        if lostInTheDrive {
            effects += await engine.endDrivingSession(reason: .authorizationLost, now: at(120))
        }
        effects += await engine.handle(.timerTick(at: at(100 + ParkingTransitionPolicy.movementIdleWindow)))
        if !lostInTheDrive {
            effects += await engine.endDrivingSession(reason: .authorizationLost, now: at(285))
        }

        // Act
        let resumed = await engine.handle(.vehicleEnter(at: at(300)))
        let stateAfterResume = await engine.state
        let wantedAfterResume = await engine.snapshot().isLocationCaptureWanted
        let checkpoint = try lastPersisted(effects + resumed)
        let relaunched = ParkingDetectionEngine()
        let restored = await relaunched.restore(checkpoint, now: at(310))

        // Assert
        #expect(stateAfterResume == .driving)
        #expect(!resumed.contains(.startBoundedLocationCapture))
        #expect(!wantedAfterResume)
        #expect(checkpoint.engine?.isDrivingCaptureLost == true)
        #expect(!restored.contains(.startBoundedLocationCapture))
        #expect(await relaunched.state == .driving)
    }

    // MARK: - Turning Smart Detection off (docs/05 §3a, 2026-09-28)

    /// Android twin: `ParkingDetectionEngineTest` `opting out during DRIVING ends the session
    /// in IDLE and stops the capture`.
    @Test("Opting out during DRIVING ends the session in IDLE and stops the capture")
    func optOutDuringDrivingEndsInIdle() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.carLinkConnected(at: at(95), kind: .bluetoothAudio))

        // Act
        let effects = await engine.endDrivingSession(reason: .smartDetectionDisabled, now: at(120))

        // Assert — and the link is forgotten: no latch survives into the next opt-in.
        #expect(effects.contains(.sessionEnded(reason: .smartDetectionDisabled, at: at(120))))
        #expect(effects.contains(.stopLocationCapture))
        #expect(await engine.state == .idle)
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
        #expect(await engine.snapshot().isVehicleActive == false)
        #expect(await engine.snapshot().connectedCarLinks.isEmpty)
    }

    /// Android twin: `ParkingDetectionEngineTest` / `ParkingDetectionRuntimeTest` `opting out
    /// inside a stop-only window closes it and keeps the candidate`.
    @Test("Opting out inside a stop-only window closes it and keeps the candidate")
    func optOutInsideAStopOnlyWindowKeepsTheCandidate() async throws {
        // Arrange
        let (engine, candidate) = try await stoppedInTrafficEngine()

        // Act
        let effects = await engine.endDrivingSession(reason: .smartDetectionDisabled, now: at(300))
        let stateAfterOptOut = await engine.state
        let vehicleAfterOptOut = await engine.snapshot().isVehicleActive
        // Detection is back on and vehicle evidence arrives inside what was the window.
        let boarding = await engine.handle(.vehicleEnter(at: at(330)))

        // Assert
        #expect(effects.filter { if case .persistCheckpoint = $0 { false } else { true } } == [.stopLocationCapture])
        #expect(stateAfterOptOut == .candidatePending)
        #expect(!vehicleAfterOptOut)
        #expect(!boarding.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .drivingCandidate)
    }

    @Test("A drive resumed from a stop-only candidate can still produce the real parking")
    func resumedDriveCanStillPark() async throws {
        // Arrange
        let (engine, _) = try await stoppedInTrafficEngine()
        _ = await engine.handle(fix(340, north: 150, speed: 8))
        _ = await engine.handle(fix(355, north: 300, speed: 9))

        // Act
        _ = await engine.handle(.vehicleExit(at: at(500)))
        let effects = await engine.handle(.walkingEnter(at: at(510)))

        // Assert — the same trip: its duration runs from t0.
        let parking = try #require(candidates(effects).first)
        #expect(parking.driveDuration == 500)
        #expect(await engine.state == .candidatePending)
    }

    /// §3a stop-only rule 4: a confirm, a reject or a `user_saved` closes the window and
    /// releases its capture. Android twin: `answering a stop-only candidate closes its window`.
    @Test("Answering a stop-only candidate closes its window", arguments: StopOnlyAnswer.allCases)
    func answeringAStopOnlyCandidateClosesItsWindow(answer: StopOnlyAnswer) async throws {
        // Arrange
        let (engine, _) = try await stoppedInTrafficEngine()
        #expect(await engine.snapshot().isLocationCaptureWanted, "the control: the window holds the capture")

        // Act
        let effects = await engine.handle(answer.event(at: at(300)))

        // Assert
        #expect(effects.contains(.stopLocationCapture))
        #expect(await engine.snapshot().isLocationCaptureWanted == false)
    }

    enum StopOnlyAnswer: CaseIterable, Sendable {
        case confirmed, rejected, saved

        func event(at date: Date) -> DetectionEvent {
            switch self {
            case .confirmed: .userConfirmedParking(at: date)
            case .rejected: .userRejectedParking(at: date)
            case .saved: .userSavedParking(at: date)
            }
        }
    }
}

import Foundation
import Testing
@testable import ParkingKok

/// `docs/05_PARKING_DETECTION_ENGINE.md` §3a "The car link" — its three rows, and the
/// property that matters more than all of them: **the link is optional.**
@Suite("§3a the car link")
struct CarLinkTransitionTests {
    private let t0 = TestTime.offset(0)

    private func at(_ seconds: TimeInterval) -> Date {
        t0.addingTimeInterval(seconds)
    }

    private func candidates(_ effects: [DetectionEffect]) -> [ParkingCandidate] {
        effects.compactMap {
            if case let .createCandidate(candidate) = $0 { return candidate }
            return nil
        }
    }

    private func idleEngine() async -> ParkingDetectionEngine {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        return engine
    }

    // MARK: - Row 1: IDLE → DRIVING_CANDIDATE

    @Test(
        "Connecting a link opens DRIVING_CANDIDATE",
        arguments: CarLinkKind.allCases
    )
    func connectingOpensDrivingCandidate(kind: CarLinkKind) async {
        // Arrange
        let engine = await idleEngine()

        // Act
        let effects = await engine.handle(.carLinkConnected(at: t0, kind: kind))

        // Assert
        #expect(await engine.state == .drivingCandidate)
        #expect(effects.contains(.startBoundedLocationCapture))
        #expect(await engine.snapshot().connectedCarLinks == [kind])
    }

    /// The sentence §3a spends a paragraph on: *people sit in parked cars*. Connecting is
    /// not vehicle activity, so it cannot buy the 90 seconds — and getting in and changing
    /// your mind has to produce nothing at all.
    @Test("Connecting does not skip the 90-second promotion")
    func connectingDoesNotPromote() async {
        // Arrange
        let engine = await idleEngine()
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))

        // Act — well past the sustain bar, with no motion evidence at all.
        _ = await engine.handle(.timerTick(at: at(DrivingConfirmationPolicy.minimumVehicleDuration + 60)))

        // Assert
        #expect(await engine.state == .drivingCandidate)

        // …and the window eventually closes it with nothing created.
        let effects = await engine.handle(.timerTick(at: at(DrivingConfirmationPolicy.drivingCandidateWindow)))
        #expect(await engine.state == .idle)
        #expect(candidates(effects).isEmpty)
    }

    @Test("With the link connected, motion still has to sustain 90 s before DRIVING")
    func linkPlusMotionPromotesOnTheUsualBar() async {
        // Arrange
        let engine = await idleEngine()
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))

        // Act — the car actually pulls away 10 s after the phone paired.
        _ = await engine.handle(.vehicleEnter(at: at(10)))
        _ = await engine.handle(.timerTick(at: at(99)))
        #expect(await engine.state == .drivingCandidate)
        _ = await engine.handle(.timerTick(at: at(100)))

        // Assert — 90 s after the *vehicle* activity, not after the pairing.
        #expect(await engine.state == .driving)
    }

    // MARK: - Row 2: DRIVING → CANDIDATE_PENDING

    @Test("Disconnecting goes straight to CANDIDATE_PENDING, skipping PARKING_TRANSITION")
    func disconnectingCreatesCandidateImmediately() async throws {
        // Arrange — a confirmed drive with the link up.
        let engine = await idleEngine()
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))
        _ = await engine.handle(.vehicleEnter(at: t0))
        _ = await engine.handle(.timerTick(at: at(120)))
        #expect(await engine.state == .driving)

        // Act
        let effects = await engine.handle(.carLinkDisconnected(at: at(600), kind: .bluetoothAudio))

        // Assert — no walk was ever reported, which is the underground car park §3a was
        // corrected for.
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(candidates(effects).first)
        #expect(candidate.reasonCodes.contains(.carProjectionDisconnected))
        #expect(!candidate.reasonCodes.contains(.walkingAfterVehicle))
        // §9: +25 meaningful session, +15 ended, +20 disconnect is already `medium`, so the
        // user is told.
        #expect(candidate.confidenceBucket != .low)
        #expect(effects.contains(.issueCandidateNotification(candidate)))
        #expect(effects.contains(.stopLocationCapture))
    }

    @Test("Disconnecting before the drive was confirmed creates nothing")
    func disconnectingFromDrivingCandidateCreatesNothing() async {
        // Arrange — paired, pulled away, changed mind 30 s later.
        let engine = await idleEngine()
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))
        _ = await engine.handle(.vehicleEnter(at: t0))

        // Act
        let effects = await engine.handle(.carLinkDisconnected(at: at(30), kind: .bluetoothAudio))

        // Assert
        #expect(candidates(effects).isEmpty)
        #expect(await engine.state == .drivingCandidate)
    }

    // MARK: - Row 3: CANDIDATE_PENDING → DRIVING

    /// The fuel stop (§17 fixture 3): disconnect, pump, get back in. The candidate has to
    /// be retired and its notification withdrawn before it is worth anything.
    @Test("Reconnecting retires the pending candidate and returns to DRIVING")
    func reconnectingWithdrawsTheCandidate() async throws {
        // Arrange
        let engine = await idleEngine()
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))
        _ = await engine.handle(.vehicleEnter(at: t0))
        _ = await engine.handle(.timerTick(at: at(120)))
        let created = try #require(
            candidates(await engine.handle(.carLinkDisconnected(at: at(600), kind: .bluetoothAudio))).first
        )

        // Act
        let effects = await engine.handle(.carLinkConnected(at: at(780), kind: .bluetoothAudio))

        // Assert
        #expect(effects.contains(.withdrawCandidate(id: created.id)))
        #expect(await engine.state == .driving)
        #expect(await engine.snapshot().checkpoint.candidateId == nil)
        #expect(effects.contains(.startBoundedLocationCapture))
    }

    /// …and the trip that resumed may still produce the real parking. A retired candidate
    /// was never delivered, so it must not spend the session's one candidate (§12).
    @Test("After a reconnect the same trip can still produce the real parking")
    func reconnectedTripStillDetectsTheRealParking() async throws {
        // Arrange
        let engine = await idleEngine()
        _ = await engine.handle(.carLinkConnected(at: t0, kind: .bluetoothAudio))
        _ = await engine.handle(.vehicleEnter(at: t0))
        _ = await engine.handle(.timerTick(at: at(120)))
        _ = await engine.handle(.carLinkDisconnected(at: at(600), kind: .bluetoothAudio))
        _ = await engine.handle(.carLinkConnected(at: at(780), kind: .bluetoothAudio))

        // Act — arrives and parks for real.
        let effects = await engine.handle(.carLinkDisconnected(at: at(1800), kind: .bluetoothAudio))

        // Assert
        #expect(candidates(effects).count == 1)
        #expect(await engine.state == .candidatePending)
    }

    // MARK: - The link is optional

    /// §3a: "링크는 선택 신호다. 링크가 없어도 모든 §3a 전이가 동작해야 한다."
    ///
    /// Stated once here as a whole trip so the property is visible rather than implied by
    /// the absence of link events in the other suites.
    @Test("Every state is reachable with no link event ever arriving")
    func everyTransitionWorksWithoutALink() async throws {
        // Arrange
        let engine = await idleEngine()

        // Act / Assert — IDLE → DRIVING_CANDIDATE → DRIVING
        _ = await engine.handle(.vehicleEnter(at: t0))
        #expect(await engine.state == .drivingCandidate)
        _ = await engine.handle(.timerTick(at: at(90)))
        #expect(await engine.state == .driving)

        // → PARKING_TRANSITION → DRIVING (the red light) → PARKING_TRANSITION
        _ = await engine.handle(.vehicleExit(at: at(200)))
        #expect(await engine.state == .parkingTransition)
        _ = await engine.handle(.vehicleEnter(at: at(260)))
        #expect(await engine.state == .driving)
        _ = await engine.handle(.vehicleExit(at: at(600)))
        #expect(await engine.state == .parkingTransition)

        // → CANDIDATE_PENDING → PARKED
        let effects = await engine.handle(.walkingEnter(at: at(630)))
        #expect(await engine.state == .candidatePending)
        #expect(candidates(effects).count == 1)
        _ = await engine.handle(.userConfirmedParking(at: at(700)))
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().connectedCarLinks.isEmpty)
    }
}

/// What iOS can actually observe, checked against the SDK rather than assumed
/// (see `AudioRouteCarLinkObserver` for the full finding).
@Suite("Car link observation")
struct CarLinkObservationTests {
    /// A stub route: the real observer reads `AVAudioSession`, which a unit test cannot
    /// put a car on.
    private struct StubObserver: CarLinkObserving {
        let observation: CarLinkObservation
        func observe() -> CarLinkObservation { observation }
    }

    @Test("An unconnected phone reports no link and no failure")
    func noCarReadsAsNoLink() {
        // Arrange / Act — the real observer on a simulator has no car audio route.
        let observed = AudioRouteCarLinkObserver().observe()

        // Assert: "no car" and "could not look" must not be the same answer.
        #expect(observed.connected.isEmpty)
        #expect(observed.failure == nil)
    }

    /// The adapter derives the §3a edges by comparing consecutive samples, because the
    /// audio route is all iOS exposes without holding an audio session (docs/05 §3a
    /// "Platform reality").
    @Test("Sampling the route twice is what produces a connect and then a disconnect")
    func consecutiveSamplesProduceEdges() async {
        // Arrange
        let clock = MutableDateProvider(TestTime.offset(0))
        let coordinator = BackgroundCoordinator(
            checkpointStore: StubCheckpointStore(loadResult: .absent),
            motionHistory: StubMotionHistoryProvider(result: .success([])),
            locationCapture: StubBoundedLocationCapture(),
            dateProvider: clock
        )
        await coordinator.rehydrate(launchReason: .userInitiated)

        // Act
        await coordinator.handleCarLink(CarLinkObservation(connected: [.bluetoothAudio]))
        let connected = await coordinator.currentSnapshot()
        clock.advance(by: 600)
        await coordinator.handleCarLink(.none)
        let disconnected = await coordinator.currentSnapshot()

        // Assert
        #expect(connected.connectedCarLinks == [.bluetoothAudio])
        #expect(connected.currentCheckpoint?.state == .drivingCandidate)
        #expect(disconnected.connectedCarLinks.isEmpty)
        // 600 s with no vehicle activity: the candidate window already closed it.
        #expect(disconnected.currentCheckpoint?.state == .idle)
    }
}

import Foundation
import Testing
@testable import ParkingPin

/// `docs/05_PARKING_DETECTION_ENGINE.md` §11c: a parking the user saved arms the departure.
///
/// Field report, iPhone, 2026-09-24: a floor saved by hand, a confirmed drive away an hour
/// later, and a record that stayed open — because the only road into `PARKED` was answering
/// a candidate. These hold the `*any* → PARKED` row of §3a and the departure it enables.
@Suite("§11c user_saved")
struct UserSavedParkingTests {
    private let t0 = TestTime.offset(0)

    private func at(_ seconds: TimeInterval) -> Date {
        t0.addingTimeInterval(seconds)
    }

    private func idleEngine() async -> ParkingDetectionEngine {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        return engine
    }

    /// `IDLE → DRIVING_CANDIDATE → DRIVING`, past the 90-second bar.
    private func drivingEngine() async -> ParkingDetectionEngine {
        let engine = await idleEngine()
        _ = await engine.handle(.vehicleEnter(at: t0))
        _ = await engine.handle(.timerTick(at: at(DrivingConfirmationPolicy.minimumVehicleDuration)))
        return engine
    }

    /// A fix `north` metres up the same meridian, fast enough to read as driving.
    private func fix(at date: Date, north: Double) -> LocationFix {
        LocationFix(
            timestamp: date,
            latitude: 37.5 + north / 111_320,
            longitude: 127.0,
            horizontalAccuracy: 8,
            speed: 12
        )
    }

    private func endedAt(_ effects: [DetectionEffect]) -> Date? {
        effects.compactMap {
            if case let .endActiveParking(at) = $0 {
                return at
            }
            return nil
        }.first
    }

    private func persistedStates(_ effects: [DetectionEffect]) -> [DetectionCheckpoint] {
        effects.compactMap {
            if case let .persistCheckpoint(checkpoint) = $0 {
                return checkpoint
            }
            return nil
        }
    }

    /// The effects §11c forbids on this row, whatever state it leaves.
    private func expectNothingInferred(
        _ effects: [DetectionEffect],
        sourceLocation: SourceLocation = #_sourceLocation
    ) {
        for effect in effects {
            switch effect {
            case .createCandidate, .issueCandidateNotification:
                Issue.record("user_saved must not raise a candidate: \(effect)", sourceLocation: sourceLocation)
            case .sessionEnded:
                Issue.record("user_saved drops the session silently: \(effect)", sourceLocation: sourceLocation)
            case .endActiveParking:
                Issue.record(
                    "user_saved would close the record just written: \(effect)",
                    sourceLocation: sourceLocation
                )
            case .startBoundedLocationCapture:
                Issue.record("user_saved must not start a capture", sourceLocation: sourceLocation)
            case .stopLocationCapture, .persistCheckpoint, .drivingConfirmed, .withdrawCandidate,
                 .candidateRuleUnmet:
                break
            }
        }
    }

    @Test("IDLE: a saved parking is PARKED, entered at the save, with nothing raised")
    func idleBecomesParked() async throws {
        // Arrange
        let engine = await idleEngine()
        let savedAt = at(60)

        // Act
        let effects = await engine.handle(.userSavedParking(at: savedAt))

        // Assert
        #expect(await engine.state == .parked)
        expectNothingInferred(effects)
        #expect(!effects.contains(.stopLocationCapture), "nothing was capturing")
        let persisted = try #require(persistedStates(effects).last, "the transition is a checkpoint write")
        #expect(persisted.state == .parked)
        #expect(persisted.stateEnteredAt == savedAt)
    }

    @Test("DRIVING: the session is dropped silently and the capture released")
    func drivingBecomesParked() async {
        // Arrange
        let engine = await drivingEngine()
        #expect(await engine.state == .driving, "the control")

        // Act
        let effects = await engine.handle(.userSavedParking(at: at(120)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(effects.contains(.stopLocationCapture))
        expectNothingInferred(effects)
        let snapshot = await engine.snapshot()
        #expect(snapshot.driving == nil)
        #expect(!snapshot.isVehicleActive, "vehicle activity is over (§11c)")
    }

    @Test("PARKING_TRANSITION: the transition is dropped and no candidate follows")
    func parkingTransitionBecomesParked() async {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(600)))
        #expect(await engine.state == .parkingTransition, "the control")

        // Act — and a walk afterwards, which would have confirmed the transition.
        var effects = await engine.handle(.userSavedParking(at: at(610)))
        effects += await engine.handle(.walkingEnter(at: at(620)))

        // Assert
        #expect(await engine.state == .parked)
        expectNothingInferred(effects)
        #expect(await engine.snapshot().parkingTransitionEnteredAt == nil)
    }

    @Test("DEPARTURE_CANDIDATE: the departure's evidence is dropped and nothing is ended")
    func departureCandidateBecomesParked() async {
        // Arrange — §11b's quickest road into the state.
        let engine = await idleEngine()
        _ = await engine.handle(.userSavedParking(at: at(0)))
        _ = await engine.handle(.carLinkConnected(at: at(3600), kind: .bluetoothAudio))
        #expect(await engine.state == .departureCandidate, "the control")

        // Act
        let effects = await engine.handle(.userSavedParking(at: at(3620)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(effects.contains(.stopLocationCapture))
        expectNothingInferred(effects)
        #expect(await engine.snapshot().driving == nil)
    }

    @Test("CANDIDATE_PENDING: the candidate is retired, not answered")
    func pendingCandidateIsRetired() async throws {
        // Arrange
        let engine = await drivingEngine()
        _ = await engine.handle(.vehicleExit(at: at(600)))
        let raised = await engine.handle(.walkingEnter(at: at(630)))
        #expect(await engine.state == .candidatePending, "the control")
        let candidate = try #require(raised.compactMap {
            if case let .createCandidate(candidate) = $0 {
                return candidate
            }
            return nil
        }.first)

        // Act
        let effects = await engine.handle(.userSavedParking(at: at(700)))

        // Assert — withdrawn the way expiry withdraws it, and the state is PARKED, not IDLE.
        #expect(await engine.state == .parked)
        #expect(effects.contains(.withdrawCandidate(id: candidate.id)))
        expectNothingInferred(effects)
        let snapshot = await engine.snapshot()
        #expect(snapshot.pendingCandidateId == nil)
        #expect(snapshot.checkpoint.candidateId == nil)
        // One checkpoint write for one transition, not a detour through IDLE.
        #expect(persistedStates(effects).map(\.state) == [.parked])
    }

    @Test("PARKED: saving again stays PARKED and re-stamps the entry")
    func parkedStaysParked() async {
        // Arrange
        let engine = await idleEngine()
        _ = await engine.handle(.userSavedParking(at: at(0)))

        // Act
        let effects = await engine.handle(.userSavedParking(at: at(300)))

        // Assert
        #expect(await engine.state == .parked)
        expectNothingInferred(effects)
        #expect(persistedStates(effects).last?.stateEnteredAt == at(300))
    }

    @Test("The bug: driving away from a parking saved by hand ends it, at the moment it pulled away")
    func drivingAwayFromASavedParkingEndsIt() async throws {
        // Arrange — the field report's shape: saved by hand, no candidate ever answered.
        let engine = await idleEngine()
        _ = await engine.handle(.userSavedParking(at: at(0)))

        // Act — get in, then cover §11's bars (90 s, 500 m) and §7's guard in full.
        let departAt = at(3600)
        var effects = await engine.handle(.vehicleEnter(at: departAt))
        for step in 1 ... 12 {
            effects += await engine.handle(
                .location(fix(at: departAt.addingTimeInterval(Double(step) * 30), north: Double(step) * 300))
            )
        }

        // Assert — closed, and closed at the moment `DEPARTURE_CANDIDATE` was entered.
        #expect(await engine.state == .driving)
        let departureEnteredAt = try #require(
            persistedStates(effects).first { $0.state == .departureCandidate }?.stateEnteredAt,
            "§11's bars must open the departure"
        )
        let ended = try #require(endedAt(effects), "a parking saved by hand must be ended by driving away")
        #expect(ended == departureEnteredAt)
    }

    @Test("A saved parking survives process death as PARKED, and still arms the departure")
    func savedParkingSurvivesRestore() async throws {
        // Arrange — the checkpoint the save wrote, handed to a fresh process.
        let engine = await idleEngine()
        let saved = await engine.handle(.userSavedParking(at: at(0)))
        let checkpoint = try #require(persistedStates(saved).last)
        let relaunched = ParkingDetectionEngine()
        _ = await relaunched.restore(checkpoint, now: at(1800))

        // Act
        let departAt = at(3600)
        var effects = await relaunched.handle(.vehicleEnter(at: departAt))
        for step in 1 ... 12 {
            effects += await relaunched.handle(
                .location(fix(at: departAt.addingTimeInterval(Double(step) * 30), north: Double(step) * 300))
            )
        }

        // Assert
        #expect(endedAt(effects) != nil)
    }
}

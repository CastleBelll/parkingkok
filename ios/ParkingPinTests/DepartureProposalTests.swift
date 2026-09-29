import Foundation
import Testing
@testable import ParkingPin

/// `docs/05_PARKING_DETECTION_ENGINE.md` §11a (DECIDED BY THE USER 2026-09-29): a confirmed
/// departure asks, it does not end the parking — and `user_kept_parking` is the answer that
/// keeps it (contract §2).
///
/// Engine half only. What the adapter and the screen do with the proposal is held by
/// `ParkingEndProposalModelTests` and `BackgroundCoordinatorProposalTests`.
@Suite("§11a departure proposal — engine")
struct DepartureProposalTests {
    private let t0 = TestTime.offset(0)

    private func at(_ seconds: TimeInterval) -> Date {
        t0.addingTimeInterval(seconds)
    }

    /// A hand-saved parking and the drive away from it that §11 confirms — the first eight
    /// events of `platform-tests/manual_save_then_departure.json`, up to the confirmation.
    private func departedEngine() async -> (ParkingDetectionEngine, [DetectionEffect]) {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        var effects: [DetectionEffect] = []
        let events: [DetectionEvent] = [
            .userSavedParking(at: at(0)),
            .vehicleEnter(at: at(600), confidence: .high),
            .location(TestGeo.fix(at: at(610), metersNorth: 0, accuracy: 8, speed: 12)),
            .location(TestGeo.fix(at: at(730), metersNorth: 1000, accuracy: 8, speed: 12)),
            .location(TestGeo.fix(at: at(760), metersNorth: 1300, accuracy: 8, speed: 12))
        ]
        for event in events {
            effects += await engine.handle(event)
        }
        return (engine, effects)
    }

    private func proposals(_ effects: [DetectionEffect]) -> [Date] {
        effects.compactMap {
            if case let .proposeParkingEnd(departedAt) = $0 {
                return departedAt
            }
            return nil
        }
    }

    @Test("A confirmed departure proposes the end at the moment the car pulled away")
    func confirmedDepartureProposes() async {
        // Arrange / Act
        let (engine, effects) = await departedEngine()

        // Assert — one proposal, stamped at DEPARTURE_CANDIDATE's entry (the t=730 fix that
        // cleared §11's bars), and the machine drives on so the next parking is detected.
        #expect(proposals(effects) == [at(730)])
        #expect(await engine.state == .driving)
    }

    @Test("A departure that never confirms proposes nothing")
    func unconfirmedDepartureProposesNothing() async {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.userSavedParking(at: at(0)))

        // Act — sitting in the car: vehicle for minutes, no distance.
        var effects = await engine.handle(.vehicleEnter(at: at(600), confidence: .high))
        effects += await engine.handle(.timerTick(at: at(900)))

        // Assert
        #expect(proposals(effects).isEmpty)
        #expect(await engine.state == .parked)
    }

    @Test("user_kept_parking after a proposal returns to PARKED and drops the drive silently")
    func keptParkingReturnsToParked() async {
        // Arrange
        let (engine, _) = await departedEngine()

        // Act
        let effects = await engine.handle(.userKeptParking(at: at(800)))

        // Assert — the capture the departure opened is stopped, and nothing is inferred on
        // the way: no session report, no candidate, no second proposal.
        #expect(await engine.state == .parked)
        #expect(effects.contains(.stopLocationCapture))
        for effect in effects {
            switch effect {
            case .stopLocationCapture, .persistCheckpoint:
                continue
            default:
                Issue.record("user_kept_parking must be silent: \(effect)")
            }
        }
        let snapshot = await engine.snapshot()
        #expect(snapshot.driving == nil)
        #expect(snapshot.checkpoint.stateEnteredAt == at(800))
    }

    @Test("After user_kept_parking a new departure has to be earned from scratch")
    func keptParkingArmsAFreshDeparture() async {
        // Arrange
        let (engine, _) = await departedEngine()
        _ = await engine.handle(.userKeptParking(at: at(800)))

        // Act — the same drive's next fix is not vehicle evidence of a new get-in.
        let effects = await engine.handle(
            .location(TestGeo.fix(at: at(820), metersNorth: 1900, accuracy: 8, speed: 12))
        )

        // Assert
        #expect(await engine.state == .parked)
        #expect(proposals(effects).isEmpty)
    }

    @Test("user_kept_parking withdraws a pending candidate, as user_saved does")
    func keptParkingWithdrawsAPendingCandidate() async throws {
        // Arrange — the drive away ended in a candidate the user never answered.
        let (engine, _) = await departedEngine()
        _ = await engine.handle(.location(TestGeo.fix(at: at(880), metersNorth: 1900, accuracy: 8, speed: 0.3)))
        _ = await engine.handle(.vehicleExit(at: at(900)))
        let created = await engine.handle(.walkingEnter(at: at(920)))
        let candidate = try #require(created.compactMap {
            if case let .createCandidate(candidate) = $0 {
                return candidate
            }
            return nil
        }.first)

        // Act
        let effects = await engine.handle(.userKeptParking(at: at(960)))

        // Assert
        #expect(effects.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .parked)
    }

    @Test("user_kept_parking is valid from IDLE, like user_saved")
    func keptParkingFromIdle() async {
        // Arrange
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)

        // Act
        let effects = await engine.handle(.userKeptParking(at: at(10)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(effects.allSatisfy {
            if case .persistCheckpoint = $0 {
                true
            } else {
                false
            }
        })
    }

    @Test("departure_proposal_kept.json: user_kept_parking is read from the wire and parks")
    func keptFixtureReplays() async throws {
        // Arrange
        let fixture = try #require(try ParityFixtureLoader.loadAll().first { $0.name == "departure_proposal_kept" })

        // Act
        let outcome = try await ParityFixtureRunner.run(fixture)

        // Assert — one proposal, then the answer returns the machine to PARKED, and the rest
        // of that drive (an exit and a walk) raises no candidate.
        #expect(outcome.finalState == .parked)
        #expect(outcome.candidates.isEmpty)
        #expect(proposals(outcome.effects).count == 1)
    }
}

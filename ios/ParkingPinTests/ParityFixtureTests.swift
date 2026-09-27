import Foundation
import Testing
@testable import ParkingPin

/// The cross-platform parity gate (`docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §8,
/// `docs/05_PARKING_DETECTION_ENGINE.md` §17).
///
/// These are the only tests in the suite whose inputs this platform does not own. The JSON
/// in `platform-tests/` is the contract; the Android engine replays the same bytes, and a
/// disagreement here is a divergence in the product, not a broken test. **If a fixture
/// fails, the engine is wrong** — the expectations were checked against the §3a table by
/// hand before they were committed.
@Suite("Cross-platform parity fixtures")
struct ParityFixtureTests {
    @Test("Every fixture in platform-tests/ is loaded and replayed")
    func everyFixtureIsPresent() throws {
        // Arrange / Act
        let fixtures = try ParityFixtureLoader.loadAll()

        // Assert — the directory currently holds five. The bar is "the bundle has them at
        // all", because an empty enumeration would otherwise make this whole suite pass by
        // finding nothing.
        #expect(fixtures.count >= 5, "platform-tests/*.json did not reach the test bundle")
        #expect(Set(fixtures.map(\.name)).count == fixtures.count, "two fixtures share a name")
    }

    @Test("Each fixture reaches the state and the candidate outcome the contract states")
    func fixturesMatchTheContract() async throws {
        for fixture in try ParityFixtureLoader.loadAll() {
            // Act
            let outcome = try await ParityFixtureRunner.run(fixture)

            // Assert
            #expect(
                outcome.finalState == fixture.expected.finalState,
                "\(fixture.name): finalState \(outcome.finalState.rawValue), expected \(fixture.expected.finalState.rawValue)"
            )
            #expect(
                outcome.didCreateCandidate == fixture.expected.candidate,
                "\(fixture.name): candidate \(outcome.didCreateCandidate), expected \(fixture.expected.candidate)"
            )

            guard let candidate = outcome.candidates.last else { continue }
            if let expectedConfidence = fixture.expected.confidence {
                #expect(
                    candidate.confidenceBucket == expectedConfidence,
                    "\(fixture.name): confidence \(candidate.confidenceBucket.rawValue), expected \(expectedConfidence.rawValue)"
                )
            }
            for reason in fixture.expected.requiredReasons ?? [] {
                #expect(
                    candidate.reasonCodes.contains(reason),
                    "\(fixture.name): missing required reason \(reason.rawValue); got \(candidate.reasonCodes.map(\.rawValue))"
                )
            }
        }
    }

    /// §12 / §3a "One candidate per travel session": a bus that stops four times must not
    /// notify four times.
    ///
    /// Counted per travel session, which is what §12 says — not per fixture. A recording
    /// can hold two journeys (field draft s03: a 128 s ride, a walk, then a new
    /// `vehicle_enter` and a fifty-minute drive), and §3a makes the second a new travel
    /// session that is allowed its own candidate. A travel session starts where the engine
    /// opens one: entering `DRIVING_CANDIDATE`, or a confirmed departure (§11).
    @Test("No travel session produces more than one candidate")
    func noSessionProducesACandidateStorm() async throws {
        for fixture in try ParityFixtureLoader.loadAll() {
            let outcome = try await ParityFixtureRunner.run(fixture)
            let perSession = ParityFixtureOutcome.candidatesPerTravelSession(outcome.effects)
            #expect(
                perSession.allSatisfy { $0 <= 1 },
                "\(fixture.name) produced \(perSession) candidates per travel session"
            )
        }
    }

    /// Contract §8's storm counter, pinned on the one case the fixtures do not yet hold: a
    /// candidate superseded by the next journey's (withdraw immediately followed by create)
    /// still counts for the session that made it — the user was asked twice, once per trip.
    @Test("The storm counter keeps a superseded candidate in its own travel session")
    func supersededCandidateStillCounts() async throws {
        // Arrange — two drives, each ended by an exit and a walk.
        let t0 = TestTime.offset(0)
        func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        let events: [DetectionEvent] = [
            .vehicleEnter(at: at(0)), .timerTick(at: at(150)),
            .vehicleExit(at: at(160)), .walkingEnter(at: at(170)),
            .vehicleEnter(at: at(200)), .timerTick(at: at(350)),
            .vehicleExit(at: at(360)), .walkingEnter(at: at(370))
        ]

        // Act
        var effects: [DetectionEffect] = []
        for event in events {
            effects += await engine.handle(event)
        }

        // Assert
        let created = effects.filter { if case .createCandidate = $0 { true } else { false } }
        #expect(created.count == 2)
        #expect(ParityFixtureOutcome.candidatesPerTravelSession(effects) == [1, 1])
    }

    /// docs/05 §17 `long_stop_in_traffic`, now committed to `platform-tests/` and replayed by
    /// the loop above like every fixture. Kept as its own test for what the loop cannot say:
    /// the candidate was silent, withdrawn, and contract §8's per-session count is `[0]`.
    @Test("long_stop_in_traffic: a jam that moves on retires its silent candidate")
    func longStopInTrafficResumesTheDrive() async throws {
        // Arrange
        let fixture = try #require(
            try ParityFixtureLoader.loadAll().first { $0.name == "long_stop_in_traffic" },
            "docs/05 §17 fixture missing from platform-tests/"
        )

        // Act
        let outcome = try await ParityFixtureRunner.run(fixture)

        // Assert — one silent candidate, withdrawn, and the drive carries on.
        #expect(outcome.finalState == fixture.expected.finalState)
        #expect(outcome.didCreateCandidate == fixture.expected.candidate)
        let candidate = try #require(outcome.candidates.first)
        #expect(candidate.confidenceBucket == .low)
        #expect(outcome.effects.contains(.withdrawCandidate(id: candidate.id)))
        #expect(!outcome.effects.contains(.issueCandidateNotification(candidate)))
        #expect(ParityFixtureOutcome.candidatesPerTravelSession(outcome.effects) == [0])
    }

    /// §3a: "No fixture may depend on a link event being present." Stated as a test so the
    /// day someone adds one, this says why it is not allowed rather than the iOS build
    /// quietly diverging from a device that has no car.
    @Test("No fixture carries a car-link event")
    func noFixtureDependsOnACarLink() async throws {
        let linkEvents: Set<String> = [
            "projection_connected", "projection_disconnected",
            "bluetooth_car_connected", "bluetooth_car_disconnected"
        ]
        for fixture in try ParityFixtureLoader.loadAll() {
            let found = fixture.events.map(\.type).filter(linkEvents.contains)
            #expect(found.isEmpty, "\(fixture.name) depends on optional link events \(found)")
        }
    }
}

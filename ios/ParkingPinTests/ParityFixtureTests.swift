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
    /// The exact committed set, as Android's `every committed fixture is replayed` states it:
    /// a renamed or deleted fixture or draft must fail loudly here, not vanish from the gate.
    @Test("Every fixture in platform-tests/ is loaded and replayed")
    func everyFixtureIsPresent() throws {
        // Arrange
        let committed: Set<String> = [
            "bus_repeated_stops_no_storm.json",
            // Real iPhone drives the user confirmed ended in a parking (promoted 2026-09-27).
            "field_s03_parked.json",
            "field_s04_parked.json",
            "field_s16_parked.json",
            "field_s17_parked.json",
            "field_s26_parked.json",
            "field_s32_parked.json",
            "field_s33_parked.json",
            "long_stop_in_traffic.json",
            "manual_save_parks.json",
            // §11 departure: the rows are edges, confirmed on a later event (2026-09-27).
            "manual_save_then_departure.json",
            // §11: the exit that confirms a departure also ends that drive (2026-09-28).
            "manual_save_then_short_departure.json",
            "quiet_transition_expires_no_candidate.json",
            "red_light_no_candidate.json",
            "subway_commute_underground.json",
            "tunnel_no_parking.json",
            "vehicle_then_walk.json"
        ]
        // Field drafts: replayed against the golden only (contract §8), never against an
        // `expected`. Listed so a draft deleted with its golden entry fails here too.
        let drafts: Set<String> = [
            "drafts/field_s02_parked.json",
            "drafts/field_s05_unknown.json",
            "drafts/field_s06_parked.json",
            "drafts/field_s22_unknown.json",
            "drafts/field_s23_unknown.json",
            "drafts/field_s24_unknown.json",
            "drafts/field_s25_unknown.json",
            "drafts/field_s27_unknown.json",
            "drafts/field_s28_unknown.json",
            "drafts/field_s29_unknown.json",
            "drafts/field_s31_parked.json"
        ]

        // Act
        let files = try ParityFixtureLoader.committedFileNames()
        let fixtures = try ParityFixtureLoader.loadAll()
        let replayed = try Set(ParityFixtureLoader.loadReplayInputs().keys)

        // Assert
        #expect(Set(files) == committed)
        #expect(replayed == committed.union(drafts))
        #expect(Set(fixtures.map(\.name)).count == fixtures.count, "two fixtures share a name")
    }

    /// Contract §8 "Outcome traces" (GAP2 / GAP7): every committed fixture **and every draft**
    /// replays, event by event, to the trace in `platform-tests/goldens/outcome-traces.golden.json`
    /// — each event that changed the state, created, withdrew or ended a parking, with the
    /// candidate's bucket and full reason set. `expected` is ignored: a draft has none, or one
    /// no conformant engine can meet. Android asserts the same file, so two engines agreeing
    /// with one golden agree with each other at every event.
    @Test("Every fixture and draft replays to its golden outcome trace")
    func everyReplayMatchesTheGoldenTrace() async throws {
        // Arrange
        let inputs = try ParityFixtureLoader.loadReplayInputs()
        let golden = try ParityFixtureLoader.loadGolden().fixtures

        // Act
        var actual: [String: [OutcomeTraceEntry]] = [:]
        for (key, input) in inputs {
            actual[key] = try await ParityFixtureRunner.run(input).trace
        }

        // Assert
        #expect(Set(actual.keys) == Set(golden.keys), "the golden covers exactly the fixtures and drafts on disk")
        for (key, trace) in actual.sorted(by: { $0.key < $1.key }) {
            guard let expected = golden[key], trace != expected else { continue }
            let at = trace.indices.first { trace[$0] != expected[safe: $0] } ?? trace.count
            Issue.record(
                "\(key): first difference at trace entry \(at) — golden \(expected[safe: at].map(String.init(describing:)) ?? "none"), replay \(trace[safe: at].map(String.init(describing:)) ?? "none")"
            )
        }
    }

    /// Contract §8 "Outcome traces": the golden is one shared file, not one per runner. A
    /// private copy lets `UPDATE_PARITY_GOLDEN=1` rewrite one runner's expectation while the
    /// other keeps asserting the old one — both suites green, engines apart. Scans the source
    /// checkout (not the bundle copy), skipping build output, for every file of that name.
    @Test("The outcome-trace golden exists exactly once, in platform-tests/goldens/")
    func theGoldenIsNotForked() throws {
        // Arrange
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // ParkingPinTests
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent()
        let shared = "platform-tests/goldens/outcome-traces.golden.json"

        // Act
        let copies = try GoldenCopyScanner.relativePaths(named: "outcome-traces.golden.json", under: repositoryRoot)

        // Assert
        #expect(copies == [shared], "every runner must read and write \(shared); found \(copies)")
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

        // Assert — contract §8: a supersession is withdraw-then-create, adjacent.
        let created: [ParkingCandidate] = effects.compactMap {
            if case let .createCandidate(candidate) = $0 { return candidate }
            return nil
        }
        #expect(created.count == 2)
        let withdrawal = try #require(effects.firstIndex(of: .withdrawCandidate(id: created[0].id)))
        #expect(effects[safe: withdrawal + 1] == .createCandidate(created[1]))
        #expect(ParityFixtureOutcome.candidatesPerTravelSession(effects) == [1, 1])
    }

    /// Contract §8's storm counter on a confirmed departure (§11): the drive away from a
    /// confirmed parking is a new travel session with its own allowance. Android twin:
    /// `the storm counter opens a travel session on a confirmed departure`.
    @Test("The storm counter opens a travel session on a confirmed departure")
    func confirmedDepartureOpensATravelSession() async throws {
        // Arrange — a parking confirmed, then a drive away that clears §11's bars and §7.
        let t0 = TestTime.offset(0)
        func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        let events: [DetectionEvent] = [
            .vehicleEnter(at: at(0)), .timerTick(at: at(150)),
            .vehicleExit(at: at(160)), .walkingEnter(at: at(170)),
            .userConfirmedParking(at: at(180)),
            .vehicleEnter(at: at(1_000)),
            .location(TestGeo.fix(at: at(1_030), metersNorth: 0, accuracy: 8, speed: 12)),
            .location(TestGeo.fix(at: at(1_100), metersNorth: 900, accuracy: 8, speed: 12)),
            .location(TestGeo.fix(at: at(1_130), metersNorth: 1_300, accuracy: 8, speed: 12))
        ]

        // Act
        var effects: [DetectionEffect] = []
        for event in events {
            effects += await engine.handle(event)
        }

        // Assert
        #expect(effects.contains { if case .endActiveParking = $0 { true } else { false } })
        #expect(ParityFixtureOutcome.candidatesPerTravelSession(effects) == [1, 0])
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

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

/// Finds every copy of a file in the source checkout, as paths relative to the root.
private enum GoldenCopyScanner {
    /// Generated output that may legitimately hold a copied golden; never a source of truth.
    private static let skippedDirectories: Set<String> = ["build", "node_modules", "DerivedData"]

    static func relativePaths(named fileName: String, under root: URL) throws -> [String] {
        let rootPath = root.standardizedFileURL.path + "/"
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        var matches: [String] = []
        for case let url as URL in enumerator {
            if skippedDirectories.contains(url.lastPathComponent),
               try url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true {
                enumerator.skipDescendants()
                continue
            }
            if url.lastPathComponent == fileName {
                matches.append(String(url.standardizedFileURL.path.dropFirst(rootPath.count)))
            }
        }
        return matches.sorted()
    }
}

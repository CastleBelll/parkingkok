import Foundation
import Testing
@testable import ParkingKok

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

    /// §12 / §3a: a bus that stops four times must not notify four times. The fixture
    /// already fixes the final state; this says the thing the user would actually feel.
    @Test("No fixture produces more than one candidate")
    func noFixtureProducesACandidateStorm() async throws {
        for fixture in try ParityFixtureLoader.loadAll() {
            let outcome = try await ParityFixtureRunner.run(fixture)
            #expect(outcome.candidates.count <= 1, "\(fixture.name) produced \(outcome.candidates.count) candidates")
        }
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

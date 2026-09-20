import Foundation
@testable import ParkingKok

/// One `platform-tests/*.json` file, in the schema
/// `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §8 fixes.
///
/// Decoded rather than hand-transcribed, and the files themselves are **never edited from
/// this side**: the Android engine reads the same bytes, and a fixture a platform can
/// adjust is not a contract.
struct ParityFixture: Decodable {
    let name: String
    let initialState: DetectionState
    let events: [ParityFixtureEvent]
    let expected: Expectation

    struct Expectation: Decodable {
        let candidate: Bool
        let finalState: DetectionState
        /// §5's bucket. Optional, and deliberately absent from
        /// `subway_commute_underground.json` — pinning it there would block the §18
        /// tuning that fixture exists to measure.
        let confidence: ConfidenceBucket?
        /// §4 codes that must be present. A subset check: the engine may have accumulated
        /// more evidence than the fixture chose to name.
        let requiredReasons: [CandidateReasonCode]?
    }
}

/// The union of the fields §8's event vocabulary allows. Every one after `type` and `t` is
/// optional because "선택 필드는 없으면 키 자체가 빠진다" — a missing key is the schema, not
/// an omission.
struct ParityFixtureEvent: Decodable {
    let type: String
    /// Seconds relative to the first event.
    let t: Double
    let accuracy: Double?
    let speed: Double?
    let distanceFromPreviousM: Double?
    let confidence: MotionConfidence?
    let fromBucket: LocationAccuracyBucket?
    let toBucket: LocationAccuracyBucket?
}

enum ParityFixtureError: Error, CustomStringConvertible {
    case unknownEventType(String)
    case locationWithoutAccuracy(at: Double)
    case unsupportedInitialState(DetectionState)

    var description: String {
        switch self {
        case let .unknownEventType(type):
            "event type '\(type)' is not in the §2 wire vocabulary — the runner must be taught it, not the fixture changed"
        case let .locationWithoutAccuracy(t):
            "location event at t=\(t) has no accuracy; §8 requires one"
        case let .unsupportedInitialState(state):
            "initialState \(state.rawValue) has no seeding rule yet"
        }
    }
}

/// What one replay produced.
struct ParityFixtureOutcome {
    var finalState: DetectionState
    var candidates: [ParkingCandidate]
    var effects: [DetectionEffect]

    var didCreateCandidate: Bool { !candidates.isEmpty }
}

/// Replays a §8 fixture through the real `ParkingDetectionEngine`.
///
/// ### The one thing the fixture format cannot express, and what this does about it
/// §8 forbids coordinates, so a `location` event carries `distanceFromPreviousM` and no
/// direction. `DrivingEvidence` measures displacement from a held anchor rather than by
/// summing consecutive steps — deliberately, because summing cannot tell 150 m of travel
/// from 150 m of accumulated jitter (docs/05 §7). Those two facts do not fit together on
/// their own, so the runner reconstructs the only path consistent with the recorded
/// numbers: a straight line, each fix `distanceFromPreviousM` further along it than the
/// last. Pairwise displacement then matches the file exactly, and anchored displacement is
/// the largest value the file admits.
///
/// This is a property of the **replay**, not of the engine: on a device the fixes carry
/// real coordinates and the anchor measures a real chord. Android's runner has to make the
/// same reconstruction for the same reason, and the two must agree — it is noted here
/// because a divergence would show up as a parity failure with no obvious cause.
enum ParityFixtureRunner {
    /// Somewhere in Seoul. Any origin works; the engine only ever measures differences.
    private static let originLatitude = 37.5
    private static let originLongitude = 127.0
    /// Metres per degree of latitude on the sphere `GeoDistance` uses, so a synthesized
    /// step is exactly the distance the fixture recorded.
    private static let metersPerDegreeLatitude = 6_371_000.0 * .pi / 180

    static func run(_ fixture: ParityFixture, startingAt origin: Date = Date(timeIntervalSince1970: 1_700_000_000)) async throws -> ParityFixtureOutcome {
        let engine = ParkingDetectionEngine()
        var effects = await engine.restore(
            try seedCheckpoint(for: fixture, at: origin),
            seedIfAbsent: false,
            now: origin
        )

        var metersNorth = 0.0
        for event in fixture.events {
            let at = origin.addingTimeInterval(event.t)
            metersNorth += event.distanceFromPreviousM ?? 0
            let normalized = try normalize(event, at: at, metersNorth: metersNorth)
            effects += await engine.handle(normalized)
        }

        let candidates: [ParkingCandidate] = effects.compactMap {
            if case let .createCandidate(candidate) = $0 { return candidate }
            return nil
        }
        return ParityFixtureOutcome(
            finalState: await engine.state,
            candidates: candidates,
            effects: effects
        )
    }

    /// `initialState` is the state the recording began in, so a fixture that starts mid-trip
    /// has to be handed the evidence that state implies — otherwise `DRIVING` is a state
    /// with no drive in it and the very first window closes it.
    private static func seedCheckpoint(for fixture: ParityFixture, at origin: Date) throws -> DetectionCheckpoint? {
        switch fixture.initialState {
        case .idle:
            return nil
        case .driving, .drivingCandidate:
            return DetectionCheckpoint(
                state: fixture.initialState,
                stateEnteredAt: origin,
                lastAutomotiveAt: origin
            )
        case .parkingTransition, .candidatePending, .parked, .departureCandidate:
            throw ParityFixtureError.unsupportedInitialState(fixture.initialState)
        }
    }

    private static func normalize(
        _ event: ParityFixtureEvent,
        at date: Date,
        metersNorth: Double
    ) throws -> DetectionEvent {
        switch event.type {
        case "vehicle_enter": return .vehicleEnter(at: date, confidence: event.confidence)
        case "vehicle_exit": return .vehicleExit(at: date, confidence: event.confidence)
        case "walking_enter": return .walkingEnter(at: date, confidence: event.confidence)
        case "stationary_enter": return .stationaryEnter(at: date, confidence: event.confidence)
        case "stationary_exit": return .stationaryExit(at: date, confidence: event.confidence)
        case "location":
            guard let accuracy = event.accuracy else {
                throw ParityFixtureError.locationWithoutAccuracy(at: event.t)
            }
            return .location(
                LocationFix(
                    timestamp: date,
                    latitude: originLatitude + metersNorth / metersPerDegreeLatitude,
                    longitude: originLongitude,
                    horizontalAccuracy: accuracy,
                    speed: event.speed
                )
            )
        case "location_quality_degraded":
            return .locationQualityDegraded(at: date, from: event.fromBucket, to: event.toBucket)
        case "projection_connected": return .carLinkConnected(at: date, kind: .projection)
        case "projection_disconnected": return .carLinkDisconnected(at: date, kind: .projection)
        case "bluetooth_car_connected": return .carLinkConnected(at: date, kind: .bluetoothAudio)
        case "bluetooth_car_disconnected": return .carLinkDisconnected(at: date, kind: .bluetoothAudio)
        // §3a: the four timeout rows fire on this and nothing else, so a fixture that
        // expects elapsed time to change the state has to say so. Its absence here is why
        // none of the committed fixtures could.
        case "timer_tick": return .timerTick(at: date)
        case "user_confirmed": return .userConfirmedParking(at: date)
        case "user_rejected": return .userRejectedParking(at: date)
        default:
            throw ParityFixtureError.unknownEventType(event.type)
        }
    }
}

/// Finds the fixtures the test bundle carries.
///
/// A folder reference (see `project.yml`), enumerated rather than listed by name: a fixture
/// added to `platform-tests/` has to start running here without anyone remembering to add
/// it, or the suite quietly stops being the contract gate it claims to be.
enum ParityFixtureLoader {
    /// Only present so `Bundle(for:)` has a class in this bundle to resolve against.
    private final class BundleToken {}

    static func loadAll() throws -> [ParityFixture] {
        let bundle = Bundle(for: BundleToken.self)
        guard let directory = bundle.url(forResource: "platform-tests", withExtension: nil) else {
            return []
        }
        let urls = try FileManager.default
            .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try urls.map { try JSONDecoder().decode(ParityFixture.self, from: Data(contentsOf: $0)) }
    }
}

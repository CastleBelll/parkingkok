import Foundation
@testable import ParkingPin

/// One `platform-tests/*.json` file, in the schema
/// `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §8 fixes.
///
/// Decoded rather than hand-transcribed, and the files themselves are **never edited from
/// this side**: the Android engine reads the same bytes, and a fixture a platform can
/// adjust is not a contract.
struct ParityFixture: ParityReplayInput {
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

/// What a replay needs from a file: a committed fixture, or a `platform-tests/drafts/` draft
/// whose `expected` is absent or one no conformant engine can meet.
protocol ParityReplayInput: Decodable {
    var name: String { get }
    var initialState: DetectionState { get }
    var events: [ParityFixtureEvent] { get }
}

/// A draft, read for its events alone — its `expected` is ignored (and may be `null`).
struct ParityDraft: ParityReplayInput {
    let name: String
    let initialState: DetectionState
    let events: [ParityFixtureEvent]
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
    /// One entry per event that changed the product outcome — the unit of
    /// `platform-tests/goldens/outcome-traces.golden.json`, which both runners assert.
    var trace: [OutcomeTraceEntry]

    /// The process death a replay injected (`ParityFixtureRunner.run(_:processDeathAfter:)`),
    /// or `nil` for an uninterrupted replay.
    var relaunch: ParityRelaunch?

    var didCreateCandidate: Bool { !candidates.isEmpty }

    /// Candidates each travel session produced, in order. A session opens where the
    /// engine resets §12's "one candidate per travel session": on entering
    /// `DRIVING_CANDIDATE`, and on a departure confirmed into `DRIVING` (§11).
    ///
    /// A candidate the session itself took back — retired by a car-link reconnect or by the
    /// drive moving on (§3a), not superseded by the next one — no longer counts: §3a lets
    /// that trip produce the real parking later, and the user was never left holding two.
    static func candidatesPerTravelSession(_ effects: [DetectionEffect]) -> [Int] {
        var counts: [Int] = [0]
        var sessionOfCandidate: [UUID: Int] = [:]
        var previousState: DetectionState?
        for (index, effect) in effects.enumerated() {
            switch effect {
            case let .persistCheckpoint(checkpoint):
                let opensSession = (checkpoint.state == .drivingCandidate && previousState != .drivingCandidate)
                    || (checkpoint.state == .driving && previousState == .departureCandidate)
                if opensSession, counts.last != 0 {
                    counts.append(0)
                }
                previousState = checkpoint.state
            case let .createCandidate(candidate):
                sessionOfCandidate[candidate.id] = counts.count - 1
                counts[counts.count - 1] += 1
            case let .withdrawCandidate(id):
                let supersedes: Bool
                if index + 1 < effects.count, case .createCandidate = effects[index + 1] {
                    supersedes = true
                } else {
                    supersedes = false
                }
                if !supersedes, let session = sessionOfCandidate[id] {
                    counts[session] -= 1
                }
            default:
                break
            }
        }
        return counts
    }
}

/// A process death injected into a replay, and what the relaunch made of it.
struct ParityRelaunch {
    let afterEvent: Int
    /// When the new process restored the state.
    let at: Date
    /// The dead engine's whole state, in memory, at the moment it died.
    let stateBeforeDeath: DetectionCheckpoint
    /// The restored engine's whole state once `restore` returned.
    let stateAfterRestore: DetectionCheckpoint
    /// Outcome labels (`OutcomeTraceEntry.label(for:)`) the restore itself emitted.
    let outcomeEffects: [String]
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

    /// When the process a replay killed is launched again.
    enum RelaunchTime {
        /// At the instant it died — the relaunch changes nothing but the process.
        case immediately
        /// When the next event arrives, which is what relaunches an iOS app in the field: the
        /// process stays dead for the whole gap, and the relaunch and that event are one wake.
        /// A death after the last event relaunches immediately.
        case atNextEvent
    }

    /// Replays `fixture` event by event. With `processDeathAfter`, the process dies right after
    /// that event: a fresh engine is restored from what the dead one had persisted — its last
    /// `persistCheckpoint` and the candidate file its effects left behind — at `relaunch` time,
    /// and the replay carries on with it (docs/05 §14 "Both platforms persist and reload their
    /// whole engine state"). What the relaunch itself emitted belongs to the wake it happened
    /// in: the next event's trace entry, or an entry of its own for a relaunch with no event.
    static func run(
        _ fixture: some ParityReplayInput,
        startingAt origin: Date = Date(timeIntervalSince1970: 1_700_000_000),
        processDeathAfter deathIndex: Int? = nil,
        relaunch relaunchTime: RelaunchTime = .immediately
    ) async throws -> ParityFixtureOutcome {
        var engine = ParkingDetectionEngine()
        var effects = await engine.restore(try seedCheckpoint(for: fixture.initialState, at: origin), now: origin)

        var trace: [OutcomeTraceEntry] = []
        var relaunch: ParityRelaunch?
        var candidateFile = CandidateFile()
        var metersNorth = 0.0
        var deadState: DetectionCheckpoint?

        func relaunchEngine(at when: Date, afterEvent: Int, dead: DetectionCheckpoint) async -> [DetectionEffect] {
            let stateBefore = await engine.state
            engine = ParkingDetectionEngine()
            let restored = await engine.restore(
                lastPersistedCheckpoint(in: effects),
                pendingCandidate: candidateFile.candidate,
                seedIfAbsent: false,
                now: when
            )
            candidateFile.apply(restored, answering: nil, stateBefore: stateBefore)
            relaunch = ParityRelaunch(
                afterEvent: afterEvent,
                at: when,
                stateBeforeDeath: dead,
                stateAfterRestore: await engine.snapshot().checkpoint,
                outcomeEffects: restored.compactMap(OutcomeTraceEntry.label(for:))
            )
            effects += restored
            return restored
        }

        for (index, event) in fixture.events.enumerated() {
            let at = origin.addingTimeInterval(event.t)
            metersNorth += event.distanceFromPreviousM ?? 0
            let normalized = try normalize(event, at: at, metersNorth: metersNorth)
            let before = await engine.state
            var step: [DetectionEffect] = []
            if let dead = deadState {
                deadState = nil
                step += await relaunchEngine(at: at, afterEvent: index - 1, dead: dead)
            }
            let handled = await engine.handle(normalized)
            let after = await engine.state
            candidateFile.apply(handled, answering: normalized, stateBefore: before)
            step += handled
            let labels = step.compactMap(OutcomeTraceEntry.label(for:))
            if before != after || !labels.isEmpty {
                trace.append(OutcomeTraceEntry(
                    event: index,
                    t: event.t,
                    state: before != after ? after.rawValue : nil,
                    effects: labels
                ))
            }
            effects += handled

            guard index == deathIndex else { continue }
            let dead = await engine.snapshot().checkpoint
            if relaunchTime == .atNextEvent, index + 1 < fixture.events.count {
                deadState = dead
                continue
            }
            let restored = await relaunchEngine(at: at, afterEvent: index, dead: dead)
            let labelsAtRelaunch = restored.compactMap(OutcomeTraceEntry.label(for:))
            let stateAtRelaunch = await engine.state
            if stateAtRelaunch != after || !labelsAtRelaunch.isEmpty {
                trace.append(OutcomeTraceEntry(
                    event: index,
                    t: event.t,
                    state: stateAtRelaunch != after ? stateAtRelaunch.rawValue : nil,
                    effects: labelsAtRelaunch
                ))
            }
        }

        let candidates: [ParkingCandidate] = effects.compactMap {
            if case let .createCandidate(candidate) = $0 { return candidate }
            return nil
        }
        return ParityFixtureOutcome(
            finalState: await engine.state,
            candidates: candidates,
            effects: effects,
            trace: trace,
            relaunch: relaunch
        )
    }

    /// What the checkpoint file holds after `effects`: the last value written to it.
    private static func lastPersistedCheckpoint(in effects: [DetectionEffect]) -> DetectionCheckpoint? {
        effects.lazy.compactMap {
            if case let .persistCheckpoint(checkpoint) = $0 { return checkpoint }
            return nil
        }.last
    }

    /// The candidate file (docs/05 §10a), as the adapter keeps it: written on create, cleared
    /// on a withdrawal of that candidate and by the confirmation flow once the user answered.
    private struct CandidateFile {
        var candidate: ParkingCandidate?

        mutating func apply(_ effects: [DetectionEffect], answering event: DetectionEvent?, stateBefore: DetectionState) {
            if let event, stateBefore == .candidatePending {
                switch event {
                case .userConfirmedParking, .userRejectedParking:
                    candidate = nil
                default:
                    break
                }
            }
            for effect in effects {
                switch effect {
                case let .createCandidate(created):
                    candidate = created
                case let .withdrawCandidate(id) where candidate?.id == id:
                    candidate = nil
                default:
                    break
                }
            }
        }
    }

    /// `initialState` is the state the recording began in, so a fixture that starts mid-trip
    /// has to be handed the evidence that state implies — otherwise `DRIVING` is a state
    /// with no drive in it and the very first window closes it.
    private static func seedCheckpoint(for initialState: DetectionState, at origin: Date) throws -> DetectionCheckpoint? {
        switch initialState {
        case .idle:
            return nil
        case .driving, .drivingCandidate:
            // Vehicle activity and the drive both begin at the origin — Android's
            // `DetectionEngineState.startingIn` — and a `DRIVING` recording's drive is confirmed.
            var drive = DrivingEvidence(startedAt: origin, lastVehicleEvidenceAt: origin)
            if initialState == .driving {
                drive.markConfirmed(at: origin)
            }
            var engine = DetectionEngineRecord()
            engine.driving = drive
            engine.isVehicleActive = true
            engine.vehicleActiveSince = origin
            return DetectionCheckpoint(
                state: initialState,
                stateEnteredAt: origin,
                lastAutomotiveAt: origin,
                engine: engine
            )
        case .parked:
            // A recording made while a parking was open (the 2026-09-28 draft): no get-in yet,
            // no vehicle activity — Android's `startingIn(PARKED)`, which seeds no session.
            return DetectionCheckpoint(state: .parked, stateEnteredAt: origin, engine: DetectionEngineRecord())
        case .parkingTransition, .candidatePending, .departureCandidate:
            throw ParityFixtureError.unsupportedInitialState(initialState)
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
        // §3a: the windows are judged on every event; a tick is how a fixture says time
        // passed with nothing else arriving.
        case "timer_tick": return .timerTick(at: date)
        case "user_confirmed": return .userConfirmedParking(at: date)
        case "user_rejected": return .userRejectedParking(at: date)
        case "user_saved": return .userSavedParking(at: date)
        case "user_kept_parking": return .userKeptParking(at: date)
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

    /// The committed contract: `platform-tests/*.json`.
    static func loadAll() throws -> [ParityFixture] {
        try load(ParityFixture.self, in: nil).map(\.value)
    }

    /// The file names of the committed contract, which is what a rename changes.
    static func committedFileNames() throws -> [String] {
        try load(ParityDraft.self, in: nil).map(\.file)
    }

    /// The committed fixtures and the drafts, keyed as the golden keys them: the file name,
    /// prefixed with `drafts/` for a draft.
    static func loadReplayInputs() throws -> [String: any ParityReplayInput] {
        var inputs: [String: any ParityReplayInput] = [:]
        for (file, fixture) in try load(ParityDraft.self, in: nil) {
            inputs[file] = fixture
        }
        for (file, draft) in try load(ParityDraft.self, in: draftDirectoryName) {
            inputs["\(draftDirectoryName)/\(file)"] = draft
        }
        return inputs
    }

    /// `platform-tests/goldens/outcome-traces.golden.json`.
    static func loadGolden() throws -> OutcomeTraceGolden {
        let url = try rootDirectory()
            .appendingPathComponent(goldenDirectoryName)
            .appendingPathComponent(goldenFileName)
        return try JSONDecoder().decode(OutcomeTraceGolden.self, from: Data(contentsOf: url))
    }

    private static let draftDirectoryName = "drafts"
    private static let goldenDirectoryName = "goldens"
    private static let goldenFileName = "outcome-traces.golden.json"

    /// A folder reference that did not reach the bundle throws, so the suite fails loudly
    /// rather than passing by finding nothing.
    private static func rootDirectory() throws -> URL {
        guard let directory = Bundle(for: BundleToken.self).url(forResource: "platform-tests", withExtension: nil) else {
            throw CocoaError(.fileNoSuchFile)
        }
        return directory
    }

    /// The `*.json` files directly inside `platform-tests/` (or one of its subdirectories),
    /// sorted by name. Not recursive: a draft or a golden is never read as a fixture.
    private static func load<T: Decodable>(_ type: T.Type, in subdirectory: String?) throws -> [(file: String, value: T)] {
        var directory = try rootDirectory()
        if let subdirectory {
            directory.appendPathComponent(subdirectory)
        }
        let urls = try FileManager.default
            .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try urls.map { url in
            (url.lastPathComponent, try JSONDecoder().decode(T.self, from: Data(contentsOf: url)))
        }
    }
}

/// One event of a replay that changed the product outcome — the unit of the golden outcome
/// trace (contract §8 "Outcome traces"). Platform-neutral, and identical in shape to Android's
/// `OutcomeTraceEntry`, so one file is asserted by both runners:
/// - `event` is the index into the fixture's `events`, `t` its relative time;
/// - `state` is the engine state after the event, present only when the event changed it;
/// - `effects` are, in emission order, `withdraw`, `create <bucket> <sorted wire codes>` and
///   `proposeParkingEnd`. Checkpoints, notifications and capture requests are not outcomes.
struct OutcomeTraceEntry: Codable, Equatable, CustomStringConvertible {
    let event: Int
    let t: Double
    let state: String?
    let effects: [String]

    init(event: Int, t: Double, state: String?, effects: [String]) {
        self.event = event
        self.t = t
        self.state = state
        self.effects = effects
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        event = try container.decode(Int.self, forKey: .event)
        t = try container.decode(Double.self, forKey: .t)
        state = try container.decodeIfPresent(String.self, forKey: .state)
        effects = try container.decodeIfPresent([String].self, forKey: .effects) ?? []
    }

    static func label(for effect: DetectionEffect) -> String? {
        switch effect {
        case let .createCandidate(candidate):
            let codes = candidate.reasonCodes.map(\.rawValue).sorted().joined(separator: ",")
            return "create \(candidate.confidenceBucket.rawValue) \(codes)"
        case .withdrawCandidate:
            return "withdraw"
        case .proposeParkingEnd:
            return "proposeParkingEnd"
        default:
            return nil
        }
    }

    var description: String {
        "{event \(event), t \(t), state \(state ?? "-"), effects \(effects)}"
    }
}

struct OutcomeTraceGolden: Decodable {
    let fixtures: [String: [OutcomeTraceEntry]]
}

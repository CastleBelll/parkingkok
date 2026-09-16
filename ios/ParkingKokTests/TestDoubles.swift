import Foundation
@testable import ParkingKok

/// Frozen clock. docs/16_CODING_STANDARDS.md §8: inject the clock, never sleep.
struct FixedDateProvider: DateProviding {
    let now: Date

    init(_ now: Date) {
        self.now = now
    }
}

/// Reference instant used across the suite so expectations read as offsets.
enum TestTime {
    static let reference = Date(timeIntervalSince1970: 1_780_000_000)

    static func offset(_ seconds: TimeInterval) -> Date {
        reference.addingTimeInterval(seconds)
    }
}

/// In-memory checkpoint store. `@unchecked Sendable` with a lock because the protocol is
/// synchronous and the coordinator calls it from actor isolation.
final class StubCheckpointStore: DetectionCheckpointStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var loadResult: DetectionCheckpointLoadResult
    private var saveError: DetectionCheckpointStoreError?
    private var saved: [DetectionCheckpoint] = []

    init(
        loadResult: DetectionCheckpointLoadResult = .absent,
        saveError: DetectionCheckpointStoreError? = nil
    ) {
        self.loadResult = loadResult
        self.saveError = saveError
    }

    var savedCheckpoints: [DetectionCheckpoint] {
        lock.withLock { saved }
    }

    func load() -> DetectionCheckpointLoadResult {
        lock.withLock { loadResult }
    }

    func save(_ checkpoint: DetectionCheckpoint) throws {
        try lock.withLock {
            if let saveError {
                throw saveError
            }
            saved.append(checkpoint)
        }
    }

    func clear() throws {
        lock.withLock { saved.removeAll() }
    }
}

/// Motion history double that records the window it was asked for.
final class StubMotionHistoryProvider: MotionHistoryProviding, @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<[MotionSample], MotionHistoryError>
    private var requested: MotionHistoryWindow?

    let authorization: MotionAuthorization
    let isHistoryAvailable: Bool

    init(
        result: Result<[MotionSample], MotionHistoryError> = .success([]),
        authorization: MotionAuthorization = .authorized,
        isHistoryAvailable: Bool = true
    ) {
        self.result = result
        self.authorization = authorization
        self.isHistoryAvailable = isHistoryAvailable
    }

    var requestedWindow: MotionHistoryWindow? {
        lock.withLock { requested }
    }

    /// History grows while the process is alive — a later wake sees the walk that
    /// followed the drive. Without this the double would replay a frozen past.
    func setResult(_ result: Result<[MotionSample], MotionHistoryError>) {
        lock.withLock { self.result = result }
    }

    func samples(in window: MotionHistoryWindow) async throws -> [MotionSample] {
        lock.withLock { requested = window }
        return try result.get()
    }
}

/// Unique scratch file per test, removed on deinit.
final class TemporaryCheckpointFile {
    let url: URL

    init() {
        url = FileManager.default.temporaryDirectory
            .appending(path: "pk-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
            .appending(path: "checkpoint.json", directoryHint: .notDirectory)
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
    }

    deinit {
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }
}

/// Clock the test moves by hand. docs/16_CODING_STANDARDS.md §8: never sleep — the
/// driving session reasons about windows minutes to hours wide.
final class MutableDateProvider: DateProviding, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ now: Date) {
        current = now
    }

    var now: Date {
        lock.withLock { current }
    }

    func advance(by interval: TimeInterval) {
        lock.withLock { current = current.addingTimeInterval(interval) }
    }
}

/// Bounded-session double that counts every acquire/release so a leak is an assertion
/// rather than a battery report.
final class StubBoundedLocationCapture: BoundedLocationCapturing, @unchecked Sendable {
    private let lock = NSLock()
    private var active = false
    private var starts = 0
    private var stops = 0
    private var redundantStarts = 0

    /// Transitions into capture. A leak shows up as `startCount > stopCount`.
    var startCount: Int {
        lock.withLock { starts }
    }

    var stopCount: Int {
        lock.withLock { stops }
    }

    /// `start()` while already capturing — must never open a second session.
    var redundantStartCount: Int {
        lock.withLock { redundantStarts }
    }

    func start() {
        lock.withLock {
            if active {
                redundantStarts += 1
            } else {
                active = true
                starts += 1
            }
        }
    }

    func stop() {
        lock.withLock {
            if active {
                stops += 1
            }
            active = false
        }
    }

    func isActive() -> Bool {
        lock.withLock { active }
    }
}

/// Fixtures on a fixed meridian so "N metres north" is an exact latitude offset.
enum TestGeo {
    /// Seoul City Hall, near enough. Only the *offsets* matter to any assertion.
    static let originLatitude = 37.5665
    static let originLongitude = 126.9780

    /// Metres per degree of latitude on the sphere `GeoDistance` uses.
    static let metersPerDegreeLatitude = 6_371_000.0 * .pi / 180

    static func fix(
        at timestamp: Date,
        metersNorth: Double = 0,
        accuracy: Double = 10,
        speed: Double? = 15
    ) -> LocationFix {
        LocationFix(
            timestamp: timestamp,
            latitude: originLatitude + metersNorth / metersPerDegreeLatitude,
            longitude: originLongitude,
            horizontalAccuracy: accuracy,
            speed: speed
        )
    }
}

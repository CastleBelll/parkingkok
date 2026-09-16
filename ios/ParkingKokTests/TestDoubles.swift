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
    private let result: Result<[MotionSample], MotionHistoryError>
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

import Foundation
import UIKit
@testable import ParkingPin

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

    /// The double reports the same shape the real capture does (docs/04_IOS §3a). It holds
    /// no Core Location objects, so `holdsSessions` mirrors `isActive`.
    func health() -> BoundedCaptureHealth {
        lock.withLock {
            BoundedCaptureHealth(
                startedAt: lastStartedAt,
                holdsSessions: active,
                updateCount: starts
            )
        }
    }

    private var lastStartedAt: Date?

    func start() {
        lock.withLock {
            lastStartedAt = Date.now
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

/// In-memory trace store. Keeps every session so a test can assert on what recording
/// actually produced, and counts prune calls so "the cap ran" is observable.
final class StubTraceStore: TraceStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: [UUID: TraceSession] = [:]
    private var order: [UUID] = []
    private var writeError: TraceStoreError?
    private var prunes: [UUID?] = []
    private var openId: UUID?
    private var discardedNonViableIds: [UUID] = []
    private var suppressedPrompts = 0

    init(writeError: TraceStoreError? = nil) {
        self.writeError = writeError
    }

    var storedSessions: [TraceSession] {
        lock.withLock { order.compactMap { sessions[$0] } }
    }

    var latestSession: TraceSession? {
        lock.withLock { order.last.flatMap { sessions[$0] } }
    }

    var pruneProtectedIds: [UUID?] {
        lock.withLock { prunes }
    }

    var nonViableDiscards: [UUID] {
        lock.withLock { discardedNonViableIds }
    }

    var labelPromptSuppressedCount: Int {
        lock.withLock { suppressedPrompts }
    }

    func write(_ session: TraceSession) throws {
        try lock.withLock {
            if let writeError {
                throw writeError
            }
            if sessions[session.sessionId] == nil {
                order.append(session.sessionId)
            }
            sessions[session.sessionId] = session
        }
    }

    var openSessionId: UUID? {
        lock.withLock { openId }
    }

    func setOpenSessionId(_ id: UUID?) {
        lock.withLock { openId = id }
    }

    func prune(protecting sessionId: UUID?) {
        lock.withLock { prunes.append(sessionId) }
    }

    func discardNonViable(id: UUID) {
        lock.withLock {
            guard sessions.removeValue(forKey: id) != nil else { return }
            order.removeAll { $0 == id }
            discardedNonViableIds.append(id)
            if openId == id {
                openId = nil
            }
        }
    }

    func replace(_ id: UUID, with fragments: [TraceSession]) throws {
        try lock.withLock {
            guard openId != id else { throw TraceStoreError.sessionIsOpen }
            guard sessions[id] != nil else { throw TraceStoreError.sessionNotFound }
            for fragment in fragments {
                if sessions[fragment.sessionId] == nil {
                    order.append(fragment.sessionId)
                }
                sessions[fragment.sessionId] = fragment
            }
            sessions.removeValue(forKey: id)
            order.removeAll { $0 == id }
            prunes.append(openId)
        }
    }

    func summaries() -> [TraceSessionSummary] {
        storedSessions.reversed().map(TraceSessionSummary.init)
    }

    func load(id: UUID) -> TraceSession? {
        lock.withLock { sessions[id] }
    }

    func updateLabel(_ label: TraceLabel, for id: UUID) throws {
        try lock.withLock {
            guard var session = sessions[id] else { throw TraceStoreError.sessionNotFound }
            session.label = label
            sessions[id] = session
        }
    }

    func recordLabelPromptSuppressed() {
        lock.withLock { suppressedPrompts += 1 }
    }

    func summary() -> TraceSummary {
        let stored = storedSessions
        return TraceSummary(
            sessionCount: stored.count,
            eventCount: stored.reduce(0) { $0 + $1.events.count },
            droppedSessionCount: 0,
            unlabeledSessionCount: stored.filter { !$0.label.isLabeled }.count,
            labelPromptSuppressedCount: suppressedPrompts
        )
    }
}

/// Records what the recorder asked a label for, so "a closed session is prompted for" is
/// observable without the notification service.
final class StubLabelPrompter: TraceLabelPrompting, @unchecked Sendable {
    private let lock = NSLock()
    private var requested: [TraceLabelPrompt] = []

    var prompts: [TraceLabelPrompt] {
        lock.withLock { requested }
    }

    func requestPrompt(_ prompt: TraceLabelPrompt) {
        lock.withLock { requested.append(prompt) }
    }
}

/// Stands in for `UNUserNotificationCenter`, which a unit test cannot reach.
final class StubLabelPromptDelivery: LabelPromptDelivering, @unchecked Sendable {
    private let lock = NSLock()
    private let authorized: Bool
    private var delivered: [TraceLabelPrompt] = []

    init(authorized: Bool) {
        self.authorized = authorized
    }

    var deliveredPrompts: [TraceLabelPrompt] {
        lock.withLock { delivered }
    }

    func isAuthorized() async -> Bool {
        authorized
    }

    func deliver(_ prompt: TraceLabelPrompt) async {
        lock.withLock { delivered.append(prompt) }
    }
}

/// Unique scratch directory per test, removed on deinit.
final class TemporaryTraceDirectory {
    let url: URL

    init() {
        url = FileManager.default.temporaryDirectory
            .appending(path: "pk-traces-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }
}

enum TestTrace {
    static let metadata = TraceDeviceMetadata(
        deviceModel: "iPhone15,3",
        osVersion: "26.6",
        appVersion: "0.1.0 (12)"
    )

    static func session(
        sessionId: UUID = UUID(),
        startedAt: Date = TestTime.offset(0),
        endedAt: Date = TestTime.offset(60),
        label: TraceLabel = .unlabeled,
        events: [TraceEvent] = [],
        gapStats: TraceGapStats? = nil,
        splitFrom: TraceSplitOrigin? = nil
    ) -> TraceSession {
        TraceSession(
            sessionId: sessionId,
            metadata: metadata,
            startedAt: startedAt,
            endedAt: endedAt,
            label: label,
            events: events,
            gapStats: gapStats,
            splitFrom: splitFrom
        )
    }

    /// A session whose events sit `spacing` apart, for the cases where only the shape of
    /// the event list matters.
    static func session(eventCount: Int, spacing: TimeInterval = 60) -> TraceSession {
        let events = (0 ..< eventCount).map { index in
            TraceEvent.motion(
                index.isMultiple(of: 2) ? .stationaryEnter : .stationaryExit,
                at: TestTime.offset(Double(index) * spacing),
                confidence: .medium
            )
        }
        return session(
            startedAt: TestTime.offset(0),
            endedAt: TestTime.offset(Double(max(0, eventCount - 1)) * spacing),
            events: events,
            gapStats: TraceGapStats(events: events)
        )
    }
}

/// In-memory candidate store. One slot, exactly like the file it stands in for.
final class StubParkingCandidateStore: ParkingCandidateStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var current: ParkingCandidate?
    private var saveError: ParkingCandidateStoreError?
    private var saved: [ParkingCandidate] = []
    private var clears = 0

    init(current: ParkingCandidate? = nil, saveError: ParkingCandidateStoreError? = nil) {
        self.current = current
        self.saveError = saveError
    }

    /// Every candidate ever written, so "superseded, then replaced" is observable.
    var savedCandidates: [ParkingCandidate] {
        lock.withLock { saved }
    }

    var clearCount: Int {
        lock.withLock { clears }
    }

    func load() -> ParkingCandidate? {
        lock.withLock { current }
    }

    func save(_ candidate: ParkingCandidate) throws {
        try lock.withLock {
            if let saveError {
                throw saveError
            }
            current = candidate
            saved.append(candidate)
        }
    }

    func clear() throws {
        lock.withLock {
            current = nil
            clears += 1
        }
    }
}

/// Stands in for `UNUserNotificationCenter`, which a unit test cannot reach.
///
/// Posts are appended rather than deduplicated: the real centre replaces by identifier, so
/// a test that wants to prove "re-posting does not stack" has to assert on the identifier
/// this records, not on the count.
final class StubCandidateNotifier: CandidateNotifying, @unchecked Sendable {
    private let lock = NSLock()
    private var posted: [ParkingCandidate] = []
    private var withdrawn: [UUID] = []

    var postedCandidates: [ParkingCandidate] {
        lock.withLock { posted }
    }

    /// The identifiers the notification centre would have seen. Equal identifiers are what
    /// "replaces rather than stacks" means.
    var postedRequestIdentifiers: [String] {
        postedCandidates.map { CandidateNotificationAction.requestIdentifier(for: $0.id) }
    }

    var withdrawnCandidateIds: [UUID] {
        lock.withLock { withdrawn }
    }

    func post(_ candidate: ParkingCandidate) async {
        lock.withLock { posted.append(candidate) }
    }

    func withdraw(candidateId: UUID) async {
        lock.withLock { withdrawn.append(candidateId) }
    }
}

/// Records the state transitions the screen asks the engine for.
@MainActor
final class StubCandidateResolver: CandidateResolving {
    private(set) var outcomes: [CandidateOutcome] = []

    func resolveCandidate(_ outcome: CandidateOutcome) async {
        outcomes.append(outcome)
    }
}

enum TestCandidate {
    /// A candidate with a stated bucket, for the screens and the model — the policy's own
    /// tests are what hold the bucket to the evidence.
    static func make(
        id: UUID = UUID(),
        detectedAt: Date = TestTime.reference,
        confidence: ConfidenceBucket = .medium,
        reasonCodes: [CandidateReasonCode] = [.recentVehicleActivity, .vehicleExitDetected, .walkingAfterVehicle],
        lastReliableLocation: LastReliableLocation? = nil,
        expiresIn: TimeInterval = ParkingCandidatePolicy.expiry
    ) -> ParkingCandidate {
        ParkingCandidate(
            id: id,
            detectedAt: detectedAt,
            confidenceBucket: confidence,
            reasonCodes: reasonCodes,
            lastReliableLocation: lastReliableLocation,
            expiresAt: detectedAt.addingTimeInterval(expiresIn),
            score: 70,
            driveDuration: 900,
            driveDistanceMeters: 5000,
            accuracyBucket: .good
        )
    }

    static let location = LastReliableLocation(
        latitude: TestGeo.originLatitude,
        longitude: TestGeo.originLongitude,
        horizontalAccuracy: 12,
        capturedAt: TestTime.reference
    )
}

/// In-memory notification history (docs/10 §7b), with the file store's two rules:
/// newest first, and an append for an id that is already here is a duplicate.
final class StubCandidateHistoryStore: CandidateHistoryStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [CandidateHistoryEntry] = []
    private let capacity: Int

    init(entries: [CandidateHistoryEntry] = [], capacity: Int = FileCandidateHistoryStore.maximumEntries) {
        stored = entries
        self.capacity = capacity
    }

    func entries() -> [CandidateHistoryEntry] {
        lock.withLock { stored }
    }

    func append(_ entry: CandidateHistoryEntry) {
        lock.withLock {
            guard !stored.contains(where: { $0.id == entry.id }) else { return }
            stored.insert(entry, at: 0)
            stored = Array(stored.prefix(capacity))
        }
    }
}

/// A pillar reader that answers with whatever the test wants, including nothing.
struct StubPillarTextReader: PillarTextReading {
    let reading: PillarReading

    init(floorText: String? = nil) {
        reading = PillarReading(floorText: floorText)
    }

    func read(_: Data) async -> PillarReading {
        reading
    }
}

/// A drawn stand-in for a photograph of a car park pillar.
///
/// Generated rather than bundled for the reason the DEV fixture gives: a real photograph
/// in the repository would be somebody's car park. Clean synthetic text is also the only
/// input on which a recognition assertion can be stable.
enum TestPillarImage {
    static func jpeg(text: String, size: CGSize = CGSize(width: 1200, height: 900)) -> Data? {
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor(white: 0.30, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            let paragraph = NSMutableParagraphStyle()
            paragraph.alignment = .center
            NSAttributedString(
                string: text,
                attributes: [
                    .font: UIFont.systemFont(ofSize: 180, weight: .heavy),
                    .foregroundColor: UIColor.white,
                    .paragraphStyle: paragraph
                ]
            ).draw(in: CGRect(x: 0, y: size.height / 3, width: size.width, height: size.height / 2))
        }
        return image.jpegData(compressionQuality: 0.9)
    }
}

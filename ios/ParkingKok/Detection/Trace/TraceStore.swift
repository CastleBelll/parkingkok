import Foundation

enum TraceStoreError: Error, Equatable {
    case writeFailed(String)
    case sessionNotFound
    /// A split was asked for on the session still being recorded. §9 allows splitting only
    /// a closed session: more events may still join an open one, and replacing it would
    /// pull the file out from under the recorder mid-append.
    case sessionIsOpen
}

/// The rolling cap docs/05 §9 requires: "하루 종일 켜둬도 저장소를 채우지 않아야 한다."
///
/// Two bounds rather than one, because they fail differently: the session count bounds
/// ordinary use, and the byte ceiling is the backstop that holds even if that estimate
/// turns out wrong. Both are field-tuning starting points in the same spirit as the §8
/// evidence weights. A runaway single trip is not this type's problem —
/// `TraceSessionBoundaryPolicy` rotates it into bounded sessions before the cap ever sees
/// it, which is what makes evicting "the oldest session" meaningful.
///
/// A value rather than a set of global constants so a test can drive an eviction without
/// having to first write four megabytes of JSON to prove the byte ceiling works.
struct TraceRetentionPolicy: Sendable, Equatable {
    var maximumSessionCount = 40
    var maximumTotalBytes = 4 * 1024 * 1024

    /// What the app ships with.
    static let standard = TraceRetentionPolicy()
}

/// Best-effort by design, exactly like `DiagnosticsReportStoring`: a trace that fails to
/// write must never take down a detection callback. The failure surfaces in diagnostics.
protocol TraceStoring: Sendable {
    /// Creates or replaces the session's file.
    func write(_ session: TraceSession) throws
    /// The session the recorder may still append to, `nil` when none is open.
    ///
    /// Survives process death, which is the whole point: §9's boundary is a property of
    /// the event stream, so a relaunch inside the idle gap continues the trip rather than
    /// starting a new one. It is not part of the §9 file schema — a trace on disk says
    /// nothing about whether anyone still holds it open.
    var openSessionId: UUID? { get }
    func setOpenSessionId(_ id: UUID?)
    /// Applies the rolling cap, oldest first. `sessionId` is the session currently being
    /// recorded, which is never evicted out from under the recorder.
    func prune(protecting sessionId: UUID?)
    /// Removes a session that rotation found too small to keep (§9 "비생존 세션은 버린다"),
    /// counting it separately from a rolling-cap eviction.
    ///
    /// Idempotent, and never an error: the recorder calls this on a path that must not be
    /// able to break a detection callback, and a file already gone is the wanted outcome.
    func discardNonViable(id: UUID)
    /// Swaps a closed session for the fragments a human cut it into, then re-applies the
    /// rolling cap — one session in, two out, so the cap has to be given a chance to
    /// notice. Throws `.sessionIsOpen` for the session still being recorded.
    func replace(_ id: UUID, with fragments: [TraceSession]) throws
    /// Newest first.
    func summaries() -> [TraceSessionSummary]
    func load(id: UUID) -> TraceSession?
    func updateLabel(_ label: TraceLabel, for id: UUID) throws
    func summary() -> TraceSummary
}

/// One JSON file per session under `Library/Application Support/Detection/traces/`.
///
/// Three deliberate choices:
///
/// 1. **A file per session, not one appended log.** Eviction is then an `unlink` of the
///    oldest file, and labelling one session is a rewrite of one file — neither has to
///    rewrite the whole history, and a corrupt file costs one trip rather than all of them.
/// 2. **The protection class lives on the directory** (`FileDetectionCheckpointStore`).
///    Passing a protection option *and* `.atomic` to the same `Data.write` failed on
///    device with `NSCocoaErrorDomain(513)`; an atomic write replaces the destination and
///    that replace cannot reattach a class to a file the OS currently considers
///    unreadable. Setting it on the containing directory gets the same protection.
/// 3. **The aggregate counters are cached in memory.** `exportDiagnostics()` runs after
///    every detection callback — at ~1 Hz during a drive — so decoding every session to
///    answer "how many events are on disk" would be a battery cost the §19 gate exists to
///    catch. The index is built once per process and maintained incrementally. Only the
///    values that must outlive the process are written to `_state.json`: the two drop
///    counters, and the open-session pointer §9's boundary needs after a relaunch. The gap
///    aggregate is not among them — it is derived from the sessions still on disk, so it
///    rebuilds itself and cannot drift from what a retrieved trace would say.
final class FileTraceStore: TraceStoring, @unchecked Sendable {
    private static let directoryName = "traces"
    private static let filePrefix = "trace-"
    private static let fileExtension = "json"
    private static let stateFileName = "_state.json"

    private let directory: URL
    private let retention: TraceRetentionPolicy
    private let lock = NSLock()
    /// `nil` until the first access builds it from disk.
    private var index: [UUID: IndexEntry]?
    private var droppedSessionCount = 0
    private var nonViableDropCount = 0
    private var openSessionIdValue: UUID?
    private var hasLoadedState = false

    init(directory: URL, retention: TraceRetentionPolicy = .standard) {
        self.directory = directory
        self.retention = retention
    }

    /// Beside `checkpoint.json`'s directory, so it inherits the same protection class and
    /// comes off the device over the same `devicectl copy` path.
    static func defaultDirectoryURL() throws -> URL {
        let directory = try FileDetectionCheckpointStore.defaultFileURL()
            .deletingLastPathComponent()
            .appending(path: directoryName, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return directory
    }

    // MARK: - Writing

    func write(_ session: TraceSession) throws {
        try lock.withLock {
            var session = session
            // A label the user attached mid-trip must survive the next event's rewrite.
            // Checked against the cached index so the common case costs no file read.
            if !session.label.isLabeled, loadedIndex()[session.sessionId]?.isLabeled == true,
               let stored = decodeSession(at: fileURL(for: session)) {
                session.label = stored.label
            }

            try encodeAndWrite(session)
            index?[session.sessionId] = IndexEntry(session)
        }
    }

    var openSessionId: UUID? {
        lock.withLock {
            loadStateIfNeeded()
            return openSessionIdValue
        }
    }

    /// Written only when the pointer actually moves — a session runs for a whole trip, so
    /// this costs one small write per boundary rather than one per event.
    func setOpenSessionId(_ id: UUID?) {
        lock.withLock {
            loadStateIfNeeded()
            guard openSessionIdValue != id else { return }
            openSessionIdValue = id
            persistState()
        }
    }

    func prune(protecting sessionId: UUID?) {
        lock.withLock { pruneLocked(protecting: sessionId) }
    }

    /// Callers already hold `lock`.
    private func pruneLocked(protecting sessionId: UUID?) {
        var files = storedFiles()
        var totalBytes = files.reduce(0) { $0 + $1.byteSize }
        var dropped = 0

        // Oldest first: §9 says the cap discards the oldest sessions.
        files.sort { $0.startedAtMillis < $1.startedAtMillis }
        for file in files {
            let isOverCap = files.count - dropped > retention.maximumSessionCount
                || totalBytes > retention.maximumTotalBytes
            guard isOverCap else { break }
            guard file.id != sessionId else { continue }
            guard (try? FileManager.default.removeItem(at: file.url)) != nil else { continue }

            totalBytes -= file.byteSize
            dropped += 1
            index?[file.id] = nil
        }

        guard dropped > 0 else { return }
        loadStateIfNeeded()
        droppedSessionCount += dropped
        persistState()
        AppLog.detection.notice("trace rolling cap dropped \(dropped, privacy: .public) session(s)")
    }

    func discardNonViable(id: UUID) {
        lock.withLock {
            // Removing the file is the whole discard; a missing one means a previous call
            // already did it, and the counter must not move twice for the same session.
            guard let file = storedFiles().first(where: { $0.id == id }),
                  (try? FileManager.default.removeItem(at: file.url)) != nil
            else { return }

            index?[id] = nil
            loadStateIfNeeded()
            nonViableDropCount += 1
            // The pointer would otherwise outlive the file it names, and the next launch
            // would restore an open session that is no longer there.
            if openSessionIdValue == id {
                openSessionIdValue = nil
            }
            persistState()
            AppLog.detection.notice("trace discarded a non-viable session")
        }
    }

    func replace(_ id: UUID, with fragments: [TraceSession]) throws {
        try lock.withLock {
            loadStateIfNeeded()
            guard openSessionIdValue != id else { throw TraceStoreError.sessionIsOpen }
            guard let file = storedFiles().first(where: { $0.id == id }) else {
                throw TraceStoreError.sessionNotFound
            }

            // Fragments first, parent second. A process death between the two leaves the
            // events on disk twice, which a person can see and undo; the other order would
            // lose a recorded trip outright, which nobody can.
            for fragment in fragments {
                try encodeAndWrite(fragment)
                index?[fragment.sessionId] = IndexEntry(fragment)
            }

            if (try? FileManager.default.removeItem(at: file.url)) != nil {
                index?[id] = nil
            }

            // One session became two, so the cap is now the thing most likely to be wrong.
            // Not a `droppedSessionCount` event of its own — splitting evicts nothing; the
            // cap decides that, and counts it if it happens.
            pruneLocked(protecting: openSessionIdValue)
        }
    }

    // MARK: - Reading

    func summaries() -> [TraceSessionSummary] {
        lock.withLock {
            storedFiles()
                .sorted { $0.startedAtMillis > $1.startedAtMillis }
                .compactMap { decodeSession(at: $0.url).map(TraceSessionSummary.init) }
        }
    }

    func load(id: UUID) -> TraceSession? {
        lock.withLock {
            storedFiles().first { $0.id == id }.flatMap { decodeSession(at: $0.url) }
        }
    }

    func updateLabel(_ label: TraceLabel, for id: UUID) throws {
        try lock.withLock {
            guard let file = storedFiles().first(where: { $0.id == id }),
                  var session = decodeSession(at: file.url)
            else { throw TraceStoreError.sessionNotFound }

            session.label = label
            try encodeAndWrite(session)
            index?[id] = IndexEntry(session)
        }
    }

    func summary() -> TraceSummary {
        lock.withLock {
            loadStateIfNeeded()
            let entries = loadedIndex().values
            let measured = entries.compactMap(\.gapStats)
            return TraceSummary(
                sessionCount: entries.count,
                eventCount: entries.reduce(0) { $0 + $1.eventCount },
                droppedSessionCount: droppedSessionCount,
                nonViableDropCount: nonViableDropCount,
                unlabeledSessionCount: entries.filter { !$0.isLabeled }.count,
                measuredSessionCount: measured.count,
                maxGapMillis: measured.map(\.maxGapMillis).max() ?? 0,
                sessionsOver10MinGapCount: measured.count { $0.gapsOver10MinCount > 0 },
                sessionsOver20MinGapCount: measured.count { $0.gapsOver20MinCount > 0 }
            )
        }
    }

    // MARK: - Index and state

    /// What `summary()` needs, so answering it never decodes a file. `gapStats` rides
    /// along for the same reason: the aggregate §9 asks for on the diagnostics screen is
    /// read after every detection callback.
    private struct IndexEntry {
        var eventCount: Int
        var isLabeled: Bool
        var gapStats: TraceGapStats?

        init(_ session: TraceSession) {
            eventCount = session.events.count
            isLabeled = session.label.isLabeled
            gapStats = session.gapStats
        }
    }

    /// Callers already hold `lock`.
    private func loadedIndex() -> [UUID: IndexEntry] {
        if let index {
            return index
        }
        var rebuilt: [UUID: IndexEntry] = [:]
        for file in storedFiles() {
            guard let session = decodeSession(at: file.url) else { continue }
            rebuilt[session.sessionId] = IndexEntry(session)
        }
        index = rebuilt
        return rebuilt
    }

    private struct StoredFile {
        let url: URL
        let id: UUID
        let startedAtMillis: Int64
        let byteSize: Int
    }

    /// Reads the start time and id out of the file *name* so the cap can order and evict
    /// without decoding anything.
    private func storedFiles() -> [StoredFile] {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        )) ?? []

        return contents.compactMap { url in
            let name = url.lastPathComponent
            guard name.hasPrefix(Self.filePrefix), url.pathExtension == Self.fileExtension else {
                return nil
            }
            let stem = name.dropFirst(Self.filePrefix.count).dropLast(Self.fileExtension.count + 1)
            let parts = stem.split(separator: "-", maxSplits: 1)
            guard parts.count == 2,
                  let millis = Int64(parts[0]),
                  let id = UUID(uuidString: String(parts[1]))
            else { return nil }

            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return StoredFile(url: url, id: id, startedAtMillis: millis, byteSize: size)
        }
    }

    private func fileURL(for session: TraceSession) -> URL {
        directory.appending(
            path: "\(Self.filePrefix)\(session.startedAt)-\(session.sessionId.uuidString).\(Self.fileExtension)",
            directoryHint: .notDirectory
        )
    }

    private func encodeAndWrite(_ session: TraceSession) throws {
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(session)
            try data.write(to: fileURL(for: session), options: [.atomic])
        } catch {
            let nsError = error as NSError
            throw TraceStoreError.writeFailed("\(nsError.domain)(\(nsError.code))")
        }
    }

    private func decodeSession(at url: URL) -> TraceSession? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(TraceSession.self, from: data)
    }

    // MARK: - Dropped-session counter

    private struct StoredState: Codable {
        var schemaVersion = 1
        var droppedSessionCount = 0
        /// Absent in files written before the §9 session boundary landed; a missing key
        /// simply means nothing was open, which is the safe reading either way.
        var openSessionId: UUID?
        /// Absent in files written before the viability rule landed, where the honest
        /// reading is that nothing had been discarded for it yet.
        var nonViableDropCount: Int?
    }

    private var stateURL: URL {
        directory.appending(path: Self.stateFileName, directoryHint: .notDirectory)
    }

    private func loadStateIfNeeded() {
        guard !hasLoadedState else { return }
        hasLoadedState = true
        guard let data = try? Data(contentsOf: stateURL),
              let state = try? JSONDecoder().decode(StoredState.self, from: data)
        else { return }
        droppedSessionCount = state.droppedSessionCount
        openSessionIdValue = state.openSessionId
        nonViableDropCount = state.nonViableDropCount ?? 0
    }

    /// Best-effort: losing the counter costs one number in diagnostics, never a trace.
    private func persistState() {
        let state = StoredState(
            droppedSessionCount: droppedSessionCount,
            openSessionId: openSessionIdValue,
            nonViableDropCount: nonViableDropCount
        )
        guard let data = try? JSONEncoder().encode(state) else { return }
        try? data.write(to: stateURL, options: [.atomic])
    }
}

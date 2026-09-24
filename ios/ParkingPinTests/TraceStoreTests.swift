import Foundation
import Testing
@testable import ParkingPin

@Suite("Trace store")
struct TraceStoreTests {
    private func store(
        _ directory: TemporaryTraceDirectory,
        retention: TraceRetentionPolicy = .standard
    ) -> FileTraceStore {
        FileTraceStore(directory: directory.url, retention: retention)
    }

    private func session(startOffset: TimeInterval, events: Int = 0) -> TraceSession {
        TraceSession(
            sessionId: UUID(),
            metadata: TestTrace.metadata,
            startedAt: TestTime.offset(startOffset),
            endedAt: TestTime.offset(startOffset + 60),
            events: (0 ..< events).map {
                .motion(.walkingEnter, at: TestTime.offset(startOffset + Double($0)), confidence: .high)
            }
        )
    }

    // MARK: - Non-viable discard (§9)

    @Test("A discarded session leaves the disk and is counted apart from the rolling cap")
    func discardRemovesTheFileAndCountsSeparately() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let lonely = session(startOffset: 0, events: 1)
        try store.write(lonely)
        try store.write(session(startOffset: 600, events: 4))

        // Act
        store.discardNonViable(id: lonely.sessionId)

        // Assert
        #expect(store.load(id: lonely.sessionId) == nil)
        #expect(store.summary().sessionCount == 1)
        #expect(store.summary().nonViableDropCount == 1)
        // The rolling cap evicted nothing; conflating the two would hide which is happening.
        #expect(store.summary().droppedSessionCount == 0)
    }

    /// The counter matters most in a background launch nobody is watching, which is the
    /// one place it cannot be held in memory.
    @Test("The non-viable count outlives the process")
    func nonViableCountIsPersisted() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let first = session(startOffset: 0, events: 1)
        let second = session(startOffset: 600, events: 1)
        try store.write(first)
        try store.write(second)

        // Act
        store.discardNonViable(id: first.sessionId)
        store.discardNonViable(id: second.sessionId)

        // Assert
        #expect(FileTraceStore(directory: directory.url).summary().nonViableDropCount == 2)
    }

    @Test("Discarding the same session twice counts once")
    func discardIsIdempotent() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let lonely = session(startOffset: 0, events: 1)
        try store.write(lonely)

        // Act
        store.discardNonViable(id: lonely.sessionId)
        store.discardNonViable(id: lonely.sessionId)

        // Assert
        #expect(store.summary().nonViableDropCount == 1)
    }

    /// A pointer outliving the file it names would have the next launch try to reopen a
    /// session that is gone.
    @Test("Discarding the open session clears the open-session pointer")
    func discardClearsTheOpenPointer() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let lonely = session(startOffset: 0, events: 1)
        try store.write(lonely)
        store.setOpenSessionId(lonely.sessionId)

        // Act
        store.discardNonViable(id: lonely.sessionId)

        // Assert
        #expect(store.openSessionId == nil)
        #expect(FileTraceStore(directory: directory.url).openSessionId == nil)
    }

    // MARK: - Split replacement (§9)

    @Test("A split session is replaced on disk by its fragments")
    func replaceSwapsTheParentForItsFragments() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let parent = session(startOffset: 0, events: 6)
        try store.write(parent)
        let fragments = try TraceSessionSplit.split(parent, atEventIndex: 3)

        // Act
        try store.replace(parent.sessionId, with: [fragments.leading, fragments.trailing])

        // Assert
        #expect(store.load(id: parent.sessionId) == nil)
        #expect(store.load(id: fragments.leading.sessionId) == fragments.leading)
        #expect(store.load(id: fragments.trailing.sessionId) == fragments.trailing)
        #expect(store.summary().sessionCount == 2)
        #expect(store.summary().eventCount == 6)
        // A split is not an eviction; neither counter may move.
        #expect(store.summary().droppedSessionCount == 0)
        #expect(store.summary().nonViableDropCount == 0)
    }

    @Test("Splitting the session still being recorded is refused")
    func replaceRefusesTheOpenSession() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let parent = session(startOffset: 0, events: 6)
        try store.write(parent)
        store.setOpenSessionId(parent.sessionId)
        let fragments = try TraceSessionSplit.split(parent, atEventIndex: 3)

        // Act / Assert
        #expect(throws: TraceStoreError.sessionIsOpen) {
            try store.replace(parent.sessionId, with: [fragments.leading, fragments.trailing])
        }
        #expect(store.load(id: parent.sessionId) == parent)
        #expect(store.summary().sessionCount == 1)
    }

    @Test("Splitting a session that is gone is an error, not a silent write")
    func replaceRefusesAMissingSession() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let parent = session(startOffset: 0, events: 6)
        let fragments = try TraceSessionSplit.split(parent, atEventIndex: 3)

        // Act / Assert
        #expect(throws: TraceStoreError.sessionNotFound) {
            try store.replace(parent.sessionId, with: [fragments.leading, fragments.trailing])
        }
        #expect(store.summary().sessionCount == 0)
    }

    /// One session in, two out. A cap applied only when the recorder opens a session would
    /// let a run of splits carry the store past it and stay there.
    @Test("A split at the rolling cap evicts the oldest session rather than overrunning it")
    func replaceReappliesTheRollingCap() throws {
        // Arrange — exactly at the cap.
        let directory = TemporaryTraceDirectory()
        let store = store(directory, retention: TraceRetentionPolicy(maximumSessionCount: 3))
        let oldest = session(startOffset: 0, events: 2)
        try store.write(oldest)
        try store.write(session(startOffset: 600, events: 2))
        let parent = session(startOffset: 1200, events: 6)
        try store.write(parent)
        #expect(store.summary().sessionCount == 3)

        // Act
        let fragments = try TraceSessionSplit.split(parent, atEventIndex: 3)
        try store.replace(parent.sessionId, with: [fragments.leading, fragments.trailing])

        // Assert — still three, and the evicted one is the oldest, counted as a cap drop.
        #expect(store.summary().sessionCount == 3)
        #expect(store.load(id: oldest.sessionId) == nil)
        #expect(store.summary().droppedSessionCount == 1)
        #expect(store.load(id: fragments.leading.sessionId) != nil)
        #expect(store.load(id: fragments.trailing.sessionId) != nil)
    }

    @Test("The cap never evicts the session being recorded to make room for a split")
    func replaceProtectsTheOpenSessionFromTheCap() throws {
        // Arrange — the open session is also the oldest.
        let directory = TemporaryTraceDirectory()
        let store = store(directory, retention: TraceRetentionPolicy(maximumSessionCount: 2))
        let open = session(startOffset: 0, events: 2)
        try store.write(open)
        store.setOpenSessionId(open.sessionId)
        let parent = session(startOffset: 600, events: 6)
        try store.write(parent)

        // Act
        let fragments = try TraceSessionSplit.split(parent, atEventIndex: 3)
        try store.replace(parent.sessionId, with: [fragments.leading, fragments.trailing])

        // Assert
        #expect(store.load(id: open.sessionId) != nil)
        #expect(store.summary().sessionCount == 2)
    }

    // MARK: - Gap aggregate (§9)

    @Test("The summary aggregates gaps over the sessions on disk")
    func summaryAggregatesGapStats() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        try store.write(measured(startOffset: 0, gapsMinutes: [1, 2]))
        try store.write(measured(startOffset: 3600, gapsMinutes: [12, 25]))
        try store.write(measured(startOffset: 7200, gapsMinutes: [14]))

        // Act
        let summary = store.summary()

        // Assert
        #expect(summary.measuredSessionCount == 3)
        #expect(summary.maxGapMillis == 25 * 60 * 1000)
        #expect(summary.sessionsOver10MinGapCount == 2)
        #expect(summary.sessionsOver20MinGapCount == 1)
    }

    /// A trace recorded before the measurement landed was never measured, and reading its
    /// absence as "no long gaps" would understate the very tail the threshold is being
    /// re-set from.
    @Test("An unmeasured session is excluded from the aggregate rather than counted as zero")
    func unmeasuredSessionsAreNotCountedAsZero() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        try store.write(session(startOffset: 0, events: 3))
        try store.write(measured(startOffset: 3600, gapsMinutes: [22]))

        // Act
        let summary = store.summary()

        // Assert
        #expect(summary.sessionCount == 2)
        #expect(summary.measuredSessionCount == 1)
        #expect(summary.maxGapMillis == 22 * 60 * 1000)
        #expect(summary.sessionsOver20MinGapCount == 1)
    }

    /// A session with fewer than two events observed no gap. Zero is the honest reading —
    /// and it must not be mistaken for the largest gap on disk.
    @Test("A measured session with no observable gap contributes zero")
    func aSessionWithNoGapContributesZero() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        try store.write(measured(startOffset: 0, gapsMinutes: []))

        // Act / Assert
        #expect(store.summary().measuredSessionCount == 1)
        #expect(store.summary().maxGapMillis == 0)
    }

    private func measured(startOffset: TimeInterval, gapsMinutes: [Double]) -> TraceSession {
        var offset = startOffset
        var events: [TraceEvent] = [.motion(.walkingEnter, at: TestTime.offset(offset), confidence: .high)]
        for minutes in gapsMinutes {
            offset += minutes * 60
            events.append(.motion(.stationaryEnter, at: TestTime.offset(offset), confidence: .high))
        }
        return TraceSession(
            sessionId: UUID(),
            metadata: TestTrace.metadata,
            startedAt: TestTime.offset(startOffset),
            endedAt: TestTime.offset(offset),
            events: events,
            gapStats: TraceGapStats(events: events)
        )
    }

    @Test("A written session round-trips off disk")
    func roundTripsThroughFile() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let original = session(startOffset: 0, events: 3)

        // Act
        try store.write(original)

        // Assert
        #expect(store.load(id: original.sessionId) == original)
        #expect(store.summaries().map(\.id) == [original.sessionId])
    }

    @Test("Summaries come back newest first")
    func summariesAreNewestFirst() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let older = session(startOffset: 0)
        let newer = session(startOffset: 3600)

        // Act
        try store.write(older)
        try store.write(newer)

        // Assert
        #expect(store.summaries().map(\.id) == [newer.sessionId, older.sessionId])
    }

    /// docs/05 §9: "롤링 상한을 두고 오래된 세션부터 버린다."
    @Test("The rolling cap discards the oldest sessions first")
    func rollingCapDropsOldestFirst() throws {
        // Arrange — one over the ceiling.
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let overflow = TraceRetentionPolicy.standard.maximumSessionCount + 5
        let written = try (0 ..< overflow).map { index -> TraceSession in
            let session = session(startOffset: Double(index) * 3600)
            try store.write(session)
            return session
        }

        // Act
        store.prune(protecting: nil)

        // Assert
        let survivors = Set(store.summaries().map(\.id))
        #expect(survivors.count == TraceRetentionPolicy.standard.maximumSessionCount)
        #expect(!survivors.contains(written[0].sessionId))
        #expect(!survivors.contains(written[4].sessionId))
        #expect(survivors.contains(written[5].sessionId))
        #expect(survivors.contains(written[overflow - 1].sessionId))
    }

    /// An eviction nobody can see looks exactly like recording that never happened, so
    /// §9 requires the count to be exposed.
    @Test("Dropped sessions are counted, and the count outlives the process")
    func droppedCountIsPersisted() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let overflow = TraceRetentionPolicy.standard.maximumSessionCount + 3
        for index in 0 ..< overflow {
            try store.write(session(startOffset: Double(index) * 3600))
        }

        // Act
        store.prune(protecting: nil)
        // A fresh instance is what the next process launch sees.
        let reopened = FileTraceStore(directory: directory.url)

        // Assert
        #expect(store.summary().droppedSessionCount == 3)
        #expect(reopened.summary().droppedSessionCount == 3)
        #expect(reopened.summary().sessionCount == TraceRetentionPolicy.standard.maximumSessionCount)
    }

    @Test("The session still being recorded is never evicted")
    func openSessionSurvivesTheCap() throws {
        // Arrange — the oldest session is the one currently open.
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let open = session(startOffset: 0)
        try store.write(open)
        for index in 1 ... TraceRetentionPolicy.standard.maximumSessionCount + 2 {
            try store.write(session(startOffset: Double(index) * 3600))
        }

        // Act
        store.prune(protecting: open.sessionId)

        // Assert
        #expect(store.summaries().contains { $0.id == open.sessionId })
    }

    /// The count ceiling alone would let a handful of very long trips fill the disk, so
    /// the byte ceiling has to bite independently of it.
    @Test("The byte ceiling evicts even when the session count is within bounds")
    func byteCeilingEvicts() throws {
        // Arrange — a byte ceiling roughly two sessions wide, a count ceiling far away.
        let directory = TemporaryTraceDirectory()
        let probe = TemporaryTraceDirectory()
        let sizingStore = store(probe)
        let sample = session(startOffset: 0, events: 20)
        try sizingStore.write(sample)
        let sessionBytes = try #require(
            FileManager.default.contentsOfDirectory(at: probe.url, includingPropertiesForKeys: [.fileSizeKey])
                .compactMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize }
                .max()
        )
        let store = store(directory, retention: TraceRetentionPolicy(
            maximumSessionCount: 100,
            maximumTotalBytes: sessionBytes * 2
        ))
        for index in 0 ..< 6 {
            try store.write(session(startOffset: Double(index) * 3600, events: 20))
        }

        // Act
        store.prune(protecting: nil)

        // Assert
        let summaries = store.summaries()
        #expect(summaries.count == 2)
        #expect(store.summary().droppedSessionCount == 4)
        // Whatever survived is the newest, never a hole in the middle.
        #expect(summaries.first?.startedAt == TestTime.offset(5 * 3600))
        #expect(summaries.last?.startedAt == TestTime.offset(4 * 3600))
    }

    // MARK: - Labels

    @Test("A label is written into the session and counted")
    func labelUpdatePersists() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let original = session(startOffset: 0, events: 2)
        try store.write(original)
        let label = TraceLabel(mode: .subway, parked: false, note: "환승 2회")

        // Act
        try store.updateLabel(label, for: original.sessionId)

        // Assert
        #expect(store.load(id: original.sessionId)?.label == label)
        #expect(store.summary().unlabeledSessionCount == 0)
        #expect(store.summary().sessionCount == 1)
        #expect(store.summary().eventCount == 2)
    }

    /// The open session's file is rewritten on every event. Without this, labelling a ride
    /// while still on it would be silently undone by the next fix.
    @Test("A label survives the next append to the same session")
    func labelSurvivesRewrite() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        var open = session(startOffset: 0, events: 1)
        try store.write(open)
        try store.updateLabel(TraceLabel(mode: .taxi, parked: true, note: nil), for: open.sessionId)

        // Act — the recorder appends another event and rewrites, still unlabeled.
        open.events.append(.motion(.walkingEnter, at: TestTime.offset(30), confidence: .high))
        try store.write(open)

        // Assert
        #expect(store.load(id: open.sessionId)?.label.mode == .taxi)
        #expect(store.load(id: open.sessionId)?.events.count == 2)
    }

    @Test("Labelling a session that is gone is an error, not a silent no-op")
    func labellingAMissingSessionThrows() {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)

        // Act / Assert
        #expect(throws: TraceStoreError.sessionNotFound) {
            try store.updateLabel(.unlabeled, for: UUID())
        }
    }

    @Test("The summary counts sessions, events and the ones still needing a human")
    func summaryCounts() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let labelled = session(startOffset: 0, events: 3)
        try store.write(labelled)
        try store.write(session(startOffset: 3600, events: 5))
        try store.updateLabel(TraceLabel(mode: .car, parked: true, note: nil), for: labelled.sessionId)

        // Act
        let summary = store.summary()

        // Assert
        #expect(summary.sessionCount == 2)
        #expect(summary.eventCount == 8)
        #expect(summary.unlabeledSessionCount == 1)
        #expect(summary.droppedSessionCount == 0)
    }

    @Test("An empty directory reports zeros rather than failing")
    func emptyDirectoryIsNotAFailure() {
        // Arrange
        let directory = TemporaryTraceDirectory()

        // Act
        let summary = store(directory).summary()

        // Assert
        #expect(summary == .empty)
        #expect(store(directory).summaries().isEmpty)
    }

    /// A file that is not a trace — a half-written temp file, a stray `_state.json` —
    /// must not take the whole history down with it.
    @Test("Unrelated files in the directory are ignored")
    func foreignFilesAreIgnored() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = store(directory)
        let valid = session(startOffset: 0)
        try store.write(valid)
        try Data("not json".utf8).write(to: directory.url.appending(path: "notes.txt"))
        try Data("{}".utf8).write(to: directory.url.appending(path: "trace-bogus.json"))

        // Act
        let summaries = FileTraceStore(directory: directory.url).summaries()

        // Assert
        #expect(summaries.map(\.id) == [valid.sessionId])
    }
}

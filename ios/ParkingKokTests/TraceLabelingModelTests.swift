import Foundation
import Testing
@testable import ParkingKok

/// The path the split button actually runs: screen → `TraceLabelingModel` →
/// `TraceSessionSplit` → `FileTraceStore`, against real files rather than a stub.
///
/// It exists because the three pieces are each tested alone and the interesting failures
/// are between them — a refusal swallowed instead of shown, a parent left on disk beside
/// its fragments, a list that still shows the session that was just replaced.
@Suite("Trace labelling model")
@MainActor
struct TraceLabelingModelTests {
    private func fixture(events: Int, spacingMinutes: Double = 1) -> TraceSession {
        var offset = 0.0
        var list: [TraceEvent] = []
        for index in 0 ..< events {
            list.append(.motion(
                index.isMultiple(of: 2) ? .stationaryEnter : .stationaryExit,
                at: TestTime.offset(offset),
                confidence: .medium
            ))
            offset += spacingMinutes * 60
        }
        return TraceSession(
            sessionId: UUID(),
            metadata: TestTrace.metadata,
            startedAt: TestTime.offset(0),
            endedAt: list[list.count - 1].date,
            label: TraceLabel(mode: .subway, parked: false, note: "사무실 대기 포함"),
            events: list,
            gapStats: TraceGapStats(events: list)
        )
    }

    @Test("Splitting from the screen replaces the session on disk with two labellable halves")
    func splitReplacesTheSessionOnDisk() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = FileTraceStore(directory: directory.url)
        let parent = fixture(events: 8)
        try store.write(parent)
        let model = TraceLabelingModel(store: store)
        model.refresh()
        #expect(model.summaries.count == 1)

        // Act
        let refusal = model.split(sessionId: parent.sessionId, atEventIndex: 4)

        // Assert
        #expect(refusal == nil)
        #expect(model.summaries.count == 2)
        // Bound outside `#expect`: a key-path `allSatisfy` inside the macro expansion loses
        // its non-throwing shape, and SwiftFormat rewrites the equivalent closure back to a
        // key path, so the two only agree out here.
        let bothAreFragments = model.summaries.allSatisfy(\.isSplitFragment)
        let bothNeedALabel = model.summaries.allSatisfy { !$0.label.isLabeled }
        #expect(bothAreFragments)
        #expect(bothNeedALabel)
        #expect(store.load(id: parent.sessionId) == nil)

        // And each half can now be labelled on its own, which is the point of splitting.
        let halves = model.summaries
        model.save(TraceLabel(mode: .still, parked: false, note: nil), for: halves[0].id)
        model.save(TraceLabel(mode: .subway, parked: false, note: nil), for: halves[1].id)
        #expect(model.failure == nil)
        #expect(Set(model.summaries.map(\.label.mode)) == [.still, .subway])
        #expect(model.summary.unlabeledSessionCount == 0)
    }

    /// The refusal has to reach the screen. Swallowed, the user would tap and see nothing
    /// change, with no way to learn that the cut they chose was the problem.
    @Test("A cut that would leave a one-event fragment comes back as a reason, not silence")
    func refusalIsReturnedToTheScreen() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = FileTraceStore(directory: directory.url)
        let parent = fixture(events: 4)
        try store.write(parent)
        let model = TraceLabelingModel(store: store)
        model.refresh()

        // Act
        let refusal = model.split(sessionId: parent.sessionId, atEventIndex: 1)

        // Assert
        #expect(refusal != nil)
        #expect(refusal?.contains("1개") == true)
        // Nothing moved on disk.
        #expect(model.summaries.count == 1)
        #expect(store.load(id: parent.sessionId) == parent)
    }

    @Test("The session still being recorded is refused with the reason §9 gives")
    func openSessionIsRefused() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = FileTraceStore(directory: directory.url)
        let parent = fixture(events: 8)
        try store.write(parent)
        store.setOpenSessionId(parent.sessionId)
        let model = TraceLabelingModel(store: store)
        model.refresh()

        // Act
        let refusal = model.split(sessionId: parent.sessionId, atEventIndex: 4)

        // Assert
        #expect(refusal == "기록 중인 세션은 나눌 수 없습니다. 닫힌 세션만 가능합니다.")
        #expect(model.isOpen(parent.sessionId))
        #expect(model.summaries.count == 1)
    }

    @Test("A fragment can itself be split again, and the provenance follows the cut")
    func aFragmentCanBeSplitAgain() throws {
        // Arrange
        let directory = TemporaryTraceDirectory()
        let store = FileTraceStore(directory: directory.url)
        let parent = fixture(events: 8)
        try store.write(parent)
        let model = TraceLabelingModel(store: store)
        model.refresh()
        #expect(model.split(sessionId: parent.sessionId, atEventIndex: 4) == nil)

        // Act — cut the four-event half in two again.
        let firstFragment = try #require(model.summaries.first { $0.eventCount == 4 })
        let refusal = model.split(sessionId: firstFragment.id, atEventIndex: 2)

        // Assert
        #expect(refusal == nil)
        #expect(model.summaries.count == 3)
        #expect(model.summary.eventCount == 8)
        // The new halves point at the fragment they came from, not at the original.
        let regrandchildren = model.summaries.filter { $0.splitFrom?.parentSessionId == firstFragment.id }
        #expect(regrandchildren.count == 2)
    }
}

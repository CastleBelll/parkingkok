import Foundation
import Testing
@testable import ParkingPin

/// docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 "사람이 세션을 나눈다".
@Suite("Trace session split")
struct TraceSessionSplitTests {
    private func session(eventCount: Int, spacing: TimeInterval = 60) -> TraceSession {
        TestTrace.session(eventCount: eventCount, spacing: spacing)
    }

    // MARK: - The fragments are whole sessions

    @Test("Each fragment is a session in its own right, not a slice of the parent")
    func fragmentsAreWholeSessions() throws {
        // Arrange
        let parent = session(eventCount: 6)

        // Act
        let split = try TraceSessionSplit.split(parent, atEventIndex: 3)

        // Assert
        #expect(split.leading.events.count == 3)
        #expect(split.trailing.events.count == 3)
        #expect(split.leading.sessionId != parent.sessionId)
        #expect(split.trailing.sessionId != parent.sessionId)
        #expect(split.leading.sessionId != split.trailing.sessionId)
        // Own boundaries, taken from their own events.
        #expect(split.leading.startedAt == parent.startedAt)
        #expect(split.leading.endedAt == parent.events[2].atMillis)
        #expect(split.trailing.startedAt == parent.events[3].atMillis)
        #expect(split.trailing.endedAt == parent.events[5].atMillis)
        // No event is lost and none is duplicated.
        #expect(split.leading.events + split.trailing.events == parent.events)
    }

    @Test("Both fragments record the same provenance, so either one finds the pair")
    func provenanceIsRecordedOnBothHalves() throws {
        // Arrange
        let parent = session(eventCount: 6)

        // Act
        let split = try TraceSessionSplit.split(parent, atEventIndex: 4)

        // Assert
        let expected = TraceSplitOrigin(
            parentSessionId: parent.sessionId,
            atMillis: parent.events[4].atMillis
        )
        #expect(split.leading.splitFrom == expected)
        #expect(split.trailing.splitFrom == expected)
    }

    @Test("A fragment carries its own gap measurement, not the parent's")
    func gapStatsAreRecomputedPerFragment() throws {
        // Arrange — the long silence sits entirely inside the leading half.
        let events = [
            TraceEvent.motion(.stationaryEnter, at: TestTime.offset(0), confidence: .medium),
            .motion(.stationaryExit, at: TestTime.offset(25 * 60), confidence: .medium),
            .motion(.walkingEnter, at: TestTime.offset(25 * 60 + 30), confidence: .high),
            .motion(.vehicleEnter, at: TestTime.offset(25 * 60 + 90), confidence: .high)
        ]
        let parent = TestTrace.session(
            startedAt: TestTime.offset(0),
            endedAt: TestTime.offset(25 * 60 + 90),
            events: events,
            gapStats: TraceGapStats(events: events)
        )
        let twentyFiveMinutes: Int64 = 25 * 60 * 1000
        #expect(parent.gapStats?.maxGapMillis == twentyFiveMinutes)

        // Act
        let split = try TraceSessionSplit.split(parent, atEventIndex: 2)

        // Assert
        let leading = try #require(split.leading.gapStats)
        let trailing = try #require(split.trailing.gapStats)
        #expect(leading.maxGapMillis == twentyFiveMinutes)
        #expect(leading.gapsOver20MinCount == 1)
        // The trailing half never contained that silence and must not claim it.
        #expect(trailing.maxGapMillis == 60 * 1000)
        #expect(trailing.gapsOver10MinCount == 0)
    }

    /// The reason to split is to label each half separately, so inheriting the parent's
    /// label would assert about the half it was never true of.
    @Test("Fragments come back unlabelled, keeping only the free-text note")
    func fragmentsDropTheParentLabelButKeepTheNote() throws {
        // Arrange
        let parent = TestTrace.session(
            startedAt: TestTime.offset(0),
            endedAt: TestTime.offset(180),
            label: TraceLabel(mode: .subway, parked: true, note: "사무실 대기 후 퇴근"),
            events: session(eventCount: 4).events
        )

        // Act
        let split = try TraceSessionSplit.split(parent, atEventIndex: 2)

        // Assert
        for fragment in [split.leading, split.trailing] {
            #expect(fragment.label.mode == .unknown)
            #expect(fragment.label.parked == nil)
            #expect(fragment.label.isLabeled == false)
            #expect(fragment.label.note == "사무실 대기 후 퇴근")
        }
    }

    @Test("A fragment keeps the device and build the parent was recorded on")
    func fragmentsKeepDeviceMetadata() throws {
        // Arrange / Act
        let split = try TraceSessionSplit.split(session(eventCount: 4), atEventIndex: 2)

        // Assert
        #expect(split.leading.deviceModel == TestTrace.metadata.deviceModel)
        #expect(split.trailing.osVersion == TestTrace.metadata.osVersion)
        #expect(split.trailing.appVersion == TestTrace.metadata.appVersion)
        #expect(split.trailing.platform == "ios")
    }

    // MARK: - The viability rule applies to fragments too

    @Test("A split that would leave one event on either side is refused")
    func aSplitLeavingASingleEventIsRefused() throws {
        // Arrange
        let parent = session(eventCount: 4)

        // Act / Assert — index 1 leaves 1 event leading, index 3 leaves 1 trailing.
        #expect(throws: TraceSplitError.fragmentNotViable(leadingEventCount: 1, trailingEventCount: 3)) {
            try TraceSessionSplit.split(parent, atEventIndex: 1)
        }
        #expect(throws: TraceSplitError.fragmentNotViable(leadingEventCount: 3, trailingEventCount: 1)) {
            try TraceSessionSplit.split(parent, atEventIndex: 3)
        }
    }

    @Test("The smallest session that can be split at all is four events")
    func fourEventsIsTheSmallestSplittableSession() throws {
        // Arrange / Act / Assert
        #expect(throws: TraceSplitError.self) {
            try TraceSessionSplit.split(session(eventCount: 3), atEventIndex: 1)
        }
        #expect(throws: TraceSplitError.self) {
            try TraceSessionSplit.split(session(eventCount: 3), atEventIndex: 2)
        }
        let split = try TraceSessionSplit.split(session(eventCount: 4), atEventIndex: 2)
        #expect(split.leading.events.count == 2)
        #expect(split.trailing.events.count == 2)
    }

    @Test("A cut outside the event list is refused rather than clamped")
    func outOfRangeIndexIsRefused() {
        // Arrange
        let parent = session(eventCount: 6)

        // Act / Assert — clamping would silently split somewhere the user did not choose.
        #expect(throws: TraceSplitError.indexOutOfRange) {
            try TraceSessionSplit.split(parent, atEventIndex: 0)
        }
        #expect(throws: TraceSplitError.indexOutOfRange) {
            try TraceSessionSplit.split(parent, atEventIndex: 6)
        }
        #expect(throws: TraceSplitError.indexOutOfRange) {
            try TraceSessionSplit.split(parent, atEventIndex: -1)
        }
    }

    // MARK: - The encoded shape

    @Test("`splitFrom` appears in the encoded session, and only on a fragment")
    func splitFromIsEncodedOnlyOnFragments() throws {
        // Arrange
        let parent = session(eventCount: 4)
        let split = try TraceSessionSplit.split(parent, atEventIndex: 2)

        // Act
        let encoder = JSONEncoder()
        let parentObject = try JSONSerialization
            .jsonObject(with: encoder.encode(parent)) as? [String: Any]
        let fragmentObject = try #require(
            try JSONSerialization.jsonObject(with: encoder.encode(split.trailing)) as? [String: Any]
        )

        // Assert — §9: an absent optional loses its key entirely.
        #expect(parentObject?["splitFrom"] == nil)
        let origin = try #require(fragmentObject["splitFrom"] as? [String: Any])
        #expect(origin["parentSessionId"] as? String == parent.sessionId.uuidString)
        #expect(origin["atMillis"] as? Int64 == parent.events[2].atMillis)
    }

    @Test("A fragment survives a round trip through JSON unchanged")
    func fragmentRoundTrips() throws {
        // Arrange
        let split = try TraceSessionSplit.split(session(eventCount: 6), atEventIndex: 3)

        // Act
        let data = try JSONEncoder().encode(split.trailing)
        let decoded = try JSONDecoder().decode(TraceSession.self, from: data)

        // Assert
        #expect(decoded == split.trailing)
    }
}

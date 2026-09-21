import Foundation
import SwiftData
import Testing
@testable import ParkingPin

/// The local parking store — docs/06 §2's record schema, §8's completion commit,
/// FR-004's one-active-parking rule.
@MainActor
struct ParkingStoreTests {
    private static let start = Date(timeIntervalSince1970: 1_700_000_000)
    private var start: Date {
        Self.start
    }

    private func makeStore(
        clock: any DateProviding = FixedDateProvider(ParkingStoreTests.start)
    ) throws -> SwiftDataParkingStore {
        try SwiftDataParkingStore(container: SwiftDataParkingStore.makeInMemoryContainer(), clock: clock)
    }

    private func session(
        id: UUID = UUID(),
        startedAt: Date? = nil,
        source: ParkingSource = .manual,
        location: ParkedLocation? = nil,
        floor: FloorValue? = FloorValue.parse("B3"),
        zone: String? = "A구역",
        spot: String? = "142",
        memo: String? = nil
    ) -> ParkingSession {
        let startedAt = startedAt ?? start
        return ParkingSession(
            id: id,
            startedAt: startedAt,
            endedAt: nil,
            source: source,
            confidenceBucket: nil,
            location: location,
            floor: floor,
            zone: zone,
            spot: spot,
            memo: memo,
            photoRelativePath: nil,
            createdAt: startedAt,
            updatedAt: startedAt
        )
    }

    // ── Round trip ──────────────────────────────────────────────────────────

    @Test("Every field of docs/06 §2's schema survives a write and a read")
    func roundTripsEveryField() throws {
        // Arrange
        let store = try makeStore()
        let location = ParkedLocation(
            latitude: 37.123_456_7,
            longitude: 127.987_654_3,
            horizontalAccuracy: 18,
            capturedAt: start.addingTimeInterval(-30)
        )
        var original = session(location: location, memo: "기둥 옆")
        original.confidenceBucket = .high

        // Act
        try store.startSession(original)
        let restored = try store.activeSession()

        // Assert
        #expect(restored?.id == original.id)
        #expect(restored?.startedAt == original.startedAt)
        #expect(restored?.endedAt == nil)
        #expect(restored?.source == .manual)
        #expect(restored?.confidenceBucket == .high)
        #expect(restored?.location == location)
        #expect(restored?.floor?.displayText == "B3")
        #expect(restored?.floor?.kind == .basement)
        #expect(restored?.zone == "A구역")
        #expect(restored?.spot == "142")
        #expect(restored?.memo == "기둥 옆")
        #expect(restored?.isActive == true)
    }

    // ── FR-001: no permission, no location, still saved ─────────────────────

    @Test("A parking saved with no location at all round-trips — FR-001's whole point")
    func savesAndReadsBackWithoutAnyLocation() throws {
        // Arrange — the shape of a record written while every permission is denied.
        let store = try makeStore()
        let withoutLocation = session(location: nil)

        // Act
        try store.startSession(withoutLocation)
        let restored = try store.activeSession()

        // Assert
        #expect(restored?.id == withoutLocation.id)
        #expect(restored?.location == nil)
        #expect(restored?.floor?.displayText == "B3")
        #expect(restored?.zone == "A구역")
    }

    @Test("A half-written location is read back as no location rather than a partial one")
    func rejectsPartialLocationColumns() throws {
        // Arrange — a latitude with no accuracy cannot be drawn honestly (FR-008).
        let container = try SwiftDataParkingStore.makeInMemoryContainer()
        let store = SwiftDataParkingStore(container: container)
        let context = ModelContext(container)
        let id = UUID()
        context.insert(
            ParkingRecord(
                id: id, startedAt: start, endedAt: nil, source: "manual", confidenceBucket: nil,
                latitude: 37.5, longitude: 127.0, horizontalAccuracy: nil, locationCapturedAt: nil,
                floorRaw: nil, floorKind: nil, floorNumber: nil, zone: nil, spot: nil, memo: nil,
                photoRelativePath: nil, createdAt: start, updatedAt: start
            )
        )
        try context.save()

        // Act
        let restored = try store.session(id: id)

        // Assert
        #expect(restored != nil)
        #expect(restored?.location == nil)
    }

    // ── FR-004: one active parking ──────────────────────────────────────────

    @Test("A second active parking is refused, naming the one already open")
    func refusesSecondActiveSession() throws {
        // Arrange
        let store = try makeStore()
        let first = session()
        try store.startSession(first)

        // Act / Assert
        #expect(throws: ParkingStoreError.activeSessionExists(existing: first.id)) {
            try store.startSession(session(startedAt: start.addingTimeInterval(600)))
        }
        #expect(try store.activeSession()?.id == first.id)
    }

    @Test("A new parking is allowed once the previous one has ended")
    func allowsNewSessionAfterEnding() throws {
        // Arrange
        let store = try makeStore()
        let first = session()
        try store.startSession(first)
        try store.endSession(id: first.id, at: start.addingTimeInterval(3600))

        // Act
        let second = session(startedAt: start.addingTimeInterval(7200))
        try store.startSession(second)

        // Assert
        #expect(try store.activeSession()?.id == second.id)
        #expect(try store.completedSessions(limit: nil).map(\.id) == [first.id])
    }

    // ── docs/06 §8: completion commit ───────────────────────────────────────

    @Test("Ending a parking keeps the same id and moves it into history")
    func endingKeepsTheSameRecord() throws {
        // Arrange
        let store = try makeStore()
        let active = session()
        try store.startSession(active)
        let endedAt = start.addingTimeInterval(5040)

        // Act
        try store.endSession(id: active.id, at: endedAt)

        // Assert — §8: "insert completed record with same sessionId".
        #expect(try store.activeSession() == nil)
        let completed = try store.completedSessions(limit: nil)
        #expect(completed.count == 1)
        #expect(completed.first?.id == active.id)
        #expect(completed.first?.endedAt == endedAt)
        #expect(completed.first?.isActive == false)
    }

    @Test("Ending a record that is gone is an error, not a silent no-op")
    func endingUnknownRecordThrows() throws {
        // Arrange
        let store = try makeStore()
        let missing = UUID()

        // Act / Assert
        #expect(throws: ParkingStoreError.notFound(missing)) {
            try store.endSession(id: missing, at: start)
        }
    }

    // ── Ordering and limits ─────────────────────────────────────────────────

    @Test("Completed records come back newest first")
    func ordersCompletedNewestFirst() throws {
        // Arrange
        let store = try makeStore()
        let ids = try (0 ..< 3).map { index -> UUID in
            let record = session(startedAt: start.addingTimeInterval(TimeInterval(index) * 3600))
            try store.startSession(record)
            try store.endSession(id: record.id, at: start.addingTimeInterval(TimeInterval(index) * 3600 + 60))
            return record.id
        }

        // Act
        let completed = try store.completedSessions(limit: nil)

        // Assert
        #expect(completed.map(\.id) == ids.reversed())
    }

    @Test("A limit takes the newest records, not an arbitrary slice")
    func appliesLimitToNewest() throws {
        // Arrange
        let store = try makeStore()
        var newest: UUID?
        for index in 0 ..< 5 {
            let record = session(startedAt: start.addingTimeInterval(TimeInterval(index) * 3600))
            try store.startSession(record)
            try store.endSession(id: record.id, at: start.addingTimeInterval(TimeInterval(index) * 3600 + 60))
            newest = record.id
        }

        // Act
        let limited = try store.completedSessions(limit: 2)

        // Assert
        #expect(limited.count == 2)
        #expect(limited.first?.id == newest)
    }

    @Test("The active record never appears in the completed list")
    func excludesActiveFromHistory() throws {
        // Arrange
        let store = try makeStore()
        try store.startSession(session())

        // Act / Assert
        #expect(try store.completedSessions(limit: nil).isEmpty)
    }

    // ── Updates and validation ──────────────────────────────────────────────

    @Test("An update rewrites the mutable fields and stamps updatedAt from the clock")
    func updatesMutableFields() throws {
        // Arrange
        let clock = FixedDateProvider(start.addingTimeInterval(900))
        let store = try makeStore(clock: clock)
        var record = session()
        try store.startSession(record)

        // Act
        record.floor = FloorValue.parse("B2")
        record.zone = "C구역"
        try store.update(record)

        // Assert
        let restored = try store.activeSession()
        #expect(restored?.floor?.displayText == "B2")
        #expect(restored?.zone == "C구역")
        #expect(restored?.updatedAt == clock.now)
        // Identity fields are not rewritten by an update.
        #expect(restored?.startedAt == start)
        #expect(restored?.createdAt == start)
    }

    @Test("FR-006 caps zone and spot at 40 characters on the way in")
    func trimsOverlongFields() throws {
        // Arrange
        let store = try makeStore()
        let long = String(repeating: "가", count: 60)

        // Act
        try store.startSession(session(zone: long, spot: long))

        // Assert
        let restored = try store.activeSession()
        #expect(restored?.zone?.count == ParkingSession.maximumFieldLength)
        #expect(restored?.spot?.count == ParkingSession.maximumFieldLength)
    }

    @Test("Whitespace-only text is stored as absence, so nil and empty cannot both mean none")
    func normalizesBlankFieldsToNil() throws {
        // Arrange
        let store = try makeStore()

        // Act
        try store.startSession(session(zone: "   ", spot: "", memo: "\n "))

        // Assert
        let restored = try store.activeSession()
        #expect(restored?.zone == nil)
        #expect(restored?.spot == nil)
        #expect(restored?.memo == nil)
    }

    // ── Deletion ────────────────────────────────────────────────────────────

    @Test("Deleting one record leaves the others alone")
    func deletesOneRecord() throws {
        // Arrange
        let store = try makeStore()
        let kept = session()
        try store.startSession(kept)
        try store.endSession(id: kept.id, at: start.addingTimeInterval(60))
        let removed = session(startedAt: start.addingTimeInterval(7200))
        try store.startSession(removed)

        // Act
        try store.delete(id: removed.id)

        // Assert
        #expect(try store.activeSession() == nil)
        #expect(try store.completedSessions(limit: nil).map(\.id) == [kept.id])
    }

    @Test("Deleting everything clears the active parking too (docs/02 §15)")
    func deletesEverythingIncludingActive() throws {
        // Arrange
        let store = try makeStore()
        let finished = session()
        try store.startSession(finished)
        try store.endSession(id: finished.id, at: start.addingTimeInterval(60))
        try store.startSession(session(startedAt: start.addingTimeInterval(7200)))

        // Act
        try store.deleteAll()

        // Assert
        #expect(try store.activeSession() == nil)
        #expect(try store.completedSessions(limit: nil).isEmpty)
    }

    @Test("Deleting a record that is gone is an error, not a silent no-op")
    func deletingUnknownRecordThrows() throws {
        // Arrange
        let store = try makeStore()
        let missing = UUID()

        // Act / Assert
        #expect(throws: ParkingStoreError.notFound(missing)) {
            try store.delete(id: missing)
        }
    }

    // ── Forward compatibility ───────────────────────────────────────────────

    @Test("A source this build does not recognise degrades instead of dropping the record")
    func toleratesUnknownStoredValues() throws {
        // Arrange — the shape a newer build could leave behind.
        let container = try SwiftDataParkingStore.makeInMemoryContainer()
        let store = SwiftDataParkingStore(container: container)
        let context = ModelContext(container)
        let id = UUID()
        context.insert(
            ParkingRecord(
                id: id, startedAt: start, endedAt: nil, source: "imported", confidenceBucket: "certain",
                latitude: nil, longitude: nil, horizontalAccuracy: nil, locationCapturedAt: nil,
                floorRaw: "B3", floorKind: "mezzanine", floorNumber: 3, zone: nil, spot: nil, memo: nil,
                photoRelativePath: nil, createdAt: start, updatedAt: start
            )
        )
        try context.save()

        // Act
        let restored = try store.session(id: id)

        // Assert — the parking is still there, with the floor text intact.
        #expect(restored?.source == .manual)
        #expect(restored?.confidenceBucket == nil)
        #expect(restored?.floor?.raw == "B3")
        #expect(restored?.floor?.kind == .freeText)
        #expect(restored?.floor?.isSteppable == false)
    }
}

/// docs/04 §11 requires the versioned schema and plan to exist from v1.
struct ParkingSchemaTests {
    @Test("The store declares a versioned schema at 1.0.0")
    func declaresVersionOne() {
        #expect(ParkingSchemaV1.versionIdentifier == Schema.Version(1, 0, 0))
        #expect(ParkingSchemaV1.models.count == 1)
    }

    @Test("A migration plan exists and starts from V1, so v2 has somewhere to attach")
    func declaresMigrationPlan() {
        // Arrange / Act
        let schemas = ParkingMigrationPlan.schemas

        // Assert
        #expect(schemas.count == 1)
        #expect(schemas.first?.versionIdentifier == ParkingSchemaV1.versionIdentifier)
        // No migration to perform yet — the plan exists so the first one is not ad hoc.
        #expect(ParkingMigrationPlan.stages.isEmpty)
    }

    @Test("The container opens through the migration plan rather than a bare schema")
    @MainActor
    func opensContainerThroughThePlan() throws {
        // Arrange / Act
        let container = try SwiftDataParkingStore.makeInMemoryContainer()

        // Assert
        #expect(container.schema.version == ParkingSchemaV1.versionIdentifier)
    }
}

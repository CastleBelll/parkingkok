import Foundation
import SwiftData

/// Why a local parking write or read could not happen (docs/16 §4: SDK errors are mapped
/// to domain errors at the boundary).
enum ParkingStoreError: Error, Equatable {
    case containerUnavailable(String)
    case readFailed(String)
    case writeFailed(String)
    case notFound(UUID)
    /// FR-004 allows one active parking at a time, and says the conflict must be handled
    /// explicitly. The store refuses the second one and lets the caller decide; silently
    /// ending the first would throw away a record the user never asked to close.
    case activeSessionExists(existing: UUID)
}

/// The app's local parking history.
///
/// `@MainActor` because SwiftData's `ModelContext` is not `Sendable` and this app reads
/// it straight into SwiftUI. Everything crossing the boundary is a `ParkingSession`
/// value, so no `@Model` object escapes.
@MainActor
protocol ParkingStoring: AnyObject {
    /// The one record with no `endedAt`, if there is one.
    func activeSession() throws -> ParkingSession?
    /// Finished records, newest first. `limit == nil` means every one of them —
    /// FR-009's free/Plus gate is not this milestone's and is deliberately not applied.
    func completedSessions(limit: Int?) throws -> [ParkingSession]
    func session(id: UUID) throws -> ParkingSession?
    /// Inserts a new active parking. Throws `.activeSessionExists` if one already is.
    func startSession(_ session: ParkingSession) throws
    /// Overwrites an existing record's mutable fields.
    func update(_ session: ParkingSession) throws
    /// docs/06 §8: stamp `endedAt` on the active record, keeping the same id.
    func endSession(id: UUID, at endedAt: Date) throws
    func delete(id: UUID) throws
    /// docs/02 §15: the settings "delete local data" action.
    func deleteAll() throws
}

/// SwiftData-backed implementation (docs/06 §3: "completed history: SwiftData").
@MainActor
final class SwiftDataParkingStore: ParkingStoring {
    private let context: ModelContext
    private let clock: any DateProviding

    init(container: ModelContainer, clock: any DateProviding = SystemDateProvider()) {
        context = ModelContext(container)
        self.clock = clock
    }

    /// The on-disk container the app uses.
    ///
    /// Throws rather than falling back to an in-memory store: a history that silently
    /// forgets everything on relaunch is worse than a visible failure, and the home
    /// screen has an error state for exactly this.
    static func makeContainer() throws -> ModelContainer {
        do {
            return try ModelContainer(
                for: Schema(versionedSchema: ParkingSchemaV1.self),
                migrationPlan: ParkingMigrationPlan.self,
                configurations: ModelConfiguration()
            )
        } catch {
            throw ParkingStoreError.containerUnavailable(String(describing: error))
        }
    }

    /// In-memory twin used by tests and previews. Same schema, same migration plan.
    static func makeInMemoryContainer() throws -> ModelContainer {
        do {
            return try ModelContainer(
                for: Schema(versionedSchema: ParkingSchemaV1.self),
                migrationPlan: ParkingMigrationPlan.self,
                configurations: ModelConfiguration(isStoredInMemoryOnly: true)
            )
        } catch {
            throw ParkingStoreError.containerUnavailable(String(describing: error))
        }
    }

    func activeSession() throws -> ParkingSession? {
        try fetchActiveRecord().map(ParkingSession.init)
    }

    func completedSessions(limit: Int?) throws -> [ParkingSession] {
        var descriptor = FetchDescriptor<ParkingRecord>(
            predicate: #Predicate { $0.endedAt != nil },
            sortBy: [SortDescriptor(\.startedAt, order: .reverse)]
        )
        if let limit {
            descriptor.fetchLimit = limit
        }
        return try fetch(descriptor).map(ParkingSession.init)
    }

    func session(id: UUID) throws -> ParkingSession? {
        try fetchRecord(id: id).map(ParkingSession.init)
    }

    func startSession(_ session: ParkingSession) throws {
        if let existing = try fetchActiveRecord(), existing.id != session.id {
            throw ParkingStoreError.activeSessionExists(existing: existing.id)
        }
        context.insert(ParkingRecord(session.sanitized()))
        try commit()
    }

    func update(_ session: ParkingSession) throws {
        guard let record = try fetchRecord(id: session.id) else {
            throw ParkingStoreError.notFound(session.id)
        }
        record.apply(session.sanitized(), updatedAt: clock.now)
        try commit()
    }

    func endSession(id: UUID, at endedAt: Date) throws {
        guard let record = try fetchRecord(id: id) else {
            throw ParkingStoreError.notFound(id)
        }
        record.endedAt = endedAt
        record.updatedAt = clock.now
        try commit()
    }

    func delete(id: UUID) throws {
        guard let record = try fetchRecord(id: id) else {
            throw ParkingStoreError.notFound(id)
        }
        context.delete(record)
        try commit()
    }

    func deleteAll() throws {
        for record in try fetch(FetchDescriptor<ParkingRecord>()) {
            context.delete(record)
        }
        try commit()
    }

    private func fetchActiveRecord() throws -> ParkingRecord? {
        let descriptor = FetchDescriptor<ParkingRecord>(
            predicate: #Predicate { $0.endedAt == nil },
            sortBy: [SortDescriptor(\.startedAt, order: .reverse)]
        )
        return try fetch(descriptor).first
    }

    private func fetchRecord(id: UUID) throws -> ParkingRecord? {
        try fetch(FetchDescriptor<ParkingRecord>(predicate: #Predicate { $0.id == id })).first
    }

    private func fetch(_ descriptor: FetchDescriptor<ParkingRecord>) throws -> [ParkingRecord] {
        do {
            return try context.fetch(descriptor)
        } catch {
            throw ParkingStoreError.readFailed(String(describing: error))
        }
    }

    private func commit() throws {
        do {
            try context.save()
        } catch {
            // Leaving a failed write staged would make the next save appear to succeed
            // while carrying the same broken change.
            context.rollback()
            throw ParkingStoreError.writeFailed(String(describing: error))
        }
    }
}

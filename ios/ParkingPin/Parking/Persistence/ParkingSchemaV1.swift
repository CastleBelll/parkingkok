import Foundation
import SwiftData

/// Version 1 of the local parking store.
///
/// docs/04_IOS_IMPLEMENTATION.md §11: "Create `SchemaV1` versioned model and
/// `SchemaMigrationPlan` even if first release has no migration. This avoids ad-hoc
/// migrations later." The cost of the wrapper now is one enum; the cost of adding it
/// after a release is a store nobody can open.
enum ParkingSchemaV1: VersionedSchema {
    static var versionIdentifier: Schema.Version {
        Schema.Version(1, 0, 0)
    }

    static var models: [any PersistentModel.Type] {
        [ParkingRecord.self]
    }

    /// The persisted shape of one parking, field-for-field from docs/06 §2's common
    /// record schema. The logical fields must match Android's Room entity, so nothing is
    /// renamed for iOS taste and nothing is added that the other platform will not have.
    ///
    /// `floorKind` and `source` are stored as their raw `String`s rather than as enums:
    /// a value written by a newer build then reads back as unknown text instead of
    /// failing the whole fetch, and the column matches Android's.
    ///
    /// docs/04 §11: no `CLLocation` here — latitude, longitude and accuracy are
    /// primitives.
    @Model
    final class ParkingRecord {
        #Index<ParkingRecord>([\.startedAt])
        @Attribute(.unique) var id: UUID
        var startedAt: Date
        var endedAt: Date?
        var source: String
        var confidenceBucket: String?
        var latitude: Double?
        var longitude: Double?
        var horizontalAccuracy: Double?
        var locationCapturedAt: Date?
        var floorRaw: String?
        var floorKind: String?
        var floorNumber: Int?
        var zone: String?
        var spot: String?
        var memo: String?
        var photoRelativePath: String?
        var createdAt: Date
        var updatedAt: Date

        init(
            id: UUID,
            startedAt: Date,
            endedAt: Date?,
            source: String,
            confidenceBucket: String?,
            latitude: Double?,
            longitude: Double?,
            horizontalAccuracy: Double?,
            locationCapturedAt: Date?,
            floorRaw: String?,
            floorKind: String?,
            floorNumber: Int?,
            zone: String?,
            spot: String?,
            memo: String?,
            photoRelativePath: String?,
            createdAt: Date,
            updatedAt: Date
        ) {
            self.id = id
            self.startedAt = startedAt
            self.endedAt = endedAt
            self.source = source
            self.confidenceBucket = confidenceBucket
            self.latitude = latitude
            self.longitude = longitude
            self.horizontalAccuracy = horizontalAccuracy
            self.locationCapturedAt = locationCapturedAt
            self.floorRaw = floorRaw
            self.floorKind = floorKind
            self.floorNumber = floorNumber
            self.zone = zone
            self.spot = spot
            self.memo = memo
            self.photoRelativePath = photoRelativePath
            self.createdAt = createdAt
            self.updatedAt = updatedAt
        }
    }
}

/// Current model version. Every call site uses this alias, so bumping to V2 touches the
/// schema files and nothing else.
typealias ParkingRecord = ParkingSchemaV1.ParkingRecord

/// Migration plan for the parking store (docs/04 §11).
///
/// One stage-free version today. Adding V2 means appending it to `schemas` and adding
/// the stage that gets there — the shape of that change is the whole point of declaring
/// this before it is needed.
enum ParkingMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] {
        [ParkingSchemaV1.self]
    }

    static var stages: [MigrationStage] {
        []
    }
}

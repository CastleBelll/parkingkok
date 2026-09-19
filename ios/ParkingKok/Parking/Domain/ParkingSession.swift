import Foundation

/// How a record came to exist (docs/06 §2 `source`, docs/16 §3 shared naming).
enum ParkingSource: String, Sendable, Codable, CaseIterable {
    case manual
    case detected
}

/// The engine's external confidence contract
/// (docs/05_PARKING_DETECTION_ENGINE.md §9). Stored on a record so a detected parking
/// can explain itself later; nothing in this milestone produces one yet.
enum ConfidenceBucket: String, Sendable, Codable, CaseIterable {
    case high
    case medium
    case low
}

/// One parking, active or finished — the domain value behind docs/06 §2's record schema.
///
/// A value type, not the `@Model` class: `@Model` is not `Sendable` under Swift 6, and
/// docs/16 §1 asks for value types in the domain either way. The store maps between the
/// two so SwiftData never reaches a view.
///
/// `endedAt == nil` is what "active" means. FR-004 allows exactly one such record at a
/// time, which the store enforces on write rather than leaving to callers.
struct ParkingSession: Sendable, Equatable, Identifiable {
    let id: UUID
    let startedAt: Date
    var endedAt: Date?
    let source: ParkingSource
    var confidenceBucket: ConfidenceBucket?

    /// Local-only, and absent whenever location permission was not granted (FR-001).
    /// Stored as primitives — docs/04 §11 forbids persisting `CLLocation` itself.
    var location: ParkedLocation?

    var floor: FloorValue?
    var zone: String?
    var spot: String?
    var memo: String?
    /// FR-007's one photo, as `{recordId}.heic` relative to the app's photo directory
    /// (`FileSystemParkingPhotoStore`). Local-only: docs/06 §1 classifies the photo as
    /// sensitive and docs/07 §2 forbids Firebase Storage, so this names a file inside the
    /// sandbox and never a remote object.
    var photoRelativePath: String?

    let createdAt: Date
    var updatedAt: Date

    var isActive: Bool {
        endedAt == nil
    }

    /// `A구역 · 142`, `A구역`, or `142번` — nil when neither was recorded.
    ///
    /// Lives here because three screens were each joining zone and spot themselves and
    /// each produced a bare number when only the spot existed: a record holding `03` read
    /// as `03` under the floor, at hero weight, with nothing saying what the number was.
    /// The zone is what made the pair legible, so without one the number takes the word.
    var placeText: String? {
        switch (zone, spot) {
        case let (zone?, spot?): "\(zone) · \(spot)"
        case let (zone?, nil): zone
        case let (nil, spot?): "\(spot)번"
        case (nil, nil): nil
        }
    }

    /// FR-006: zone and spot are capped at 40 characters each.
    static let maximumFieldLength = 40
    /// Not in FR-006, which is silent on memo. A ceiling all the same: an unbounded
    /// column is a migration problem later, and nothing in the UI wants a paragraph.
    static let maximumMemoLength = 200
}

/// The coordinate pair kept for one parking.
///
/// Never leaves the device: not Firebase, not analytics, not a log line (CLAUDE.md Hard
/// Constraints). Held separately from `ParkingSession` so "has a location" is one
/// optional rather than four that could disagree.
struct ParkedLocation: Sendable, Equatable {
    let latitude: Double
    let longitude: Double
    /// Metres.
    let horizontalAccuracy: Double
    let capturedAt: Date
}

extension ParkedLocation {
    /// Bridge from the detection stack's local-only fix.
    init(_ reliable: LastReliableLocation) {
        self.init(
            latitude: reliable.latitude,
            longitude: reliable.longitude,
            horizontalAccuracy: reliable.horizontalAccuracy,
            capturedAt: reliable.capturedAt
        )
    }
}

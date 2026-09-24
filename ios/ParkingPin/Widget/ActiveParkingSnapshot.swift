import Foundation

/// The active parking, projected into the App Group so the widget process can draw it
/// (docs/06 §3 "Widget reads App Group projection only", §6).
///
/// Deliberately **not** a `ParkingSession`. docs/06 §1 classifies the coordinate as
/// sensitive local-only data and docs/09 forbids the widget rendering a coordinate, an
/// address or a photo — so latitude, longitude, the photo path and the memo are absent
/// here rather than filtered out at the view. A field the widget does not draw has no
/// reason to sit in a container two processes can open.
///
/// SwiftData stays canonical (docs/06 §3). This is the projection of it, and the
/// revision model in §5 is what lets the widget mutate it safely between refreshes.
struct ActiveParkingSnapshot: Codable, Sendable, Equatable {
    /// Bumped when the payload's meaning changes. A file written by a newer build is
    /// discarded on read instead of being guessed at — the app rewrites it on the next
    /// refresh anyway, and half-understood state is worse than none.
    static let currentVersion = 1

    /// The kind both processes hand `WidgetCenter` (docs/06 §7 step 3). It lives beside
    /// the projection because the *app* reloads a widget it does not own, and a second
    /// spelling of this string is a widget that silently stops refreshing.
    static let widgetKind = "ActiveParkingWidget"

    let version: Int
    /// docs/06 §5. The widget echoes this back with every mutation, so a tap that lands
    /// after the parking ended can be dropped rather than applied to its successor.
    let sessionId: UUID
    /// docs/06 §5: monotonic within a session, incremented by every mutation from either
    /// process.
    let revision: Int
    let updatedAt: Date
    /// What the elapsed line counts from.
    let startedAt: Date
    let floor: Floor?
    let zone: String?
    let spot: String?

    /// `FloorValue` flattened to its stored form (docs/06 §2 `floorRaw`/`floorKind`/
    /// `floorNumber`), so the projection round-trips a floor an older build parsed
    /// without re-parsing it.
    struct Floor: Codable, Sendable, Equatable {
        let raw: String
        let kind: FloorKind
        let number: Int?
    }
}

extension ActiveParkingSnapshot {
    init(
        sessionId: UUID,
        revision: Int,
        updatedAt: Date,
        startedAt: Date,
        floor: FloorValue?,
        zone: String?,
        spot: String?
    ) {
        self.init(
            version: Self.currentVersion,
            sessionId: sessionId,
            revision: revision,
            updatedAt: updatedAt,
            startedAt: startedAt,
            floor: floor.map { Floor(raw: $0.raw, kind: $0.kind, number: $0.number) },
            zone: zone,
            spot: spot
        )
    }

    /// The projected floor as the shared domain type.
    ///
    /// docs/06 §7a requires the stepper to go through "the shared floor domain"; this is
    /// the door back into it, and it is why the widget target compiles `FloorValue.swift`
    /// itself rather than carrying a second copy of the rules.
    var floorValue: FloorValue? {
        floor.map { FloorValue.stored(raw: $0.raw, kind: $0.kind, number: $0.number) }
    }

    /// `A구역 · 142`, or whichever half of it exists — the line under the hero in
    /// `01-home-main.png`.
    var placeText: String? {
        let parts = [zone, spot]
            .compactMap(\.self)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// The same parking with a new floor, one revision on (docs/06 §7a steps 3–4).
    func stepped(to floor: FloorValue, at now: Date) -> ActiveParkingSnapshot {
        ActiveParkingSnapshot(
            sessionId: sessionId,
            revision: revision + 1,
            updatedAt: now,
            startedAt: startedAt,
            floor: floor,
            zone: zone,
            spot: spot
        )
    }

    /// Whether this already projects exactly that parking.
    ///
    /// A refresh is not a mutation: republishing an identical projection would inflate
    /// the revision on every foregrounding and reload the widget for nothing.
    func projectsSame(
        sessionId: UUID,
        startedAt: Date,
        floor: FloorValue?,
        zone: String?,
        spot: String?
    ) -> Bool {
        self.sessionId == sessionId
            && self.startedAt == startedAt
            && floorValue == floor
            && self.zone == zone
            && self.spot == spot
    }
}

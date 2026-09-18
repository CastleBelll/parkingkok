import Foundation
import Observation

/// What the user typed into the manual save sheet, before it becomes a parking (FR-001).
struct ManualParkingDraft: Sendable, Equatable {
    var floorText = ""
    var zone = ""
    var spot = ""
    var memo = ""

    /// Nothing is required. FR-001 lists floor/zone/spot as the fields, and docs/02 §9
    /// makes each of them optional — a parking with only a timestamp is still the record
    /// that tells the user when they arrived.
    var isEmpty: Bool {
        [floorText, zone, spot, memo].allSatisfy {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }
}

/// Application state for parking: the one place the store is read and written.
///
/// Every screen observes this rather than holding its own store handle, so ending a
/// parking on the detail screen is visible on home without a reload dance. The layering
/// is docs/16 §5's: views render and send intents, this type performs them, the store
/// persists. Nothing here is called from a SwiftUI `body`.
///
/// Reads are cached into plain properties so `body` never touches SwiftData —
/// docs/16 §5 forbids storage work inside `body`, and docs/01 §8 requires the home
/// screen to render from local state with no network anywhere in the path.
@MainActor
@Observable
final class ParkingModel {
    /// Home's "최근 주차" preview keeps to three rows, as `01-home-main.png` shows.
    static let homePreviewLimit = 3

    private(set) var activeSession: ParkingSession?
    private(set) var completedSessions: [ParkingSession] = []
    /// Non-nil when the local store could not be read or written. Shown in place of the
    /// content rather than swallowed — a history that looks empty because of an IO error
    /// is indistinguishable from one the user really has not filled yet.
    private(set) var failure: String?

    private let store: any ParkingStoring
    private let locationProvider: any ParkingLocationProviding
    private let clock: any DateProviding
    private let analytics: any AnalyticsRecording

    init(
        store: any ParkingStoring,
        locationProvider: any ParkingLocationProviding = DetectionParkingLocationProvider(),
        clock: any DateProviding = SystemDateProvider(),
        analytics: any AnalyticsRecording = DisabledAnalyticsRecorder()
    ) {
        self.store = store
        self.locationProvider = locationProvider
        self.clock = clock
        self.analytics = analytics
    }

    var homePreviewSessions: [ParkingSession] {
        Array(completedSessions.prefix(Self.homePreviewLimit))
    }

    var now: Date {
        clock.now
    }

    /// Re-reads everything. Cheap enough to run on every appearance; the store is local.
    func refresh() {
        perform {
            activeSession = try store.activeSession()
            // FR-009's free 5-record limit is a subscription gate and there is no
            // subscription yet, so every record is listed. Adding a cap now would look
            // like the gate while gating nothing.
            completedSessions = try store.completedSessions(limit: nil)
        }
    }

    /// FR-001. Succeeds with no permission of any kind: the location is whatever
    /// `locationProvider` happens to have, and `nil` is a normal answer.
    @discardableResult
    func saveManualParking(_ draft: ManualParkingDraft) async -> Bool {
        let location = await locationProvider.currentParkedLocation()
        let now = clock.now
        let session = ParkingSession(
            id: UUID(),
            startedAt: now,
            endedAt: nil,
            source: .manual,
            confidenceBucket: nil,
            location: location,
            floor: FloorValue.parse(draft.floorText),
            zone: draft.zone,
            spot: draft.spot,
            memo: draft.memo,
            photoRelativePath: nil,
            createdAt: now,
            updatedAt: now
        )
        let saved = perform {
            try store.startSession(session)
            refreshAfterWrite()
        }
        // docs/17 §2 `parking_manual_saved`. After the write, never before: an event for a
        // save that failed would overstate the feature. The payload is the event name and
        // `platform` — floor, zone, spot and memo are §3 forbidden and `AnalyticsEvent`
        // gives them nowhere to go.
        if saved {
            analytics.record(.parkingManualSaved)
        }
        return saved
    }

    /// The `-` / `+` keys on home. FR-005: only a numerically parsed floor moves.
    @discardableResult
    func stepActiveFloor(by delta: Int) -> Bool {
        guard var session = activeSession, let stepped = session.floor?.stepped(by: delta) else {
            return false
        }
        session.floor = stepped
        session.updatedAt = clock.now
        return perform {
            try store.update(session)
            refreshAfterWrite()
        }
    }

    /// Edits made from the detail screen.
    @discardableResult
    func update(_ session: ParkingSession) -> Bool {
        var updated = session
        updated.updatedAt = clock.now
        return perform {
            try store.update(updated)
            refreshAfterWrite()
        }
    }

    /// docs/06 §8: stamp `endedAt` on the same record and let it fall into history.
    @discardableResult
    func endActiveParking() -> Bool {
        guard let session = activeSession else { return false }
        return perform {
            try store.endSession(id: session.id, at: clock.now)
            refreshAfterWrite()
        }
    }

    @discardableResult
    func delete(id: UUID) -> Bool {
        perform {
            try store.delete(id: id)
            refreshAfterWrite()
        }
    }

    /// docs/02 §15.
    @discardableResult
    func deleteAllLocalData() -> Bool {
        perform {
            try store.deleteAll()
            refreshAfterWrite()
        }
    }

    func clearFailure() {
        failure = nil
    }

    private func refreshAfterWrite() {
        activeSession = try? store.activeSession()
        completedSessions = (try? store.completedSessions(limit: nil)) ?? []
    }

    /// Runs a store operation, turning a thrown domain error into displayable text.
    /// Returns whether it got through, so a sheet knows not to dismiss on failure.
    @discardableResult
    private func perform(_ work: () throws -> Void) -> Bool {
        do {
            try work()
            failure = nil
            return true
        } catch {
            failure = Self.message(for: error)
            AppLog.lifecycle.error("parking store operation failed: \(String(describing: error), privacy: .public)")
            return false
        }
    }

    private static func message(for error: any Error) -> String {
        guard let storeError = error as? ParkingStoreError else {
            return "주차 기록을 처리하지 못했어요."
        }
        switch storeError {
        case .activeSessionExists:
            // FR-004's explicit conflict policy, stated to the user rather than resolved
            // behind their back by ending a parking they never closed.
            return "이미 진행 중인 주차가 있어요. 먼저 주차를 종료해 주세요."
        case .notFound:
            return "해당 주차 기록을 찾지 못했어요."
        case .containerUnavailable, .readFailed:
            return "주차 기록을 불러오지 못했어요."
        case .writeFailed:
            return "주차 기록을 저장하지 못했어요."
        }
    }
}

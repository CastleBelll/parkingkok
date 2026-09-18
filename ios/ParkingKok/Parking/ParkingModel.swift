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
    private let photoStore: any ParkingPhotoStoring
    private let clock: any DateProviding
    private let analytics: any AnalyticsRecording

    init(
        store: any ParkingStoring,
        locationProvider: any ParkingLocationProviding = DetectionParkingLocationProvider(),
        photoStore: any ParkingPhotoStoring = UnavailableParkingPhotoStore(),
        clock: any DateProviding = SystemDateProvider(),
        analytics: any AnalyticsRecording = DisabledAnalyticsRecorder()
    ) {
        self.store = store
        self.locationProvider = locationProvider
        self.photoStore = photoStore
        self.clock = clock
        self.analytics = analytics
    }

    var homePreviewSessions: [ParkingSession] {
        Array(completedSessions.prefix(Self.homePreviewLimit))
    }

    var now: Date {
        clock.now
    }

    /// The cached record with this id, active or finished. Reads the already-loaded
    /// arrays rather than the store, so a `body` may call it (docs/16 §5).
    func session(id: UUID) -> ParkingSession? {
        if let activeSession, activeSession.id == id {
            return activeSession
        }
        return completedSessions.first { $0.id == id }
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

    /// docs/04 §10: "remove orphan on record delete". The record goes first — a file
    /// left behind is collected by `removeOrphanPhotos`, whereas a record pointing at a
    /// file that is already gone is a broken row nothing cleans up.
    @discardableResult
    func delete(id: UUID) async -> Bool {
        let photoRelativePath = session(id: id)?.photoRelativePath
        guard perform({
            try store.delete(id: id)
            refreshAfterWrite()
        }) else {
            return false
        }
        if let photoRelativePath {
            try? await photoStore.remove(photoRelativePath)
        }
        return true
    }

    /// docs/02 §15. Deletes the photos too: "전체 삭제" that left a directory of
    /// photographs behind would be the opposite of what it says.
    @discardableResult
    func deleteAllLocalData() async -> Bool {
        guard perform({
            try store.deleteAll()
            refreshAfterWrite()
        }) else {
            return false
        }
        try? await photoStore.removeOrphans(keeping: [])
        return true
    }

    // ── FR-007 photo ────────────────────────────────────────────────────────

    /// Stores `imageData` as this record's one photo, replacing any previous one.
    ///
    /// The file is written before the row is updated. The other order can leave a record
    /// naming a file that was never written; this order can only leave an unreferenced
    /// file, which `removeOrphanPhotos` sweeps.
    @discardableResult
    func attachPhoto(_ imageData: Data, to sessionID: UUID) async -> Bool {
        guard var session = session(id: sessionID) else {
            failure = Self.message(for: ParkingStoreError.notFound(sessionID))
            return false
        }
        do {
            session.photoRelativePath = try await photoStore.save(imageData, for: sessionID)
        } catch {
            note(error)
            return false
        }
        return update(session)
    }

    /// Drops the photo and keeps the parking. The row is cleared first, so a failed file
    /// delete degrades to an orphan rather than to a record pointing at nothing.
    @discardableResult
    func removePhoto(from sessionID: UUID) async -> Bool {
        guard var session = session(id: sessionID), let relativePath = session.photoRelativePath else {
            return false
        }
        session.photoRelativePath = nil
        guard update(session) else { return false }
        try? await photoStore.remove(relativePath)
        return true
    }

    /// The stored photo for a record, or `nil` when there is none to show.
    ///
    /// Never called from `body` — the detail screen loads it in a `.task`.
    func photo(for session: ParkingSession) async -> ParkingPhoto? {
        guard let relativePath = session.photoRelativePath else { return nil }
        return try? await photoStore.load(relativePath)
    }

    /// docs/04 §10 "periodic orphan cleanup". Runs once per launch, after the first read.
    ///
    /// Deliberately refuses to run on a failed read: `completedSessions` is empty both
    /// when the user has no history and when the container would not open, and the
    /// second case would delete every photo on the device.
    func removeOrphanPhotos() async {
        guard failure == nil else { return }
        var kept = Set(completedSessions.compactMap(\.photoRelativePath))
        if let activePath = activeSession?.photoRelativePath {
            kept.insert(activePath)
        }
        try? await photoStore.removeOrphans(keeping: kept)
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
            note(error)
            return false
        }
    }

    /// Records a failure for display, and logs a redacted version of it.
    ///
    /// A `ParkingPhotoError` is logged by case name only — its payload can carry the
    /// stored photo's path, which CLAUDE.md keeps out of logs alongside coordinates.
    private func note(_ error: any Error) {
        failure = Self.message(for: error)
        let label = (error as? ParkingPhotoError)?.logLabel ?? String(describing: error)
        AppLog.lifecycle.error("parking operation failed: \(label, privacy: .public)")
    }

    private static func message(for error: any Error) -> String {
        if let photoError = error as? ParkingPhotoError {
            return message(for: photoError)
        }
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

    /// FR-007 is one optional photo. Every failure here leaves the parking itself
    /// intact, so the wording says what could not be done and nothing more alarming.
    private static func message(for error: ParkingPhotoError) -> String {
        switch error {
        case .unreadableImage, .downsampleFailed, .encodeFailed:
            "이 사진은 사용할 수 없어요. 다른 사진을 선택해 주세요."
        case .directoryUnavailable, .writeFailed:
            "사진을 저장하지 못했어요. 저장 공간을 확인해 주세요."
        case .notFound:
            "저장된 사진을 찾지 못했어요."
        }
    }
}

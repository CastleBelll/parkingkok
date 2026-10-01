import Foundation
import Observation
import WidgetKit

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

/// How a parking saved by hand reaches the detection engine (docs/05 §11c).
///
/// A protocol rather than a reach for `DetectionRuntime.shared`, for the reason
/// `CandidateResolving` is one: the model and its tests never need a Core Location stack
/// to exist, and a build with no detection at all still saves parkings.
@MainActor
protocol ManualParkingReporting: AnyObject {
    /// `location`: where the saved parking says the car is, when it knows (§11d).
    func userSavedParking(at date: Date, location: LastReliableLocation?) async
    /// docs/05 §11a: the user answered a departure proposal with 아직 주차 중.
    func userKeptParking(at date: Date) async
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
    /// The parking whose location is still being asked for after a manual save, so the
    /// screen can say `위치 확인 중` instead of `위치 없음` for those few seconds.
    private(set) var locatingSessionID: UUID?
    /// Non-nil when the local store could not be read or written. Shown in place of the
    /// content rather than swallowed — a history that looks empty because of an IO error
    /// is indistinguishable from one the user really has not filled yet.
    private(set) var failure: String?
    /// docs/05 §11a: a departure the engine noticed and the user has not answered. Only ever
    /// about `activeSession` — a proposal about any other record is retired on sight.
    private(set) var endProposal: ParkingEndProposal?

    private let store: any ParkingStoring
    private let locationProvider: any ParkingLocationProviding
    private let photoStore: any ParkingPhotoStoring
    private let clock: any DateProviding
    private let analytics: any AnalyticsRecording
    /// docs/06 §6's App Group projection, or `nil` when this build has no container —
    /// which disables the widget and nothing else (CLAUDE.md: a missing capability is not
    /// an app-wide failure).
    private let snapshots: (any ActiveParkingSnapshotStoring)?
    /// docs/05 §11c. `nil` when there is no detection to tell — the save is unaffected.
    /// Weak because the runtime outlives every model and is not this model's to keep.
    private weak var detection: (any ManualParkingReporting)?
    /// The in-flight hand-off to detection. Kept so tests can await it; the save itself
    /// never does.
    private(set) var detectionReport: Task<Void, Never>?
    /// The pending departure proposal's file and notification. `nil` when this build has no
    /// detection; nothing is ever proposed then.
    private let endProposals: ParkingEndProposalInbox?
    /// The in-flight withdrawal of the proposal's notification, held so tests can await it.
    private(set) var proposalWithdrawal: Task<Void, Never>?
    /// Re-reads the proposal when the coordinator writes one while the app is on screen.
    @ObservationIgnored private var proposalObservation: ParkingEndProposalObservation?

    init(
        store: any ParkingStoring,
        locationProvider: any ParkingLocationProviding = CurrentFixParkingLocationProvider(),
        photoStore: any ParkingPhotoStoring = UnavailableParkingPhotoStore(),
        clock: any DateProviding = SystemDateProvider(),
        analytics: any AnalyticsRecording = DisabledAnalyticsRecorder(),
        snapshots: (any ActiveParkingSnapshotStoring)? = nil,
        detection: (any ManualParkingReporting)? = nil,
        endProposals: ParkingEndProposalInbox? = nil
    ) {
        self.store = store
        self.locationProvider = locationProvider
        self.photoStore = photoStore
        self.clock = clock
        self.analytics = analytics
        self.snapshots = snapshots
        self.detection = detection
        self.endProposals = endProposals
        proposalObservation = endProposals?.observeChanges { [weak self] in
            self?.reconcileEndProposal()
        }
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

    /// docs/02 §18: last time's floor and zone near `here`, from the loaded history.
    func usualSpot(near here: ParkedLocation?) -> PillarReading? {
        UsualSpotLookup.find(near: here, in: completedSessions)
    }

    /// docs/02 §18 for a saved parking: only what it left blank.
    func usualSpot(for session: ParkingSession) -> PillarReading? {
        UsualSpotLookup.find(for: session, in: completedSessions)
    }

    /// Where a manual save would put the car right now, without waiting (FR-001).
    func storedLocation() async -> ParkedLocation? {
        await locationProvider.storedLocation()
    }

    /// [session(id:)] after taking in a floor the widget stepped while the app was away.
    ///
    /// For the writes that start from the cached record without the user looking at it — a
    /// fix or a photo landing seconds later. Read straight from the cache, they published
    /// the app's old floor over the widget's newer one (audit 2026-10-01 L2).
    private func currentSession(id: UUID) -> ParkingSession? {
        adoptWidgetFloorChange()
        return session(id: id)
    }

    /// Re-reads everything. Cheap enough to run on every appearance; the store is local.
    ///
    /// Also the app's side of the widget contract, and the reason `RootView` calls it on
    /// every activation: docs/04 §13 "app reconciles App Group revision to in-memory UI
    /// on activation". The order matters — adopt what the widget did *before* republishing
    /// over it.
    func refresh() {
        perform {
            activeSession = try store.activeSession()
            // FR-009's free 5-record limit is a subscription gate and there is no
            // subscription yet, so every record is listed. Adding a cap now would look
            // like the gate while gating nothing.
            completedSessions = try store.completedSessions(limit: nil)
        }
        reconcileEndProposal()
        adoptWidgetFloorChange()
        publishWidgetSnapshot()
    }

    /// docs/04 §13 / docs/06 §5. While the app was away the widget may have stepped the
    /// floor; the projection is the only record of it.
    ///
    /// `updatedAt` is what decides, not the revision: the revision is monotonic per
    /// session but says nothing about which *store* is newer, and on a cold launch the app
    /// has no memory of the revision it last published. A projection older than the
    /// record — the case where a publish failed after the store had already moved on —
    /// must not revert an edit the user just made in the app.
    private func adoptWidgetFloorChange() {
        guard let snapshot = snapshots?.read(),
              var session = activeSession,
              snapshot.sessionId == session.id,
              snapshot.updatedAt > session.updatedAt,
              let floor = snapshot.floorValue,
              floor != session.floor
        else {
            return
        }
        session.floor = floor
        session.updatedAt = snapshot.updatedAt
        perform {
            try store.update(session)
            activeSession = try store.activeSession()
        }
    }

    /// Rewrites the projection from the canonical store (docs/06 §6).
    ///
    /// This is also docs/06 §8's startup repair. The repair asks that a stale active
    /// projection be cleared when a completed record carries its session id; publishing
    /// from the store is that, and more besides — SwiftData is what "active" means, so
    /// whenever it has no active session there is nothing to project, whether the parking
    /// was completed, deleted, or belongs to a build that no longer exists.
    private func publishWidgetSnapshot() {
        guard let snapshots else { return }
        let didChange = if let session = activeSession {
            snapshots.publish(
                sessionId: session.id,
                startedAt: session.startedAt,
                floor: session.floor,
                zone: session.zone,
                spot: session.spot,
                at: clock.now
            )
        } else {
            snapshots.clear()
        }
        // Both report whether the file actually moved, so a plain refresh does not spend a
        // widget reload on a redraw nobody would see.
        if didChange {
            reloadWidget()
        }
    }

    /// docs/06 §7 step 3. The app reloads a widget that lives in another process.
    private func reloadWidget() {
        WidgetCenter.shared.reloadTimelines(ofKind: ActiveParkingSnapshot.widgetKind)
    }

    /// FR-001. Succeeds with no permission of any kind: the location is whatever is already
    /// known, and `nil` is a normal answer.
    ///
    /// **The record is written before the GPS is asked.** Waiting for a fix first made the
    /// button look broken — up to eight silent seconds with nothing on screen but a disabled
    /// button, reported from the device as "저장 눌러도 반응은 없는데 저장은 되고". A parking
    /// record is local and instant, and a coordinate is an improvement to it, so
    /// [attachCurrentFix] catches up afterwards.
    @discardableResult
    func saveManualParking(_ draft: ManualParkingDraft) async -> Bool {
        #if PK_DEV
            SaveLocationDiagnostics.begin(at: clock.now)
        #endif
        let location = await locationProvider.storedLocation()
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
        // docs/05 §11a: a proposal still pending means the car left the previous parking at
        // `departedAt` — the user is now saving where it went. Without one, the store refuses
        // a second active parking (FR-004).
        let saved = startSession(session, endingActiveAt: pendingProposalEnd)
        // docs/17 §2 `parking_manual_saved`. After the write, never before: an event for a
        // save that failed would overstate the feature. The payload is the event name and
        // `platform` — floor, zone, spot and memo are §3 forbidden and `AnalyticsEvent`
        // gives them nowhere to go.
        if saved {
            analytics.record(.parkingManualSaved)
            attachCurrentFix(to: session.id, improving: location)
            reportToDetection(savedAt: now, location: location)
        }
        return saved
    }

    /// docs/05 §11c: tell the engine the car is parked, so driving away can end this record.
    ///
    /// Detached like `attachCurrentFix`: the hop crosses the coordinator actor, which may be
    /// busy with a wake, and the sheet must close on the save alone. Only a written record
    /// is reported — a refused save has no parking for a departure to end.
    private func reportToDetection(savedAt date: Date, location: ParkedLocation?) {
        guard let detection else { return }
        // §11d: the engine measures the next drive's start against this spot. The fix the
        // save had in hand, not the one `attachCurrentFix` may still improve it to: the two
        // are metres apart, and the test allows hundreds.
        let spot = location.map {
            LastReliableLocation(
                latitude: $0.latitude,
                longitude: $0.longitude,
                horizontalAccuracy: $0.horizontalAccuracy,
                capturedAt: $0.capturedAt
            )
        }
        detectionReport = Task {
            await detection.userSavedParking(at: date, location: spot)
        }
    }

    /// Asks the OS where the car is and writes it onto a record already saved.
    ///
    /// Detached on purpose: the save has returned and the sheet has closed, so this is the
    /// part the user never waits for. A fix that arrives after the record was deleted
    /// finds nothing to update. One that arrives after the parking was *ended* finds the
    /// record in the history and is refused: a record the user has ended keeps the
    /// coordinates it ended with — the rule Android's `SaveManualParkingUseCase` already
    /// had. The comment once claimed the store refused it; it did not, and the 20 s deadline
    /// made that window real.
    private func attachCurrentFix(to sessionID: UUID, improving existing: ParkedLocation?) {
        locatingSessionID = sessionID
        Task { [weak self, locationProvider] in
            let fix = await locationProvider.currentFix()
            if self?.locatingSessionID == sessionID {
                self?.locatingSessionID = nil
            }
            guard let fix else { return }
            // A stored location that is already better stays. `currentFix` is this moment's,
            // so it wins ties on age; accuracy is the only reason to keep the old one.
            if let existing, existing.horizontalAccuracy <= fix.horizontalAccuracy {
                #if PK_DEV
                    SaveLocationDiagnostics.note("attach", "keptStored")
                #endif
                return
            }
            guard let self, var session = currentSession(id: sessionID) else {
                #if PK_DEV
                    SaveLocationDiagnostics.note("attach", "sessionGone")
                #endif
                return
            }
            guard session.endedAt == nil else {
                #if PK_DEV
                    SaveLocationDiagnostics.note("attach", "sessionEnded")
                #endif
                return
            }
            session.location = fix
            let written = update(session)
            #if PK_DEV
                SaveLocationDiagnostics.note("attach", written ? "written" : "writeFailed")
            #endif
        }
    }

    /// docs/05 §10a confirmation: a detected candidate becomes a parking record.
    ///
    /// `source = detected`, the candidate's `lastReliableLocation`, and the floor the user
    /// chose — those three are the whole of the contract, and nothing else about the
    /// candidate is copied in. The draft is the same value the manual sheet produces,
    /// because docs/10 §7a routes `직접 입력` into "the existing manual entry" rather than
    /// into a second form that would drift from it. The record's `startedAt` is `detectedAt` rather than now,
    /// because the user parked when the engine says they did, not when they got round to
    /// answering.
    ///
    /// **FR-004's conflict policy, stated rather than left to the store.** One parking can
    /// be active. If one already is, the car cannot have been in two places, so the old
    /// one is ended at the moment this drive finished. That is not the "destructive silent
    /// end" docs/05 §11 forbids: it happens only because the user just said, explicitly,
    /// that they parked somewhere else.
    ///
    /// @return the new record's id, or `nil` if it could not be written — in which case
    /// nothing was ended either: the end and the insert are one store write.
    @discardableResult
    func saveDetectedParking(from candidate: ParkingCandidate, draft: ManualParkingDraft) -> UUID? {
        // docs/05 §11a: a pending proposal's `departedAt` is a better answer than the end of
        // the drive that followed. Without one, the old record ends when this drive finished,
        // never before it started, however far the clocks have drifted.
        let endingActiveAt = pendingProposalEnd
            ?? activeSession.map { max($0.startedAt, candidate.detectedAt) }
        let now = clock.now
        let session = ParkingSession(
            id: UUID(),
            startedAt: candidate.detectedAt,
            endedAt: nil,
            source: .detected,
            confidenceBucket: candidate.confidenceBucket,
            location: candidate.lastReliableLocation.map(ParkedLocation.init),
            floor: FloorValue.parse(draft.floorText),
            zone: draft.zone,
            spot: draft.spot,
            memo: draft.memo,
            photoRelativePath: nil,
            createdAt: now,
            updatedAt: now
        )
        guard startSession(session, endingActiveAt: endingActiveAt) else { return nil }
        return session.id
    }

    /// Writes the next parking, ending the active one at `endingActiveAt` in the same
    /// commit when given. A pending proposal is withdrawn only once the write has happened:
    /// until then the previous parking is still the user's, and so is the question about it.
    private func startSession(_ session: ParkingSession, endingActiveAt endedAt: Date?) -> Bool {
        let ending = endedAt.flatMap { endedAt in activeSession.map { ($0.id, endedAt) } }
        let written = perform {
            if let (endingId, endedAt) = ending {
                try store.replaceActiveSession(ending: endingId, at: endedAt, with: session)
            } else {
                try store.startSession(session)
            }
            refreshAfterWrite()
        }
        if written, ending != nil {
            retireEndProposal()
        }
        return written
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
    ///
    /// [at] is when the parking actually ended. 주차 종료 leaves it nil and means now; §11's
    /// automatic departure passes the moment the car pulled away, which is minutes before
    /// the engine could be sure of it — stamping now would record the parking as ending
    /// somewhere down the road. Never earlier than the start, so a clock that moved
    /// backwards cannot produce a negative duration.
    ///
    /// A departure proposal pending for this record is withdrawn: the user answered it by
    /// hand, at the time they chose (docs/05 §11a).
    @discardableResult
    func endActiveParking(at: Date? = nil) -> Bool {
        guard let session = activeSession else { return false }
        let endedAt = max(at ?? clock.now, session.startedAt)
        let ended = perform {
            try store.endSession(id: session.id, at: endedAt)
            refreshAfterWrite()
        }
        if ended {
            retireEndProposal()
        }
        return ended
    }

    // ── docs/05 §11a departure proposal ─────────────────────────────────────

    /// The home card's prompt row, while a proposal about the active parking is pending.
    var endPrompt: ParkingEndPrompt? {
        guard let active = activeSession, pendingProposalForActive != nil else { return nil }
        return ParkingEndPrompt(session: active)
    }

    /// `주차 종료` on the prompt: the record ends when the car left, not now.
    ///
    /// `parking_auto_end` (docs/17) is reported here, after the write and only for a record
    /// this call actually closed.
    @discardableResult
    func acceptEndProposal() -> Bool {
        guard let proposal = pendingProposalForActive, let session = activeSession else {
            retireEndProposal()
            return false
        }
        let ended = perform {
            try store.endSession(id: session.id, at: proposal.endedAt(for: session))
            refreshAfterWrite()
        }
        guard ended else { return false }
        analytics.record(.parkingAutoEnd)
        retireEndProposal()
        return true
    }

    /// `아직 주차 중`: the record stays, the question goes, and the engine is told the car is
    /// still parked (`user_kept_parking`) so it stops measuring a drive that was not this
    /// parking's end.
    func keepParking() {
        guard endProposal != nil else { return }
        retireEndProposal()
        guard let detection else { return }
        let now = clock.now
        detectionReport = Task {
            await detection.userKeptParking(at: now)
        }
    }

    private var pendingProposalForActive: ParkingEndProposal? {
        guard let proposal = endProposal, let active = activeSession, proposal.isAbout(active) else {
            return nil
        }
        return proposal
    }

    /// When the active record ends if the next parking is saved now: the pending proposal's
    /// `departedAt` (clamped to the record's start), or `nil` when nothing is pending.
    private var pendingProposalEnd: Date? {
        guard let proposal = pendingProposalForActive, let active = activeSession else { return nil }
        return proposal.endedAt(for: active)
    }

    /// Reads the proposal and retires one that no longer asks about the active parking.
    private func reconcileEndProposal() {
        guard let endProposals else { return }
        let proposal = endProposals.load()
        if let proposal, let active = activeSession, proposal.isAbout(active) {
            endProposal = proposal
        } else {
            endProposal = nil
            if proposal != nil {
                proposalWithdrawal = endProposals.retire()
            }
        }
    }

    private func retireEndProposal() {
        let hadProposal = endProposal != nil
        endProposal = nil
        guard let endProposals, hadProposal || endProposals.load() != nil else { return }
        proposalWithdrawal = endProposals.retire()
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
        try? await photoStore.removeAll()
        return true
    }

    // ── FR-007 photo ────────────────────────────────────────────────────────

    /// Stores `imageData` as this record's one photo, replacing any previous one.
    ///
    /// The file is written before the row is updated. The other order can leave a record
    /// naming a file that was never written; this order can only leave an unreferenced
    /// file, which `removeOrphanPhotos` sweeps.
    ///
    /// **The record is read again after the file is written**, and only the photo is
    /// changed on it. Encoding a pillar photo takes long enough for the one-shot location
    /// fix to be written in the meantime; putting back the copy read before the `await`
    /// erased that fix, and hand-saved parkings on the iPhone kept no location at all
    /// (2026-09-24).
    @discardableResult
    func attachPhoto(_ imageData: Data, to sessionID: UUID) async -> Bool {
        guard session(id: sessionID) != nil else {
            failure = Self.message(for: ParkingStoreError.notFound(sessionID))
            return false
        }
        let relativePath: String
        do {
            relativePath = try await photoStore.save(imageData, for: sessionID)
        } catch {
            note(error)
            return false
        }
        // Deleted while the file was being written: the file is now an orphan, which
        // `removeOrphanPhotos` sweeps.
        guard var session = currentSession(id: sessionID) else {
            failure = Self.message(for: ParkingStoreError.notFound(sessionID))
            return false
        }
        session.photoRelativePath = relativePath
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

    /// A photo the album handed back could not be read (iCloud offline, a format the picker
    /// would not export). Said on the screen the user is on; the picker had already closed
    /// and nothing else happened (audit 2026-10-01).
    func notePhotoUnreadable() {
        failure = "사진을 불러오지 못했어요. 다시 골라 주세요."
    }

    private func refreshAfterWrite() {
        activeSession = try? store.activeSession()
        completedSessions = (try? store.completedSessions(limit: nil)) ?? []
        reconcileEndProposal()
        // Every mutation ends here, so the projection cannot drift from the store by
        // someone forgetting to update it at one call site (docs/06 §8 steps 4–5).
        publishWidgetSnapshot()
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

import Foundation

/// Builds the parking stack for the running app.
///
/// Kept out of `ParkingModel` so the model has no opinion about where its store came
/// from — which is what lets the tests hand it an in-memory one.
@MainActor
struct ParkingComposition {
    let model: ParkingModel
    /// The pending detection candidate, for the confirmation screen, the home row and the
    /// notification actions (docs/05 §10a).
    let candidates: CandidateModel
    /// Non-nil when the on-disk store could not be opened and the app fell back to a
    /// store that dies with the process.
    ///
    /// The fallback exists because of FR-001: manual parking has to keep working even
    /// when the device is out of space or the container is unreadable. It is announced
    /// rather than hidden — losing records silently is the worse failure.
    let storageWarning: String?
    /// Held only so the DEV fixture can write through the same pair the model uses.
    private let store: any ParkingStoring
    private let photoStore: any ParkingPhotoStoring

    /// The lock screen's half of the candidate flow (`PKNotificationRouter`).
    ///
    /// Built on demand: it holds nothing but `candidates`, and the router needs it only
    /// for the instant it takes to turn one tap into one model call.
    var candidateResponder: CandidateNotificationResponder {
        CandidateNotificationResponder(model: candidates)
    }

    static let volatileStorageWarning = "기기에 저장할 수 없어 이번 실행에서만 기록이 유지됩니다."

    /// The process's one composition, built on first use.
    ///
    /// It exists because a notification action has to reach the parking store in a process
    /// that may never draw a frame: `NOT_PARKING` and the inline floor entry are answered
    /// from the lock screen, and `RootView`'s `.task` — which is where composition used to
    /// happen, and still happens for the UI — has not run there. One container either way,
    /// so the screen and the lock screen cannot disagree about what is stored.
    static var shared: ParkingComposition? {
        if !hasBuiltShared {
            hasBuiltShared = true
            sharedInstance = live()
        }
        return sharedInstance
    }

    private static var sharedInstance: ParkingComposition?
    /// Separate from `sharedInstance` so a failed build is remembered as a failure rather
    /// than retried on every notification.
    private static var hasBuiltShared = false

    /// `nil` only if even an in-memory container cannot be built, which leaves nothing
    /// to show.
    static func live() -> ParkingComposition? {
        let photoStore = livePhotoStore()
        // docs/06 §6. `nil` when the App Group container is missing: the widget then shows
        // its empty state and the app is otherwise untouched.
        let snapshots = FileActiveParkingSnapshotStore.appGroup()
        if let container = try? SwiftDataParkingStore.makeContainer() {
            let store = SwiftDataParkingStore(container: container)
            let model = ParkingModel(
                store: store,
                photoStore: photoStore,
                analytics: AnalyticsComposition.recorder,
                snapshots: snapshots,
                // docs/05 §11c: a parking saved by hand arms the departure.
                detection: DetectionRuntime.shared
            )
            return ParkingComposition(
                model: model,
                candidates: candidateModel(parking: model),
                storageWarning: nil,
                store: store,
                photoStore: photoStore
            )
        }
        AppLog.lifecycle.error("parking container unavailable; falling back to in-memory store")
        guard let fallback = try? SwiftDataParkingStore.makeInMemoryContainer() else {
            return nil
        }
        let fallbackStore = SwiftDataParkingStore(container: fallback)
        let fallbackModel = ParkingModel(
            store: fallbackStore,
            photoStore: photoStore,
            analytics: AnalyticsComposition.recorder,
            snapshots: snapshots,
            detection: DetectionRuntime.shared
        )
        return ParkingComposition(
            model: fallbackModel,
            candidates: candidateModel(parking: fallbackModel),
            storageWarning: volatileStorageWarning,
            store: fallbackStore,
            photoStore: photoStore
        )
    }

    /// The candidate half of the stack.
    ///
    /// It reads the same file `DetectionRuntime` writes to, which is what makes a
    /// candidate created on a background wake visible to a screen opened minutes later.
    /// A directory that will not open disables candidates and nothing else — manual
    /// parking is unaffected, which is the constraint in CLAUDE.md.
    private static func candidateModel(parking: ParkingModel) -> CandidateModel {
        CandidateModel(
            store: DetectionRuntime.shared.candidateStore ?? UnavailableParkingCandidateStore(),
            history: DetectionRuntime.shared.candidateHistoryStore ?? UnavailableCandidateHistoryStore(),
            analytics: AnalyticsComposition.recorder,
            parking: parking,
            resolver: DetectionRuntime.shared
        )
    }

    /// FR-007's photo directory, or the store that cannot hold one.
    ///
    /// A missing Application Support directory disables photos and nothing else: FR-007
    /// is one *optional* photo, and CLAUDE.md is explicit that a denied or unavailable
    /// capability is not an app-wide failure.
    private static func livePhotoStore() -> any ParkingPhotoStoring {
        guard let store = try? FileSystemParkingPhotoStore.applicationSupport() else {
            AppLog.lifecycle.error("photo directory unavailable; parking photos disabled this run")
            return UnavailableParkingPhotoStore()
        }
        return store
    }

    /// DEV-only, and a no-op unless the launch environment asks for it. See
    /// `ParkingSampleSeed`.
    ///
    /// `async` because the fixture now writes a photo, which the store does off the main
    /// actor. Awaited from `RootView`'s composition task, never from `body`.
    func seedSampleDataIfRequested() async {
        #if PK_DEV
            // Additive, and first: restoring one active parking must not depend on the
            // replacing seed below being asked for.
            if let requested = ParkingSampleSeed.requestedActiveParking {
                do {
                    try ParkingSampleSeed.applyActiveParking(requested, to: store, now: Date())
                    model.refresh()
                } catch {
                    AppLog.lifecycle.error("active seed failed: \(String(describing: error), privacy: .public)")
                }
            }
            guard ParkingSampleSeed.isRequested else { return }
            do {
                try await ParkingSampleSeed.apply(
                    to: store,
                    photoStore: photoStore,
                    // The same two stores the app runs on, so the seeded bell is read
                    // back through the production path rather than a parallel one.
                    candidateStore: DetectionRuntime.shared.candidateStore,
                    historyStore: DetectionRuntime.shared.candidateHistoryStore,
                    now: Date()
                )
            } catch {
                AppLog.lifecycle.error("sample seed failed: \(String(describing: error), privacy: .public)")
            }
        #endif
    }
}

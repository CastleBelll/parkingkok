import Foundation

/// Builds the parking stack for the running app.
///
/// Kept out of `ParkingModel` so the model has no opinion about where its store came
/// from — which is what lets the tests hand it an in-memory one.
@MainActor
struct ParkingComposition {
    let model: ParkingModel
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

    static let volatileStorageWarning = "기기에 저장할 수 없어 이번 실행에서만 기록이 유지됩니다."

    /// `nil` only if even an in-memory container cannot be built, which leaves nothing
    /// to show.
    static func live() -> ParkingComposition? {
        let photoStore = livePhotoStore()
        if let container = try? SwiftDataParkingStore.makeContainer() {
            let store = SwiftDataParkingStore(container: container)
            return ParkingComposition(
                model: ParkingModel(store: store, photoStore: photoStore),
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
        return ParkingComposition(
            model: ParkingModel(store: fallbackStore, photoStore: photoStore),
            storageWarning: volatileStorageWarning,
            store: fallbackStore,
            photoStore: photoStore
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
            guard ParkingSampleSeed.isRequested else { return }
            do {
                try await ParkingSampleSeed.apply(to: store, photoStore: photoStore, now: Date())
            } catch {
                AppLog.lifecycle.error("sample seed failed: \(String(describing: error), privacy: .public)")
            }
        #endif
    }
}

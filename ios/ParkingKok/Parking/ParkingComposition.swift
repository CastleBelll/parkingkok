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

    static let volatileStorageWarning = "기기에 저장할 수 없어 이번 실행에서만 기록이 유지됩니다."

    /// `nil` only if even an in-memory container cannot be built, which leaves nothing
    /// to show.
    static func live() -> ParkingComposition? {
        if let container = try? SwiftDataParkingStore.makeContainer() {
            let store = SwiftDataParkingStore(container: container)
            seedSampleDataIfRequested(into: store)
            return ParkingComposition(model: ParkingModel(store: store), storageWarning: nil)
        }
        AppLog.lifecycle.error("parking container unavailable; falling back to in-memory store")
        guard let fallback = try? SwiftDataParkingStore.makeInMemoryContainer() else {
            return nil
        }
        return ParkingComposition(
            model: ParkingModel(store: SwiftDataParkingStore(container: fallback)),
            storageWarning: volatileStorageWarning
        )
    }

    /// DEV-only, and a no-op unless the launch environment asks for it. See
    /// `ParkingSampleSeed`.
    private static func seedSampleDataIfRequested(into store: any ParkingStoring) {
        #if PK_DEV
            guard ParkingSampleSeed.isRequested else { return }
            do {
                try ParkingSampleSeed.apply(to: store, now: Date())
            } catch {
                AppLog.lifecycle.error("sample seed failed: \(String(describing: error), privacy: .public)")
            }
        #endif
    }
}

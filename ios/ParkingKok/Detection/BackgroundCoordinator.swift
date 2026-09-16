import Foundation

/// Why the process is running.
enum LaunchReason: String, Sendable, Equatable {
    /// The user tapped the icon, or the app was already alive.
    case userInitiated
    /// Core Location relaunched us for a significant change
    /// (`UIApplication.LaunchOptionsKey.location`).
    case significantLocationChange
}

/// Everything the rehydration path learned, in a form the UI can render.
///
/// Carries no coordinate by construction — `LocationQualitySample` never had one.
struct RehydrationSnapshot: Sendable, Equatable {
    var launchReason: LaunchReason = .userInitiated
    var rehydratedAt: Date?
    /// What the *load* said. Never overwritten by a later save, so a corrupt file stays
    /// visible for the whole session instead of being papered over by the next write.
    var checkpointLoad: DetectionCheckpointLoadResult = .absent
    /// What we hold right now, restored or freshly seeded.
    var currentCheckpoint: DetectionCheckpoint?
    var checkpointAge: TimeInterval?
    var isBeyondMotionRetention = false
    var motionWindow: MotionHistoryWindow?
    var motionSamples: [MotionSample] = []
    var motionFailure: String?
    var locationFailure: String?
    var significantChangeCount = 0
    var lastLocationAt: Date?
    var lastLocationAccuracy: Double?
    var lastPersistError: String?
}

/// Owns the detection state that outlives any one screen or callback.
///
/// An `actor` because the checkpoint is shared mutable state touched from a background
/// relaunch, a delegate callback, and the UI (docs/03_SYSTEM_ARCHITECTURE.md §4).
///
/// Scope note: this coordinates *rehydration* only. State-machine transitions, candidate
/// creation, notifications, and the bounded driving session are M0A-2.
actor BackgroundCoordinator {
    private let checkpointStore: any DetectionCheckpointStoring
    private let motionHistory: any MotionHistoryProviding
    private let dateProvider: any DateProviding

    private var checkpoint: DetectionCheckpoint?
    private var snapshot = RehydrationSnapshot()

    init(
        checkpointStore: any DetectionCheckpointStoring,
        motionHistory: any MotionHistoryProviding,
        dateProvider: any DateProviding = SystemDateProvider()
    ) {
        self.checkpointStore = checkpointStore
        self.motionHistory = motionHistory
        self.dateProvider = dateProvider
    }

    func currentSnapshot() -> RehydrationSnapshot {
        snapshot
    }

    /// The rehydration path from docs/04_IOS_IMPLEMENTATION.md §6.
    ///
    /// 1. read checkpoint  2. validate age  3. query motion history from
    /// `max(checkpoint.time, now - 30m)`  4. feed the engine (M0A-2)
    /// 5. start/stop live location (M0A-2).
    ///
    /// Never throws: a failure at any step degrades to a recorded reason, because the
    /// app must keep working with manual parking (CLAUDE.md Hard Constraints). No network
    /// request happens here — docs/04 §7 forbids it on the background path.
    func rehydrate(launchReason: LaunchReason) async {
        let now = dateProvider.now
        snapshot.launchReason = launchReason
        snapshot.rehydratedAt = now

        let load = checkpointStore.load()
        snapshot.checkpointLoad = load

        let restored = load.checkpoint
        checkpoint = restored
        snapshot.currentCheckpoint = restored

        switch load {
        case let .restored(loaded):
            snapshot.checkpointAge = loaded.age(now: now)
            snapshot.isBeyondMotionRetention = MotionHistoryWindowPolicy.isBeyondMotionRetention(
                checkpointDate: loaded.latestTimestamp,
                now: now
            )
            snapshot.lastLocationAt = loaded.lastLocationAt
        case .absent:
            // First run on this install: give process death something to restore.
            persist(DetectionCheckpoint.initial(at: now))
        case let .failed(failure):
            // Deliberately does *not* seed. Overwriting here would hide the damage and
            // make a real data-loss bug look like a fresh install.
            AppLog.detection.error("checkpoint load failed: \(failure.diagnosticDescription, privacy: .public)")
        }

        // Anchored on the *restored* checkpoint only. A checkpoint seeded a moment ago
        // would collapse the window to zero and skip the replay entirely.
        await reconstructMotionHistory(now: now, anchor: restored?.latestTimestamp)
    }

    /// A significant change arrived. M0A-1 records that it happened and how good the fix
    /// was; interpreting it is M0A-2.
    func handleSignificantChange(_ sample: LocationQualitySample) {
        snapshot.significantChangeCount += 1
        snapshot.lastLocationAt = sample.timestamp
        snapshot.lastLocationAccuracy = sample.horizontalAccuracy

        var updated = checkpoint ?? DetectionCheckpoint.initial(at: dateProvider.now)
        updated.lastLocationAt = sample.timestamp
        updated.revision += 1
        persist(updated)
    }

    /// Core Location told us it could not produce a fix. Recorded, not escalated.
    func recordLocationFailure(_ description: String) {
        snapshot.locationFailure = description
    }

    private func reconstructMotionHistory(now: Date, anchor: Date?) async {
        let window = MotionHistoryWindowPolicy.window(now: now, checkpointDate: anchor)
        snapshot.motionWindow = window

        do {
            let samples = try await motionHistory.samples(in: window)
            snapshot.motionSamples = samples
            snapshot.motionFailure = nil
            AppLog.detection.info("motion history restored: \(samples.count, privacy: .public) samples")
        } catch let error as MotionHistoryError {
            snapshot.motionSamples = []
            snapshot.motionFailure = error.diagnosticDescription
        } catch {
            snapshot.motionSamples = []
            let nsError = error as NSError
            snapshot.motionFailure = "unexpected: \(nsError.domain)(\(nsError.code))"
        }
    }

    private func persist(_ checkpoint: DetectionCheckpoint) {
        self.checkpoint = checkpoint
        snapshot.currentCheckpoint = checkpoint
        do {
            try checkpointStore.save(checkpoint)
            snapshot.lastPersistError = nil
        } catch {
            let description = String(describing: error)
            snapshot.lastPersistError = description
            AppLog.detection.error("checkpoint save failed: \(description, privacy: .public)")
        }
    }
}

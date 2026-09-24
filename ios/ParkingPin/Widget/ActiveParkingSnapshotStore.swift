import Foundation

/// What a widget step did (docs/06 §7a). Every case reloads the widget: a rejected delta
/// has to snap the display back to the true value rather than appear to have moved, and
/// a dropped one has to stop showing a parking that is already over.
enum ActiveParkingStepOutcome: Equatable, Sendable {
    /// The delta went through. The floor is what the widget must now draw.
    case stepped(FloorValue)
    /// The domain refused it — free text, or already at the outermost floor. A no-op.
    case rejected
    /// The session ended between render and tap. docs/06 §7a: dropped, and never applied
    /// to whatever session came next.
    case dropped
    /// The container was unreadable or the write failed. Nothing moved.
    case failed
}

/// The App Group projection: the only thing allowed to read or write it, from either
/// process (docs/06 §6 "atomic file replacement and shared mutation helper").
///
/// `Sendable` rather than `@MainActor` — unlike `ParkingStoring` there is no
/// `ModelContext` behind it, and the widget's intent reaches it from whatever isolation
/// App Intents runs `perform()` in. The cross-process lock is the synchronisation here;
/// an actor could only ever order the calls made inside one of the two processes.
protocol ActiveParkingSnapshotStoring: Sendable {
    func read() -> ActiveParkingSnapshot?

    /// Makes the projection match this parking, incrementing the revision.
    ///
    /// Returns whether anything on disk changed. A refresh that projects the parking
    /// already there writes nothing: republishing an identical projection would inflate
    /// the revision on every foregrounding and spend a widget reload on a redraw nobody
    /// would see. A failed write returns `false` for the same reason — nothing moved.
    @discardableResult
    func publish(
        sessionId: UUID,
        startedAt: Date,
        floor: FloorValue?,
        zone: String?,
        spot: String?,
        at now: Date
    ) -> Bool

    /// docs/06 §8 step 4: there is no active parking any more. Returns whether there was
    /// a projection to remove.
    @discardableResult
    func clear() -> Bool

    /// docs/06 §7a. The widget's `−`/`+` key, carrying a delta and the session it was
    /// drawn for.
    @discardableResult
    func step(by delta: Int, expecting sessionId: UUID, at now: Date) -> ActiveParkingStepOutcome
}

/// File-backed projection shared by the app and the widget extension.
///
/// Two processes write this, so neither read-modify-write may interleave with the other:
/// docs/06 §7a is explicit that two taps landing together must move two floors, which is
/// only true if each tap re-reads *after* the previous one has landed. `flock(2)` on a
/// sibling lock file is what makes that so — it is held across the whole read-decide-write
/// and it works between processes, which an `NSLock` inside one of them does not.
///
/// The payload itself is replaced atomically (`Data.WritingOptions.atomic` writes a
/// temporary file and renames it), so a reader never sees half a JSON document even
/// though it takes no lock of its own.
///
/// Sendable by construction: both stored properties are `let` URLs and every byte of
/// state lives in the shared container.
final class FileActiveParkingSnapshotStore: ActiveParkingSnapshotStoring {
    private let snapshotURL: URL
    private let lockURL: URL

    /// `directory` must already exist and be shared by both processes.
    init(directory: URL) {
        snapshotURL = directory.appending(path: "active-parking.json", directoryHint: .notDirectory)
        lockURL = directory.appending(path: "active-parking.lock", directoryHint: .notDirectory)
    }

    /// The store over this build's App Group, or `nil` when there is no container.
    ///
    /// `nil` is a normal answer, not a crash: CLAUDE.md is explicit that a missing
    /// capability is not an app-wide failure. Without a container the widget shows its
    /// empty state and the app is entirely unaffected — the canonical history is
    /// SwiftData either way.
    static func appGroup(bundle: Bundle = .main) -> FileActiveParkingSnapshotStore? {
        guard let identifier = bundle.object(forInfoDictionaryKey: appGroupInfoKey) as? String,
              !identifier.isEmpty,
              let container = FileManager.default
              .containerURL(forSecurityApplicationGroupIdentifier: identifier)
        else {
            AppLog.lifecycle.error("app group container unavailable; widget projection disabled")
            return nil
        }
        // `Library/Application Support`, not the container root. It is where the rest of
        // this app keeps local state (`FileSystemParkingPhotoStore`,
        // `DetectionCheckpointStore`), and it is the only part of a shared container
        // `devicectl device copy from` will read — which is what lets a field test pull the
        // projection off a real phone and check the revision by hand.
        let directory = container
            .appending(path: "Library/Application Support/Widget", directoryHint: .isDirectory)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            AppLog.lifecycle
                .error("widget projection directory unavailable: \(String(describing: error), privacy: .public)")
            return nil
        }
        return FileActiveParkingSnapshotStore(directory: directory)
    }

    /// Injected per configuration by `Config/Base.xcconfig` (`PK_APP_GROUP`), so DEV,
    /// STAGING and PROD get a container each and cannot overwrite one another's parking.
    private static let appGroupInfoKey = "PKAppGroupIdentifier"

    func read() -> ActiveParkingSnapshot? {
        withLock { readLocked() }
    }

    @discardableResult
    func publish(
        sessionId: UUID,
        startedAt: Date,
        floor: FloorValue?,
        zone: String?,
        spot: String?,
        at now: Date
    ) -> Bool {
        withLock {
            let previous = readLocked()
            if let previous, previous.projectsSame(
                sessionId: sessionId,
                startedAt: startedAt,
                floor: floor,
                zone: zone,
                spot: spot
            ) {
                return false
            }
            // docs/06 §5 makes the revision monotonic *within* a session. A different
            // parking starts its own count — the widget compares session ids, never
            // revisions across sessions.
            let revision = previous?.sessionId == sessionId ? (previous?.revision ?? 0) + 1 : 1
            let snapshot = ActiveParkingSnapshot(
                sessionId: sessionId,
                revision: revision,
                updatedAt: now,
                startedAt: startedAt,
                floor: floor,
                zone: zone,
                spot: spot
            )
            return writeLocked(snapshot)
        }
    }

    @discardableResult
    func clear() -> Bool {
        withLock {
            guard FileManager.default.fileExists(atPath: snapshotURL.path(percentEncoded: false)) else {
                return false
            }
            do {
                try FileManager.default.removeItem(at: snapshotURL)
                return true
            } catch {
                AppLog.lifecycle.error("widget projection clear failed: \(String(describing: error), privacy: .public)")
                return false
            }
        }
    }

    @discardableResult
    func step(by delta: Int, expecting sessionId: UUID, at now: Date) -> ActiveParkingStepOutcome {
        withLock {
            // 1. Re-read under the lock. What the widget rendered may already be several
            //    taps old — docs/06 §7a step 1.
            guard let current = readLocked() else { return .dropped }
            // 2. The session the key was drawn for must still be the active one.
            guard current.sessionId == sessionId else { return .dropped }
            // 3. Through the shared floor domain, never by arithmetic on a display string.
            guard let stepped = current.floorValue?.stepped(by: delta) else { return .rejected }
            // 4./5. Revision up, replaced atomically.
            return writeLocked(current.stepped(to: stepped, at: now)) ? .stepped(stepped) : .failed
        }
    }

    // ── Everything below runs with the lock already held ────────────────────

    private func readLocked() -> ActiveParkingSnapshot? {
        guard let data = try? Data(contentsOf: snapshotURL) else { return nil }
        guard let snapshot = try? JSONDecoder().decode(ActiveParkingSnapshot.self, from: data) else {
            AppLog.lifecycle.error("widget projection unreadable; treating as absent")
            return nil
        }
        guard snapshot.version == ActiveParkingSnapshot.currentVersion else {
            AppLog.lifecycle.error("widget projection version \(snapshot.version, privacy: .public) not understood")
            return nil
        }
        return snapshot
    }

    private func writeLocked(_ snapshot: ActiveParkingSnapshot) -> Bool {
        do {
            // The default date strategy, deliberately: it is a `Double` that round-trips
            // exactly. ISO-8601 would truncate, and `publish` compares the dates it reads
            // back against the ones in memory to decide whether anything changed at all.
            let data = try JSONEncoder().encode(snapshot)
            // `.atomic` is docs/06 §6's atomic file replacement: a temporary file beside
            // the target, then a rename. The protection class matches the detection
            // checkpoint's — the widget is refreshed on a device that has been unlocked
            // once since boot, never before.
            try data.write(to: snapshotURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            AppLog.lifecycle.error("widget projection write failed: \(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// Runs `body` holding an exclusive cross-process lock on `lockURL`.
    ///
    /// A lock that cannot be taken degrades to running unlocked rather than dropping the
    /// write: losing the user's floor change is the worse outcome, and the atomic
    /// replacement still leaves a whole file behind.
    private func withLock<T>(_ body: () -> T) -> T {
        let descriptor = open(lockURL.path(percentEncoded: false), O_RDONLY | O_CREAT, 0o644)
        guard descriptor >= 0 else {
            AppLog.lifecycle.error("widget projection lock unavailable; proceeding unlocked")
            return body()
        }
        defer { close(descriptor) }
        guard flock(descriptor, LOCK_EX) == 0 else { return body() }
        defer { flock(descriptor, LOCK_UN) }
        return body()
    }
}

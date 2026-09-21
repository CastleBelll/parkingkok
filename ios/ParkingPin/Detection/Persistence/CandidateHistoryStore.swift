import Foundation

/// docs/10 §7b's last 30 notifications, and docs/05 §10a's "separate append-only list".
///
/// **Deliberately not the candidate slot.** §10a: "The live candidate slot still holds at
/// most one; history is a separate append-only list, because the two answer different
/// questions and giving the slot a second job is how it would end up holding two live
/// candidates by accident."
///
/// Synchronous and `Sendable` for the same reason `ParkingCandidateStoring` is: the
/// writer can be `BackgroundCoordinator`'s actor on a wake with no UI, and the reader is
/// the main actor a moment later.
protocol CandidateHistoryStoring: Sendable {
    /// Newest first, already capped. Never throws — an unreadable history is an empty
    /// bell, not an app failure.
    func entries() -> [CandidateHistoryEntry]

    /// Records a resolution.
    ///
    /// **Idempotent by `id`.** A candidate can be retired by the screen and by the engine
    /// within the same second (the screen clears the file, the engine's own copy times out
    /// later), and §12 allows exactly one live candidate — so a second append for an id
    /// that is already here is a duplicate of the same event, not a second event.
    ///
    /// Does not throw. Losing the history entry must never cost the user the confirmation
    /// it belongs to.
    func append(_ entry: CandidateHistoryEntry)
}

/// JSON file beside `candidate.json`, written atomically.
///
/// The same three choices `FileParkingCandidateStore` documents — a plain file rather
/// than SwiftData, `.completeUntilFirstUserAuthentication` on the directory, an envelope
/// with a schema version — for the same reasons, because it is written from the same
/// background wake.
struct FileCandidateHistoryStore: CandidateHistoryStoring {
    /// docs/10 §7b "The last 30 entries". Older ones fall off; a user looking further
    /// back wants the parking history, which is a different screen that keeps everything.
    static let maximumEntries = 30

    /// Bumped whenever the encoded shape changes. An older payload is dropped rather than
    /// half-decoded: the worst case is an empty bell, and §7b's retention already says
    /// entries are not kept forever.
    static let schemaVersion = 1

    private static let directoryName = "Detection"
    private static let fileName = "candidate-history.json"

    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// Production location: the `Detection` directory the checkpoint already creates with
    /// the right protection class.
    static func defaultFileURL() throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = support.appending(path: directoryName, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return directory.appending(path: fileName, directoryHint: .notDirectory)
    }

    func entries() -> [CandidateHistoryEntry] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.schemaVersion == Self.schemaVersion
        else {
            AppLog.detection.notice("candidate history unreadable; treating as empty")
            return []
        }
        return envelope.entries
    }

    func append(_ entry: CandidateHistoryEntry) {
        var current = entries()
        guard !current.contains(where: { $0.id == entry.id }) else { return }
        current.insert(entry, at: 0)
        let capped = Array(current.prefix(Self.maximumEntries))
        do {
            let data = try JSONEncoder().encode(Envelope(schemaVersion: Self.schemaVersion, entries: capped))
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            // Best-effort by design: the confirmation is already written and the parking
            // record is what the user actually needs. A bell short one row is the cost.
            let nsError = error as NSError
            let reason = "\(nsError.domain)(\(nsError.code))"
            AppLog.detection.notice("candidate history append failed: \(reason, privacy: .public)")
        }
    }

    private struct Envelope: Codable {
        let schemaVersion: Int
        /// Newest first, so a read does not have to sort and the cap is a `prefix`.
        let entries: [CandidateHistoryEntry]
    }
}

/// The history for a build that has no directory to write to.
///
/// Holds nothing and refuses nothing, exactly like `UnavailableParkingCandidateStore`: a
/// bell with no rows is a degraded feature, not an app failure (CLAUDE.md).
struct UnavailableCandidateHistoryStore: CandidateHistoryStoring {
    func entries() -> [CandidateHistoryEntry] {
        []
    }

    func append(_: CandidateHistoryEntry) {}
}

import Foundation

enum ParkingCandidateStoreError: Error, Equatable {
    case writeFailed(String)
    case removeFailed(String)
}

/// The one pending candidate, wherever the process that needs it happens to be running.
///
/// **At most one.** docs/05 §12 allows one candidate per travel session and §10a makes a
/// newer one supersede an older, so there is nothing a list would express that a single
/// slot does not — and a list is how two notifications end up on a lock screen.
///
/// Synchronous and `Sendable`, like `DetectionCheckpointStoring`, because the writer is an
/// actor on a background wake and the reader is the main actor a moment later.
protocol ParkingCandidateStoring: Sendable {
    /// Never throws. A candidate file that will not decode is a candidate the user has
    /// lost, not an app failure — they still have manual parking (CLAUDE.md).
    func load() -> ParkingCandidate?
    func save(_ candidate: ParkingCandidate) throws
    func clear() throws
}

/// JSON file beside `checkpoint.json`, written atomically.
///
/// Every choice here is `FileDetectionCheckpointStore`'s, for the same reasons, and they
/// are worth restating because getting either one wrong is silent:
///
/// 1. **A plain file, not SwiftData.** The candidate is created on a significant-change
///    wake, which docs/04 §7 holds to minimal work.
/// 2. **`.completeUntilFirstUserAuthentication` on the directory.** A wake happens with
///    the device locked; under the default protection class the write would fail there
///    and the user would simply never hear about the parking.
struct FileParkingCandidateStore: ParkingCandidateStoring {
    /// Bumped whenever the encoded shape changes. An older payload is dropped rather than
    /// half-decoded — a candidate is worth 45 minutes, so there is nothing to migrate.
    static let schemaVersion = 1

    private static let directoryName = "Detection"
    private static let fileName = "candidate.json"

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

    func load() -> ParkingCandidate? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.schemaVersion == Self.schemaVersion
        else {
            AppLog.detection.notice("candidate file unreadable; treating as no candidate")
            return nil
        }
        return envelope.candidate
    }

    func save(_ candidate: ParkingCandidate) throws {
        let envelope = Envelope(schemaVersion: Self.schemaVersion, candidate: candidate)
        do {
            let data = try JSONEncoder().encode(envelope)
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            throw ParkingCandidateStoreError.writeFailed(Self.reason(for: error))
        }
    }

    func clear() throws {
        do {
            try FileManager.default.removeItem(at: fileURL)
        } catch let error as CocoaError where error.code == .fileNoSuchFile {
            return
        } catch {
            throw ParkingCandidateStoreError.removeFailed(Self.reason(for: error))
        }
    }

    /// Short and free of user data — it reaches diagnostics.
    private static func reason(for error: some Error) -> String {
        let nsError = error as NSError
        return "\(nsError.domain)(\(nsError.code))"
    }

    private struct Envelope: Codable {
        let schemaVersion: Int
        let candidate: ParkingCandidate
    }
}

/// The store for a build that has no directory to write to.
///
/// Holds nothing and refuses nothing: a candidate that cannot be persisted is a candidate
/// the user never hears about, and that is a degraded feature rather than an app failure
/// (CLAUDE.md — "권한 거부는 앱 전체 failure가 아님", and the same is true of storage).
struct UnavailableParkingCandidateStore: ParkingCandidateStoring {
    func load() -> ParkingCandidate? {
        nil
    }

    func save(_: ParkingCandidate) throws {}
    func clear() throws {}
}

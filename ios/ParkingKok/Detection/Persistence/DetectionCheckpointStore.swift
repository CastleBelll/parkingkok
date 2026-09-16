import Foundation

/// Why a checkpoint could not be restored.
///
/// Silent recovery loses field data in P0, so every failure keeps a reason the
/// diagnostics screen can show.
enum DetectionCheckpointLoadFailure: Sendable, Equatable {
    /// The file exists but the OS would not hand it over — the expected symptom of a
    /// wrong data-protection class on a locked device.
    case unreadable(String)
    case corrupt(String)
    case schemaMismatch(found: Int, expected: Int)

    var diagnosticDescription: String {
        switch self {
        case let .unreadable(reason): "unreadable: \(reason)"
        case let .corrupt(reason): "corrupt: \(reason)"
        case let .schemaMismatch(found, expected): "schema mismatch: found \(found), expected \(expected)"
        }
    }
}

enum DetectionCheckpointLoadResult: Sendable, Equatable {
    case restored(DetectionCheckpoint)
    case absent
    case failed(DetectionCheckpointLoadFailure)

    var checkpoint: DetectionCheckpoint? {
        if case let .restored(checkpoint) = self {
            return checkpoint
        }
        return nil
    }
}

enum DetectionCheckpointStoreError: Error, Equatable {
    case writeFailed(String)
    case removeFailed(String)
}

protocol DetectionCheckpointStoring: Sendable {
    /// Never throws: a missing or damaged checkpoint is a recoverable state, not an app
    /// failure. The caller decides what to do with the reason.
    func load() -> DetectionCheckpointLoadResult
    func save(_ checkpoint: DetectionCheckpoint) throws
    func clear() throws
}

/// JSON file in Application Support, written atomically.
///
/// Two deliberate choices (docs/04_IOS_IMPLEMENTATION.md §6, §7):
///
/// 1. **A plain file, not SwiftData.** A significant-change relaunch must read this on
///    the fastest possible path, before any stack is spun up. docs/04 §7 says background
///    callbacks do minimal work.
/// 2. **`.completeFileProtectionUntilFirstUserAuthentication`.** The default protection
///    class makes a file unreadable while the device is locked — which is exactly when
///    significant-change wakes happen. With the default, rehydration would fail silently
///    in the field and look like a detection bug.
struct FileDetectionCheckpointStore: DetectionCheckpointStoring {
    /// Bump whenever the encoded shape changes; an older payload is rejected rather than
    /// half-decoded.
    static let schemaVersion = 1

    private static let directoryName = "Detection"
    private static let fileName = "checkpoint.json"

    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// Production location. Throws only when Application Support itself is unavailable.
    static func defaultFileURL() throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = support.appending(path: directoryName, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appending(path: fileName, directoryHint: .notDirectory)
    }

    func load() -> DetectionCheckpointLoadResult {
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            return .absent
        } catch {
            return .failed(.unreadable(Self.reason(for: error)))
        }

        let envelope: Envelope
        do {
            envelope = try JSONDecoder().decode(Envelope.self, from: data)
        } catch {
            return .failed(.corrupt(Self.reason(for: error)))
        }

        guard envelope.schemaVersion == Self.schemaVersion else {
            return .failed(.schemaMismatch(found: envelope.schemaVersion, expected: Self.schemaVersion))
        }
        return .restored(envelope.checkpoint)
    }

    func save(_ checkpoint: DetectionCheckpoint) throws {
        let envelope = Envelope(schemaVersion: Self.schemaVersion, checkpoint: checkpoint)
        do {
            let data = try JSONEncoder().encode(envelope)
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            throw DetectionCheckpointStoreError.writeFailed(Self.reason(for: error))
        }
    }

    func clear() throws {
        do {
            try FileManager.default.removeItem(at: fileURL)
        } catch let error as CocoaError where error.code == .fileNoSuchFile {
            return
        } catch {
            throw DetectionCheckpointStoreError.removeFailed(Self.reason(for: error))
        }
    }

    /// Keeps the reason short and free of user data — it is rendered in diagnostics.
    private static func reason(for error: some Error) -> String {
        let nsError = error as NSError
        return "\(nsError.domain)(\(nsError.code))"
    }

    private struct Envelope: Codable {
        let schemaVersion: Int
        let checkpoint: DetectionCheckpoint
    }
}

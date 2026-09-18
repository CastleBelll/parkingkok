import Foundation

/// One stored parking photo, as it comes back off disk.
///
/// `savedAt` is the file's modification date rather than a new column: the mock captions
/// the photo panel with when it was saved, and docs/06 §2's record schema is shared with
/// Android field-for-field — adding an iOS-only timestamp there would break that
/// contract for a caption. `nil` when the filesystem will not say.
struct ParkingPhoto: Sendable, Equatable {
    let data: Data
    let savedAt: Date?
}

/// The one photo a parking record may carry (FR-007).
///
/// A protocol for the same reason `ParkingStoring` is one: the model must be testable
/// without reaching the app container, and every screen has to keep working when this
/// fails. FR-007 is one photo per record, so `recordID` is the whole key — there is no
/// list, no ordering and no second slot.
///
/// Nothing here is `@MainActor`. Downsampling and file IO have no business on the actor
/// that draws the screen; the calls are `async` so the caller suspends instead of the
/// UI stalling.
protocol ParkingPhotoStoring: Sendable {
    /// Downsamples `imageData` and stores it as the photo for `recordID`, replacing any
    /// photo already there. Returns the path to persist in `photoRelativePath`.
    func save(_ imageData: Data, for recordID: UUID) async throws -> String
    /// The stored photo, or `.notFound` when the file is gone — a restore, a cleanup, or
    /// a user who cleared storage all produce a record whose photo no longer exists.
    func load(_ relativePath: String) async throws -> ParkingPhoto
    func remove(_ relativePath: String) async throws
    /// docs/04 §10 "remove orphan on record delete" / "periodic orphan cleanup": drops
    /// every stored file that no record still points at.
    func removeOrphans(keeping keptPaths: Set<String>) async throws
}

/// FR-007's storage, at `Application Support/ParkingPhotos/{recordId}.heic`
/// (docs/04_IOS_IMPLEMENTATION.md §10).
///
/// App-private and local-only. docs/07 §2 forbids Firebase Storage and docs/09 §7 keeps
/// the photo in the OS sandbox, so there is no upload path here to disable later.
///
/// **Backup policy (docs/09 §17, reviewed rather than assumed).** The file is left
/// *included* in iOS backup. §17 asks for one of two things — exclude the file, or keep
/// the disclosure honest — and the safe claim at docs/09 §13 says only that the photo
/// is not stored on 주차콕's servers, which stays true of an iCloud backup held under the
/// user's own account. Excluding it would also desynchronise restore: the SwiftData
/// record *is* backed up, so an excluded photo comes back as a record pointing at
/// nothing. docs/04 §10 asks for backup exclusion of generated *cache thumbnails*, which
/// implies the photo itself is meant to survive; this store generates no thumbnails.
struct FileSystemParkingPhotoStore: ParkingPhotoStoring {
    /// docs/04 §10.
    static let directoryName = "ParkingPhotos"
    static let fileExtension = "heic"
    /// Hidden so `removeOrphans`' directory sweep cannot mistake a half-written file for
    /// a stored photo.
    static let stagingDirectoryName = ".staging"

    let root: URL

    /// The directory the running app uses.
    static func applicationSupport() throws -> FileSystemParkingPhotoStore {
        do {
            let base = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            return FileSystemParkingPhotoStore(
                root: base.appending(path: directoryName, directoryHint: .isDirectory)
            )
        } catch {
            throw ParkingPhotoError.directoryUnavailable(String(describing: error))
        }
    }

    /// `{recordId}.heic`. One record, one name — so re-attaching a photo overwrites
    /// rather than accumulating, and an orphan is recognisable by its record id alone.
    static func relativePath(for recordID: UUID) -> String {
        "\(recordID.uuidString).\(fileExtension)"
    }

    func save(_ imageData: Data, for recordID: UUID) async throws -> String {
        let relativePath = Self.relativePath(for: recordID)
        let destination = try fileURL(for: relativePath)
        // Off the caller's actor: this is the decode/encode, and it is the expensive part.
        let encoded = try await Task.detached(priority: .userInitiated) {
            try ParkingPhotoDownsampler.heicData(from: imageData)
        }.value

        try createDirectories()
        let staged = root
            .appending(path: Self.stagingDirectoryName, directoryHint: .isDirectory)
            .appending(path: "\(UUID().uuidString).\(Self.fileExtension)", directoryHint: .notDirectory)
        do {
            // docs/04 §10 "write atomic temp → final move". The staged write is itself
            // atomic, and the move publishes it: a reader either sees the previous photo
            // or the new one, never a truncated file.
            try encoded.write(to: staged, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            try moveIntoPlace(from: staged, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: staged)
            throw ParkingPhotoError.writeFailed(String(describing: error))
        }
        return relativePath
    }

    func load(_ relativePath: String) async throws -> ParkingPhoto {
        let url = try fileURL(for: relativePath)
        guard let data = try? Data(contentsOf: url) else {
            throw ParkingPhotoError.notFound(relativePath)
        }
        let savedAt = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
        return ParkingPhoto(data: data, savedAt: savedAt)
    }

    func remove(_ relativePath: String) async throws {
        let url = try fileURL(for: relativePath)
        let manager = FileManager.default
        guard manager.fileExists(atPath: url.path(percentEncoded: false)) else { return }
        do {
            try manager.removeItem(at: url)
        } catch {
            throw ParkingPhotoError.writeFailed(String(describing: error))
        }
    }

    func removeOrphans(keeping keptPaths: Set<String>) async throws {
        let manager = FileManager.default
        guard let entries = try? manager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles, .skipsSubdirectoryDescendants]
        ) else {
            // No directory yet is the ordinary state before the first photo, not a failure.
            return
        }
        for entry in entries where !keptPaths.contains(entry.lastPathComponent) {
            try? manager.removeItem(at: entry)
        }
        // Staging is hidden, so the sweep above skipped it. A file left there means a
        // move that died between write and publish; nothing will ever claim it.
        let staging = root.appending(path: Self.stagingDirectoryName, directoryHint: .isDirectory)
        if let stale = try? manager.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil) {
            for entry in stale {
                try? manager.removeItem(at: entry)
            }
        }
    }

    /// Resolves a stored path inside `root`, and only inside it.
    ///
    /// The values come from the app's own store, so this is defence rather than a live
    /// hazard — but a path that traverses out of the sandbox directory is the one bug in
    /// this file that would be worth a CVE, and one comparison prevents it.
    func fileURL(for relativePath: String) throws -> URL {
        guard relativePath == (relativePath as NSString).lastPathComponent,
              !relativePath.isEmpty,
              relativePath != ".",
              relativePath != ".."
        else {
            throw ParkingPhotoError.notFound(relativePath)
        }
        return root.appending(path: relativePath, directoryHint: .notDirectory)
    }

    private func createDirectories() throws {
        let manager = FileManager.default
        do {
            for directory in [root, root.appending(path: Self.stagingDirectoryName, directoryHint: .isDirectory)] {
                try manager.createDirectory(at: directory, withIntermediateDirectories: true)
            }
        } catch {
            throw ParkingPhotoError.directoryUnavailable(String(describing: error))
        }
    }

    /// `replaceItemAt` is the atomic swap, but it needs something to replace; a first
    /// photo has nothing there yet and takes the plain move.
    private func moveIntoPlace(from staged: URL, to destination: URL) throws {
        let manager = FileManager.default
        if manager.fileExists(atPath: destination.path(percentEncoded: false)) {
            _ = try manager.replaceItemAt(destination, withItemAt: staged)
        } else {
            try manager.moveItem(at: staged, to: destination)
        }
    }
}

/// The store that has nowhere to write, made explicit.
///
/// The counterpart of `UnavailableParkingLocationProvider`: previews, unit tests that do
/// not care about photos, and the app itself when the container cannot be created. A
/// record without a photo is an ordinary record (FR-007 is one *optional* photo), so
/// this degrades the feature rather than the app.
struct UnavailableParkingPhotoStore: ParkingPhotoStoring {
    func save(_: Data, for _: UUID) async throws -> String {
        throw ParkingPhotoError.directoryUnavailable("photo storage unavailable")
    }

    func load(_ relativePath: String) async throws -> ParkingPhoto {
        throw ParkingPhotoError.notFound(relativePath)
    }

    func remove(_: String) async throws {}

    func removeOrphans(keeping _: Set<String>) async throws {}
}

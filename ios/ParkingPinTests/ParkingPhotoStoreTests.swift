import Foundation
import Testing
@testable import ParkingPin

/// FR-007's photo: the downsample, the atomic write, and the cleanup that keeps the
/// directory honest (docs/04_IOS_IMPLEMENTATION.md §10).
struct ParkingPhotoStoreTests {
    // ── Downsample (FR-007 "long edge target ~1600px") ──────────────────────

    @Test("A photo larger than the target comes back with its long edge at 1600px")
    func downsamplesLongEdgeToTarget() throws {
        // Arrange — 4032×3024 is what a current iPhone rear camera produces.
        let source = try TestImage.jpegData(width: 4032, height: 3024)

        // Act
        let stored = try ParkingPhotoDownsampler.heicData(from: source)

        // Assert
        let size = try TestImage.pixelSize(of: stored)
        #expect(size.width == ParkingPhotoDownsampler.maximumLongEdge)
        #expect(size.height == 1200)
    }

    @Test("A portrait photo is measured on its own long edge, not on its width")
    func downsamplesPortraitOnHeight() throws {
        // Arrange
        let source = try TestImage.jpegData(width: 3024, height: 4032)

        // Act
        let stored = try ParkingPhotoDownsampler.heicData(from: source)

        // Assert
        let size = try TestImage.pixelSize(of: stored)
        #expect(size.height == ParkingPhotoDownsampler.maximumLongEdge)
        #expect(size.width == 1200)
    }

    /// Edge case: upscaling a small photo would cost bytes and add nothing. ImageIO's
    /// thumbnail is a ceiling, not a target, and this pins that behaviour.
    @Test("A photo already under the target is not enlarged")
    func leavesSmallPhotoAlone() throws {
        // Arrange
        let source = try TestImage.jpegData(width: 640, height: 480)

        // Act
        let stored = try ParkingPhotoDownsampler.heicData(from: source)

        // Assert
        let size = try TestImage.pixelSize(of: stored)
        #expect(size.width == 640)
        #expect(size.height == 480)
    }

    /// Edge case: the exact boundary. 1600 is inside the limit and must survive intact.
    @Test("A photo exactly at the target keeps every pixel")
    func keepsPhotoAtExactTarget() throws {
        // Arrange
        let source = try TestImage.jpegData(width: 1600, height: 900)

        // Act
        let stored = try ParkingPhotoDownsampler.heicData(from: source)

        // Assert
        let size = try TestImage.pixelSize(of: stored)
        #expect(size.width == 1600)
        #expect(size.height == 900)
    }

    @Test("Bytes that are not an image are refused rather than stored")
    func refusesNonImageData() throws {
        // Arrange
        let garbage = Data("not an image".utf8)

        // Act / Assert
        #expect(throws: ParkingPhotoError.unreadableImage) {
            try ParkingPhotoDownsampler.heicData(from: garbage)
        }
    }

    /// The stored file is the thing that gets backed up and, one day, exported. A picked
    /// library photo routinely carries GPS EXIF, and docs/06 §1 classifies coordinates as
    /// local-sensitive; the thumbnail path drops all metadata, and this says so out loud.
    @Test("The stored photo carries no metadata from the source")
    func stripsSourceMetadata() throws {
        // Arrange
        let source = try TestImage.jpegData(width: 2000, height: 1500)

        // Act
        let stored = try ParkingPhotoDownsampler.heicData(from: source)

        // Assert — no EXIF/GPS dictionaries survived the downsample.
        let properties = try TestImage.allProperties(of: stored)
        #expect(properties["{GPS}"] == nil)
        #expect(properties["{Exif}"] == nil)
    }

    // ── Storage (docs/04 §10 "write atomic temp → final move") ──────────────

    @Test("A saved photo lands at {recordId}.heic and comes back byte for byte")
    func savesAtRecordIDPath() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let recordID = UUID()
        let source = try TestImage.jpegData(width: 2000, height: 1500)

        // Act
        let relativePath = try await store.save(source, for: recordID)
        let loaded = try await store.load(relativePath)

        // Assert
        #expect(relativePath == "\(recordID.uuidString).heic")
        let onDisk = try Data(contentsOf: store.fileURL(for: relativePath))
        #expect(loaded.data == onDisk)
        #expect(loaded.savedAt != nil)
    }

    @Test("The staging directory is empty once the move has published the file")
    func leavesNoTemporaryFileBehind() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let source = try TestImage.jpegData(width: 2000, height: 1500)

        // Act
        _ = try await store.save(source, for: UUID())

        // Assert
        let staging = store.root.appending(
            path: FileSystemParkingPhotoStore.stagingDirectoryName,
            directoryHint: .isDirectory
        )
        let leftovers = try FileManager.default.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil)
        #expect(leftovers.isEmpty)
    }

    /// FR-007 is one photo per record. A second save replaces the first rather than
    /// leaving two files that both claim to be this parking.
    @Test("Saving a second photo for the same record replaces the first")
    func replacesExistingPhoto() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let recordID = UUID()
        let first = try TestImage.jpegData(width: 1600, height: 1200)
        let second = try TestImage.jpegData(width: 800, height: 600)

        // Act
        let firstPath = try await store.save(first, for: recordID)
        let secondPath = try await store.save(second, for: recordID)

        // Assert — same name, new pixels, one file in the directory.
        #expect(firstPath == secondPath)
        let stored = try await store.load(secondPath)
        let size = try TestImage.pixelSize(of: stored.data)
        #expect(size.width == 800)
        let entries = try FileManager.default.contentsOfDirectory(
            at: store.root,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )
        #expect(entries.count == 1)
    }

    @Test("Loading a path with no file behind it reports it as missing")
    func reportsMissingFile() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let path = FileSystemParkingPhotoStore.relativePath(for: UUID())

        // Act / Assert
        await #expect(throws: ParkingPhotoError.notFound(path)) {
            try await store.load(path)
        }
    }

    @Test("Removing a photo that is already gone is not an error")
    func removingMissingPhotoSucceeds() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()

        // Act / Assert — a record deleted twice must not surface a failure.
        try await store.remove(FileSystemParkingPhotoStore.relativePath(for: UUID()))
    }

    /// The stored values come from the app's own database, so this is defence rather
    /// than a live hazard — but it is the one bug in this file worth a CVE.
    @Test("A stored path that tries to escape the photo directory is refused")
    func refusesPathTraversal() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()

        // Act / Assert
        for escape in ["../secrets.heic", "nested/photo.heic", "..", ""] {
            await #expect(throws: ParkingPhotoError.notFound(escape)) {
                try await store.load(escape)
            }
        }
    }

    // ── Orphan cleanup (docs/04 §10) ────────────────────────────────────────

    @Test("The orphan sweep keeps referenced photos and drops the rest")
    func removesOrphansOnly() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let source = try TestImage.jpegData(width: 800, height: 600)
        let kept = try await store.save(source, for: UUID())
        let orphan = try await store.save(source, for: UUID())

        // Act
        try await store.removeOrphans(keeping: [kept])

        // Assert
        _ = try await store.load(kept)
        await #expect(throws: ParkingPhotoError.notFound(orphan)) {
            try await store.load(orphan)
        }
    }

    @Test("The sweep clears a half-written staging file nothing will ever claim")
    func clearsStaleStagingFiles() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let source = try TestImage.jpegData(width: 800, height: 600)
        let kept = try await store.save(source, for: UUID())
        let staging = store.root.appending(
            path: FileSystemParkingPhotoStore.stagingDirectoryName,
            directoryHint: .isDirectory
        )
        let stale = staging.appending(path: "interrupted.heic", directoryHint: .notDirectory)
        try Data("half a photo".utf8).write(to: stale)

        // Act
        try await store.removeOrphans(keeping: [kept])

        // Assert
        #expect(!FileManager.default.fileExists(atPath: stale.path(percentEncoded: false)))
    }

    @Test("Sweeping a directory that does not exist yet is not an error")
    func sweepingEmptyStoreSucceeds() async throws {
        // Arrange — nothing has ever been saved, so the directory was never created.
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()

        // Act / Assert
        try await store.removeOrphans(keeping: [])
    }

    // ── The store with nowhere to write ─────────────────────────────────────

    /// CLAUDE.md: a capability that is unavailable is not an app failure. The null store
    /// refuses to save and stays silent about everything else.
    @Test("The unavailable store fails the save and no-ops the cleanup")
    func unavailableStoreDegradesQuietly() async throws {
        // Arrange
        let store = UnavailableParkingPhotoStore()

        // Act / Assert
        await #expect(throws: ParkingPhotoError.self) {
            _ = try await store.save(Data(), for: UUID())
        }
        try await store.remove("anything.heic")
        try await store.removeOrphans(keeping: [])
    }
}

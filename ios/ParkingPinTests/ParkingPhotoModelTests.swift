import Foundation
import Testing
@testable import ParkingPin

/// FR-007 through the application layer: attaching, replacing and removing the one
/// photo, and the orphan cleanup docs/04 §10 asks for.
@MainActor
struct ParkingPhotoModelTests {
    private static let start = Date(timeIntervalSince1970: 1_700_000_000)

    private func makeModel(photoStore: any ParkingPhotoStoring) throws -> ParkingModel {
        let store = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: FixedDateProvider(Self.start)
        )
        let model = ParkingModel(
            store: store,
            locationProvider: UnavailableParkingLocationProvider(),
            photoStore: photoStore,
            clock: FixedDateProvider(Self.start)
        )
        model.refresh()
        return model
    }

    private func startParking(_ model: ParkingModel) async throws -> UUID {
        #expect(await model.saveManualParking(ManualParkingDraft(floorText: "B3", zone: "A구역")))
        return try #require(model.activeSession?.id)
    }

    // ── Attach ──────────────────────────────────────────────────────────────

    @Test("Attaching a photo stores the file and points the record at it")
    func attachesPhotoToRecord() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let model = try makeModel(photoStore: store)
        let sessionID = try await startParking(model)
        let source = try TestImage.jpegData(width: 3000, height: 2000)

        // Act
        let attached = await model.attachPhoto(source, to: sessionID)

        // Assert
        #expect(attached)
        let relativePath = try #require(model.session(id: sessionID)?.photoRelativePath)
        #expect(relativePath == "\(sessionID.uuidString).heic")
        let stored = try await store.load(relativePath)
        let size = try TestImage.pixelSize(of: stored.data)
        #expect(size.width == ParkingPhotoDownsampler.maximumLongEdge)
        #expect(model.failure == nil)
    }

    /// A photo that cannot be stored must leave the parking itself untouched — FR-007 is
    /// one *optional* photo, not a precondition for having a record.
    @Test("A photo that will not decode leaves the record and the app intact")
    func failedAttachLeavesRecordIntact() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let model = try makeModel(photoStore: directory.makePhotoStore())
        let sessionID = try await startParking(model)

        // Act
        let attached = await model.attachPhoto(Data("not an image".utf8), to: sessionID)

        // Assert
        #expect(!attached)
        #expect(model.session(id: sessionID)?.photoRelativePath == nil)
        #expect(model.session(id: sessionID)?.floor?.displayText == "B3")
        #expect(model.failure != nil)
    }

    /// CLAUDE.md: a denied or unavailable capability is not an app failure.
    @Test("With no photo storage at all the parking still saves, edits and ends")
    func worksWithoutPhotoStorage() async throws {
        // Arrange
        let model = try makeModel(photoStore: UnavailableParkingPhotoStore())
        let sessionID = try await startParking(model)

        // Act
        let attached = await model.attachPhoto(Data("anything".utf8), to: sessionID)
        model.clearFailure()
        let stepped = model.stepActiveFloor(by: -1)
        let ended = model.endActiveParking()

        // Assert
        #expect(!attached)
        #expect(stepped)
        #expect(ended)
        #expect(model.completedSessions.count == 1)
        #expect(model.failure == nil)
    }

    // ── Remove ──────────────────────────────────────────────────────────────

    @Test("Removing the photo clears the column and deletes the file, keeping the parking")
    func removesPhotoButKeepsRecord() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let model = try makeModel(photoStore: store)
        let sessionID = try await startParking(model)
        let source = try TestImage.jpegData(width: 1200, height: 900)
        #expect(await model.attachPhoto(source, to: sessionID))
        let relativePath = try #require(model.session(id: sessionID)?.photoRelativePath)

        // Act
        let removed = await model.removePhoto(from: sessionID)

        // Assert
        #expect(removed)
        #expect(model.session(id: sessionID) != nil)
        #expect(model.session(id: sessionID)?.photoRelativePath == nil)
        await #expect(throws: ParkingPhotoError.notFound(relativePath)) {
            try await store.load(relativePath)
        }
    }

    @Test("Removing a photo from a record that has none is a no-op")
    func removingAbsentPhotoIsHarmless() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let model = try makeModel(photoStore: directory.makePhotoStore())
        let sessionID = try await startParking(model)

        // Act
        let removed = await model.removePhoto(from: sessionID)

        // Assert
        #expect(!removed)
        #expect(model.failure == nil)
    }

    // ── Delete (docs/04 §10 "remove orphan on record delete") ───────────────

    @Test("Deleting the record deletes its photo file too")
    func deletingRecordDeletesPhoto() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let model = try makeModel(photoStore: store)
        let sessionID = try await startParking(model)
        #expect(try await model.attachPhoto(TestImage.jpegData(width: 1200, height: 900), to: sessionID))
        let relativePath = try #require(model.session(id: sessionID)?.photoRelativePath)

        // Act
        let deleted = await model.delete(id: sessionID)

        // Assert
        #expect(deleted)
        #expect(model.session(id: sessionID) == nil)
        await #expect(throws: ParkingPhotoError.notFound(relativePath)) {
            try await store.load(relativePath)
        }
    }

    @Test("Deleting a record that never had a photo touches no file")
    func deletingRecordWithoutPhotoTouchesNothing() async throws {
        // Arrange
        let spy = SpyParkingPhotoStore()
        let model = try makeModel(photoStore: spy)
        let sessionID = try await startParking(model)

        // Act
        let deleted = await model.delete(id: sessionID)

        // Assert
        #expect(deleted)
        #expect(spy.removed.isEmpty)
    }

    @Test("전체 삭제 clears the photo directory as well as the records")
    func deleteAllRemovesEveryPhoto() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let model = try makeModel(photoStore: store)
        let sessionID = try await startParking(model)
        #expect(try await model.attachPhoto(TestImage.jpegData(width: 800, height: 600), to: sessionID))
        let relativePath = try #require(model.session(id: sessionID)?.photoRelativePath)

        // Act
        let deleted = await model.deleteAllLocalData()

        // Assert
        #expect(deleted)
        await #expect(throws: ParkingPhotoError.notFound(relativePath)) {
            try await store.load(relativePath)
        }
    }

    // ── Orphan sweep ────────────────────────────────────────────────────────

    @Test("The launch sweep keeps every referenced photo and drops the rest")
    func sweepKeepsReferencedPhotos() async throws {
        // Arrange — one photo on the active parking, one file nothing points at.
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let model = try makeModel(photoStore: store)
        let sessionID = try await startParking(model)
        let source = try TestImage.jpegData(width: 800, height: 600)
        #expect(await model.attachPhoto(source, to: sessionID))
        let kept = try #require(model.session(id: sessionID)?.photoRelativePath)
        let orphan = try await store.save(source, for: UUID())

        // Act
        await model.removeOrphanPhotos()

        // Assert
        _ = try await store.load(kept)
        await #expect(throws: ParkingPhotoError.notFound(orphan)) {
            try await store.load(orphan)
        }
    }

    /// The dangerous case. An unreadable container leaves `completedSessions` empty,
    /// which looks exactly like "the user has no history" — and would delete every photo
    /// on the device.
    @Test("The sweep refuses to run while the record store is in a failed state")
    func sweepRefusesAfterStoreFailure() async throws {
        // Arrange
        let spy = SpyParkingPhotoStore()
        let model = try makeModel(photoStore: spy)
        let sessionID = try await startParking(model)
        // A second active parking is refused by FR-004, which puts the model into failure.
        #expect(await model.saveManualParking(ManualParkingDraft(floorText: "B1")) == false)
        #expect(model.failure != nil)

        // Act
        await model.removeOrphanPhotos()

        // Assert
        #expect(spy.sweeps.isEmpty)

        // And once the failure is cleared it runs, keeping the live record's photo.
        model.clearFailure()
        _ = try await model.attachPhoto(TestImage.jpegData(width: 400, height: 300), to: sessionID)
        await model.removeOrphanPhotos()
        let kept = try #require(model.session(id: sessionID)?.photoRelativePath)
        #expect(spy.sweeps == [[kept]])
    }

    // ── Reading back ────────────────────────────────────────────────────────

    @Test("A record with no photo reads back as no photo rather than as an error")
    func photoLookupOnRecordWithoutPhoto() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let model = try makeModel(photoStore: directory.makePhotoStore())
        let sessionID = try await startParking(model)
        let session = try #require(model.session(id: sessionID))

        // Act
        let photo = await model.photo(for: session)

        // Assert
        #expect(photo == nil)
        #expect(model.failure == nil)
    }

    /// The file can vanish under a live record — an iCloud restore still downloading,
    /// storage cleared by the OS. The screen shows "다시 추가" for this, not a crash.
    @Test("A record whose file has vanished reads back as no photo")
    func photoLookupSurvivesAMissingFile() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let store = directory.makePhotoStore()
        let model = try makeModel(photoStore: store)
        let sessionID = try await startParking(model)
        #expect(try await model.attachPhoto(TestImage.jpegData(width: 800, height: 600), to: sessionID))
        let relativePath = try #require(model.session(id: sessionID)?.photoRelativePath)
        try FileManager.default.removeItem(at: store.fileURL(for: relativePath))

        // Act
        let session = try #require(model.session(id: sessionID))
        let photo = await model.photo(for: session)

        // Assert
        #expect(photo == nil)
    }

    /// docs/06 §8 keeps the id across the completion commit, so the photo follows the
    /// parking into history without being re-saved.
    @Test("A photo attached while parked is still on the record after 주차 종료")
    func photoSurvivesEndingTheParking() async throws {
        // Arrange
        let directory = try TemporaryDirectory()
        let model = try makeModel(photoStore: directory.makePhotoStore())
        let sessionID = try await startParking(model)
        #expect(try await model.attachPhoto(TestImage.jpegData(width: 800, height: 600), to: sessionID))

        // Act
        #expect(model.endActiveParking())

        // Assert
        #expect(model.session(id: sessionID)?.photoRelativePath == "\(sessionID.uuidString).heic")
        #expect(model.completedSessions.first?.id == sessionID)
    }

    /// The pillar photo is attached right after the save, and the one-shot fix lands while
    /// the photo is still being written. Found on an iPhone on 2026-09-24: the fix was
    /// written at 9 m, then the photo write put back the location-less copy it had read
    /// before its `await`, and the record kept no location at all.
    @Test("A location written while the photo is being saved survives the photo")
    func photoKeepsLocationWrittenMeanwhile() async throws {
        // Arrange
        let photoStore = GatedParkingPhotoStore()
        let model = try makeModel(photoStore: photoStore)
        let sessionID = try await startParking(model)
        let attaching = Task { await model.attachPhoto(Data("pillar".utf8), to: sessionID) }
        await photoStore.waitUntilSaving()

        // Act — what `attachCurrentFix` does when the fix arrives
        var located = try #require(model.session(id: sessionID))
        located.location = ParkedLocation(
            latitude: 37.5,
            longitude: 127.0,
            horizontalAccuracy: 9,
            capturedAt: Self.start
        )
        #expect(model.update(located))
        photoStore.finishSaving()
        let attached = await attaching.value

        // Assert
        #expect(attached)
        let session = try #require(model.session(id: sessionID))
        #expect(session.location?.horizontalAccuracy == 9)
        #expect(session.photoRelativePath == "\(sessionID.uuidString).heic")
    }
}

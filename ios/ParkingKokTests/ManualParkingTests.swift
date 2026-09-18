import Foundation
import Testing
@testable import ParkingKok

/// **FR-001, the hard constraint.**
///
/// CLAUDE.md: "Manual parking은 항상 가능해야 한다." These tests drive `ParkingModel`
/// with a location provider that behaves exactly as one does when location, motion and
/// notification permission have all been refused — it returns nothing — and prove the
/// save still completes and the record still reads back.
@MainActor
struct ManualParkingTests {
    private static let now = Date(timeIntervalSince1970: 1_700_000_000)
    private var now: Date {
        Self.now
    }

    private func makeModel(
        locationProvider: any ParkingLocationProviding = UnavailableParkingLocationProvider(),
        clock: any DateProviding = FixedDateProvider(ManualParkingTests.now)
    ) throws -> ParkingModel {
        let store = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: clock
        )
        return ParkingModel(store: store, locationProvider: locationProvider, clock: clock)
    }

    @Test("With no permission of any kind, a manual parking saves and reads back")
    func savesWithEveryPermissionDenied() async throws {
        // Arrange — `UnavailableParkingLocationProvider` is the permission-less case:
        // no location manager, no prompt, nothing to return.
        let model = try makeModel()
        let draft = ManualParkingDraft(floorText: "B3", zone: "A구역", spot: "142", memo: "기둥 옆")

        // Act
        let saved = await model.saveManualParking(draft)

        // Assert — saved…
        #expect(saved)
        #expect(model.failure == nil)
        // …and readable, which is the half that makes the save worth anything.
        model.refresh()
        let active = try #require(model.activeSession)
        #expect(active.floor?.displayText == "B3")
        #expect(active.zone == "A구역")
        #expect(active.spot == "142")
        #expect(active.memo == "기둥 옆")
        #expect(active.source == .manual)
        #expect(active.startedAt == now)
        // The one thing permission would have added, and its absence is not a failure.
        #expect(active.location == nil)
    }

    @Test("A parking with nothing but a timestamp is still a valid parking")
    func savesEmptyDraft() async throws {
        // Arrange — the user taps 직접 저장 and saves without typing anything.
        let model = try makeModel()

        // Act
        let saved = await model.saveManualParking(ManualParkingDraft())

        // Assert
        #expect(saved)
        model.refresh()
        let active = try #require(model.activeSession)
        #expect(active.floor == nil)
        #expect(active.zone == nil)
        #expect(active.startedAt == now)
    }

    @Test("When a location is available it is attached, and it is still local-only")
    func attachesLocationWhenAvailable() async throws {
        // Arrange
        let fix = ParkedLocation(
            latitude: 37.123_456_7,
            longitude: 127.987_654_3,
            horizontalAccuracy: 18,
            capturedAt: now.addingTimeInterval(-60)
        )
        let model = try makeModel(locationProvider: StubParkingLocationProvider(location: fix))

        // Act
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))

        // Assert
        model.refresh()
        #expect(model.activeSession?.location == fix)
    }

    @Test("A denied second save is reported rather than silently ending the first parking")
    func reportsActiveConflict() async throws {
        // Arrange — FR-004's explicit conflict policy.
        let model = try makeModel()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))
        let firstID = model.activeSession?.id

        // Act
        let saved = await model.saveManualParking(ManualParkingDraft(floorText: "B1"))

        // Assert
        #expect(!saved)
        #expect(model.failure != nil)
        #expect(model.activeSession?.id == firstID)
        #expect(model.activeSession?.floor?.displayText == "B3")
    }

    @Test("Ending the parking moves it into the history the home preview reads")
    func endingMovesRecordIntoHistory() async throws {
        // Arrange
        let clock = MutableDateProvider(now)
        let model = try makeModel(clock: clock)
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))
        clock.advance(by: 3600)

        // Act
        let ended = model.endActiveParking()

        // Assert
        #expect(ended)
        #expect(model.activeSession == nil)
        #expect(model.completedSessions.count == 1)
        #expect(model.completedSessions.first?.endedAt == clock.now)
        #expect(model.homePreviewSessions.count == 1)
    }

    @Test("The home preview is capped at three rows while history keeps everything")
    func capsHomePreviewOnly() async throws {
        // Arrange
        let clock = MutableDateProvider(now)
        let model = try makeModel(clock: clock)
        for index in 0 ..< 5 {
            _ = await model.saveManualParking(ManualParkingDraft(floorText: "B\(index + 1)"))
            clock.advance(by: 600)
            _ = model.endActiveParking()
            clock.advance(by: 600)
        }

        // Act
        model.refresh()

        // Assert — FR-009's free limit is not applied; only the preview is trimmed.
        #expect(model.completedSessions.count == 5)
        #expect(model.homePreviewSessions.count == ParkingModel.homePreviewLimit)
        #expect(model.homePreviewSessions.first?.floor?.displayText == "B5")
    }

    // ── The `-` / `+` keys ──────────────────────────────────────────────────

    @Test("Stepping the active floor persists, so a relaunch sees the new floor")
    func stepsActiveFloor() async throws {
        // Arrange
        let model = try makeModel()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))

        // Act
        let stepped = model.stepActiveFloor(by: 1)

        // Assert
        #expect(stepped)
        #expect(model.activeSession?.floor?.displayText == "B2")
        // Re-read from the store rather than trusting the cached copy.
        model.refresh()
        #expect(model.activeSession?.floor?.displayText == "B2")
    }

    @Test("A free-text floor refuses to step — FR-005 allows it only on numeric floors")
    func refusesToStepFreeTextFloor() async throws {
        // Arrange
        let model = try makeModel()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "옥상"))

        // Act / Assert
        #expect(!model.stepActiveFloor(by: 1))
        #expect(model.activeSession?.floor?.raw == "옥상")
    }

    @Test("Stepping with no parking at all is refused rather than crashing")
    func refusesToStepWithoutActiveParking() throws {
        // Arrange
        let model = try makeModel()

        // Act / Assert
        #expect(!model.stepActiveFloor(by: 1))
        #expect(!model.endActiveParking())
    }

    @Test("Deleting all local data clears the active parking and the history (docs/02 §15)")
    func deletesAllLocalData() async throws {
        // Arrange
        let clock = MutableDateProvider(now)
        let model = try makeModel(clock: clock)
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))
        clock.advance(by: 600)
        _ = model.endActiveParking()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B1"))

        // Act
        let deleted = await model.deleteAllLocalData()

        // Assert
        #expect(deleted)
        #expect(model.activeSession == nil)
        #expect(model.completedSessions.isEmpty)
    }

    @Test("An empty draft is recognised as empty, whitespace and all")
    func detectsEmptyDraft() {
        #expect(ManualParkingDraft().isEmpty)
        #expect(ManualParkingDraft(floorText: "  ", zone: "\n").isEmpty)
        #expect(!ManualParkingDraft(zone: "A구역").isEmpty)
    }
}

/// Hands back a fixed location, standing in for a granted-permission device.
@MainActor
private struct StubParkingLocationProvider: ParkingLocationProviding {
    let location: ParkedLocation?

    func currentParkedLocation() async -> ParkedLocation? {
        location
    }
}

/// The stale-fix rule on the real provider: an old location is not where the car is.
@MainActor
struct ParkingLocationProviderTests {
    @Test("The permission-less provider returns nothing, which is a normal answer")
    func unavailableProviderReturnsNil() async {
        #expect(await UnavailableParkingLocationProvider().currentParkedLocation() == nil)
    }

    @Test("The freshness window is the one the detection stack's capture cadence implies")
    func declaresFreshnessWindow() {
        // A fix older than this is wherever the phone was, not where the car is
        // (FR-008 forbids presenting that as the parking spot).
        #expect(DetectionParkingLocationProvider.maximumAge == 15 * 60)
    }
}

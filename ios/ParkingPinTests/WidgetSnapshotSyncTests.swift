import Foundation
import Testing
@testable import ParkingPin

/// **docs/06 §6 / §8 and docs/04_IOS §13 — the app's half of the widget contract.**
///
/// SwiftData stays canonical; the App Group file is its projection. These prove the two
/// cannot drift: every write republishes, a completion clears, and a floor the widget
/// stepped while the app was away is adopted on activation rather than overwritten.
@MainActor
struct WidgetSnapshotSyncTests {
    private static let start = TestTime.reference

    /// The store, the projection and the clock, wired as the app wires them.
    private struct Harness {
        let model: ParkingModel
        let snapshots: FileActiveParkingSnapshotStore
        let store: any ParkingStoring
        let clock: MutableDateProvider
        /// Held so the scratch directory outlives the test body.
        let directory: TemporaryWidgetDirectory
    }

    private func makeHarness() throws -> Harness {
        let directory = TemporaryWidgetDirectory()
        let snapshots = FileActiveParkingSnapshotStore(directory: directory.url)
        let clock = MutableDateProvider(Self.start)
        let store = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: clock
        )
        return Harness(
            model: ParkingModel(store: store, clock: clock, snapshots: snapshots),
            snapshots: snapshots,
            store: store,
            clock: clock,
            directory: directory
        )
    }

    private func draft(floor: String = "B3") -> ManualParkingDraft {
        ManualParkingDraft(floorText: floor, zone: "A구역", spot: "142")
    }

    // ── Publishing ──────────────────────────────────────────────────────────

    @Test("Saving a parking projects it into the App Group")
    func savePublishesProjection() async throws {
        // Arrange
        let harness = try makeHarness()

        // Act
        await harness.model.saveManualParking(draft())

        // Assert
        let snapshot = try #require(harness.snapshots.read())
        let session = try #require(harness.model.activeSession)
        #expect(snapshot.sessionId == session.id)
        #expect(snapshot.floorValue?.displayText == "B3")
        #expect(snapshot.placeText == "A구역 · 142")
        #expect(snapshot.startedAt == Self.start)
    }

    @Test("The app's own stepper moves the projection too")
    func appStepRepublishes() async throws {
        // Arrange
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())

        // Act — the `−` key on home.
        harness.clock.advance(by: 60)
        #expect(harness.model.stepActiveFloor(by: -1))

        // Assert
        let snapshot = try #require(harness.snapshots.read())
        #expect(snapshot.floorValue?.displayText == "B4")
        #expect(snapshot.revision == 2)
    }

    @Test("Ending a parking clears the projection")
    func endClearsProjection() async throws {
        // Arrange — docs/06 §8 steps 4–5: clear active, refresh widget.
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())

        // Act
        harness.clock.advance(by: 3600)
        #expect(harness.model.endActiveParking())

        // Assert
        #expect(harness.snapshots.read() == nil)
        #expect(harness.model.activeSession == nil)
    }

    @Test("Deleting the active record clears the projection")
    func deleteClearsProjection() async throws {
        // Arrange
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())
        let id = try #require(harness.model.activeSession?.id)

        // Act
        #expect(await harness.model.delete(id: id))

        // Assert
        #expect(harness.snapshots.read() == nil)
    }

    @Test("A model with no App Group container saves and reads exactly as before")
    func worksWithoutContainer() async throws {
        // Arrange — CLAUDE.md: a missing capability is not an app-wide failure. This is
        // the path `ParkingComposition` takes when `containerURL` returns nil.
        let store = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: FixedDateProvider(Self.start)
        )
        let model = ParkingModel(store: store, clock: FixedDateProvider(Self.start), snapshots: nil)

        // Act
        let saved = await model.saveManualParking(draft())

        // Assert
        #expect(saved)
        #expect(model.failure == nil)
        #expect(model.activeSession?.floor?.displayText == "B3")
    }

    // ── docs/06 §8: startup repair ──────────────────────────────────────────

    @Test("A projection naming a record that is already in history is cleared on launch")
    func startupRepairClearsStaleProjection() async throws {
        // Arrange — docs/06 §8: "if completed record exists with same active sessionId ->
        // clear stale active projection." Reproduced the way it actually happens: the
        // completion commits and the process dies before the projection is cleared, which
        // is a model that never got to write it.
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())
        let sessionId = try #require(harness.model.activeSession?.id)
        let unwired = ParkingModel(store: harness.store, clock: harness.clock, snapshots: nil)
        unwired.refresh()
        harness.clock.advance(by: 3600)
        #expect(unwired.endActiveParking())
        // The stale projection survived the completion, as it would across a crash.
        #expect(try #require(harness.snapshots.read()).sessionId == sessionId)

        // Act — next launch.
        let relaunched = ParkingModel(
            store: harness.store,
            clock: harness.clock,
            snapshots: harness.snapshots
        )
        relaunched.refresh()

        // Assert
        #expect(harness.snapshots.read() == nil)
        #expect(relaunched.activeSession == nil)
        #expect(relaunched.completedSessions.map(\.id) == [sessionId])
    }

    // ── docs/04_IOS §13: reconciliation on activation ────────────────────────

    @Test("A floor the widget stepped while the app was away is adopted on activation")
    func activationAdoptsWidgetStep() async throws {
        // Arrange
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())
        let sessionId = try #require(harness.model.activeSession?.id)

        // Act — the widget's intent, in the other process.
        harness.clock.advance(by: 60)
        #expect(try harness.snapshots.step(by: 1, expecting: sessionId, at: harness.clock.now) == .stepped(
            #require(FloorValue.parse("B2"))
        ))
        harness.clock.advance(by: 60)
        harness.model.refresh()

        // Assert — in memory, and in the canonical store behind it.
        #expect(harness.model.activeSession?.floor?.displayText == "B2")
        #expect(try harness.store.activeSession()?.floor?.displayText == "B2")
    }

    @Test("Two widget taps are adopted as two floors, not one")
    func activationAdoptsRepeatedSteps() async throws {
        // Arrange — the delta contract has to survive the trip back into SwiftData too.
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())
        let sessionId = try #require(harness.model.activeSession?.id)

        // Act
        harness.clock.advance(by: 60)
        harness.snapshots.step(by: 1, expecting: sessionId, at: harness.clock.now)
        harness.snapshots.step(by: 1, expecting: sessionId, at: harness.clock.now)
        harness.model.refresh()

        // Assert — B3 up two floors.
        #expect(harness.model.activeSession?.floor?.displayText == "B1")
    }

    @Test("A projection older than the record never reverts an edit made in the app")
    func activationKeepsNewerRecord() async throws {
        // Arrange — the case where a publish failed after the store had already moved on.
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())
        var session = try #require(harness.model.activeSession)
        harness.clock.advance(by: 600)
        session.floor = FloorValue.parse("5F")
        #expect(harness.model.update(session))

        // Act — a projection stamped before that edit turns up in the container.
        harness.snapshots.publish(
            sessionId: session.id,
            startedAt: session.startedAt,
            floor: FloorValue.parse("B3"),
            zone: session.zone,
            spot: session.spot,
            at: Self.start
        )
        harness.model.refresh()

        // Assert — the app's edit stands, and the projection is put back in step with it.
        #expect(harness.model.activeSession?.floor?.displayText == "5F")
        #expect(harness.snapshots.read()?.floorValue?.displayText == "5F")
    }

    @Test("A projection for some other parking is ignored, never merged into this one")
    func activationIgnoresForeignProjection() async throws {
        // Arrange — the same drop rule as the stepper's, on the app's side of the line.
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())
        let session = try #require(harness.model.activeSession)

        // Act
        harness.clock.advance(by: 600)
        harness.snapshots.publish(
            sessionId: UUID(),
            startedAt: session.startedAt,
            floor: FloorValue.parse("7F"),
            zone: nil,
            spot: nil,
            at: harness.clock.now
        )
        harness.model.refresh()

        // Assert — the floor is untouched and the projection is rewritten from the store.
        #expect(harness.model.activeSession?.floor?.displayText == "B3")
        let snapshot = try #require(harness.snapshots.read())
        #expect(snapshot.sessionId == session.id)
        #expect(snapshot.floorValue?.displayText == "B3")
    }

    @Test("A refresh with nothing new leaves the revision where it was")
    func refreshIsNotAMutation() async throws {
        // Arrange — `RootView` refreshes on every activation; docs/06 §5 counts mutations.
        let harness = try makeHarness()
        await harness.model.saveManualParking(draft())
        let revision = try #require(harness.snapshots.read()).revision

        // Act
        harness.clock.advance(by: 600)
        harness.model.refresh()
        harness.model.refresh()

        // Assert
        #expect(try #require(harness.snapshots.read()).revision == revision)
    }
}

import Foundation
import Testing
@testable import ParkingKok

@Suite("Background rehydration")
struct BackgroundCoordinatorTests {
    private func makeCoordinator(
        store: StubCheckpointStore,
        motion: StubMotionHistoryProvider,
        now: Date = TestTime.offset(0)
    ) -> BackgroundCoordinator {
        BackgroundCoordinator(
            checkpointStore: store,
            motionHistory: motion,
            dateProvider: FixedDateProvider(now)
        )
    }

    @Test("A fresh install seeds an IDLE checkpoint so process death has something to restore")
    func seedsCheckpointOnFirstLaunch() async {
        // Arrange
        let store = StubCheckpointStore(loadResult: .absent)
        let coordinator = makeCoordinator(store: store, motion: StubMotionHistoryProvider())

        // Act
        await coordinator.rehydrate(launchReason: .userInitiated)

        // Assert
        #expect(store.savedCheckpoints.count == 1)
        #expect(store.savedCheckpoints.first?.state == .idle)
        #expect(store.savedCheckpoints.first?.stateEnteredAt == TestTime.offset(0))
    }

    @Test("A location relaunch restores the checkpoint and replays motion from its anchor")
    func restoresAndReplaysFromCheckpoint() async {
        // Arrange
        let anchor = TestTime.offset(-300)
        let checkpoint = DetectionCheckpoint(
            state: .driving,
            stateEnteredAt: TestTime.offset(-600),
            lastAutomotiveAt: anchor,
            revision: 3
        )
        let store = StubCheckpointStore(loadResult: .restored(checkpoint))
        let motion = StubMotionHistoryProvider(result: .success([
            MotionSample(timestamp: TestTime.offset(-200), automotive: true, stationary: true, confidence: .high),
            MotionSample(timestamp: TestTime.offset(-100), walking: true, confidence: .medium)
        ]))
        let coordinator = makeCoordinator(store: store, motion: motion)

        // Act
        await coordinator.rehydrate(launchReason: .significantLocationChange)
        let snapshot = await coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.launchReason == .significantLocationChange)
        #expect(snapshot.checkpointLoad == .restored(checkpoint))
        #expect(snapshot.checkpointAge == 300)
        #expect(motion.requestedWindow?.start == anchor)
        #expect(motion.requestedWindow?.end == TestTime.offset(0))
        #expect(snapshot.motionSamples.count == 2)
        // The restored checkpoint is not rewritten, so the revision cannot drift.
        #expect(store.savedCheckpoints.isEmpty)
    }

    @Test("A corrupt checkpoint is reported, not swallowed, and the app keeps going")
    func surfacesCorruptCheckpoint() async {
        // Arrange
        let store = StubCheckpointStore(loadResult: .failed(.corrupt("NSCocoaErrorDomain(3840)")))
        let motion = StubMotionHistoryProvider()
        let coordinator = makeCoordinator(store: store, motion: motion)

        // Act
        await coordinator.rehydrate(launchReason: .significantLocationChange)
        let snapshot = await coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.isCheckpointFailed)
        #expect(snapshot.checkpointDescription.contains("corrupt"))
        // Motion replay still runs, over the full lookback, because there is no anchor.
        #expect(snapshot.motionWindow?.duration == MotionHistoryWindowPolicy.maximumLookback)
        // A failed load is not an absent one: do not silently overwrite with a seed.
        #expect(store.savedCheckpoints.isEmpty)
    }

    @Test("A denied motion permission degrades to a reason instead of failing rehydration")
    func recordsMotionPermissionFailure() async {
        // Arrange
        let store = StubCheckpointStore(loadResult: .absent)
        let motion = StubMotionHistoryProvider(result: .failure(.notAuthorized), authorization: .denied)
        let coordinator = makeCoordinator(store: store, motion: motion)

        // Act
        await coordinator.rehydrate(launchReason: .userInitiated)
        let snapshot = await coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.motionSamples.isEmpty)
        #expect(snapshot.motionFailure == MotionHistoryError.notAuthorized.diagnosticDescription)
        // The checkpoint path is unaffected — manual parking keeps working.
        #expect(store.savedCheckpoints.count == 1)
    }

    @Test("A checkpoint older than Core Motion retention is flagged")
    func flagsCheckpointBeyondRetention() async {
        // Arrange
        let stale = DetectionCheckpoint.initial(at: TestTime.offset(-9 * 24 * 60 * 60))
        let store = StubCheckpointStore(loadResult: .restored(stale))
        let coordinator = makeCoordinator(store: store, motion: StubMotionHistoryProvider())

        // Act
        await coordinator.rehydrate(launchReason: .userInitiated)
        let snapshot = await coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.isBeyondMotionRetention)
        #expect(snapshot.motionWindow?.duration == MotionHistoryWindowPolicy.maximumLookback)
    }

    @Test("Each significant change bumps the revision and records only accuracy plus time")
    func significantChangeUpdatesCheckpoint() async {
        // Arrange
        let checkpoint = DetectionCheckpoint.initial(at: TestTime.offset(-60))
        let store = StubCheckpointStore(loadResult: .restored(checkpoint))
        let coordinator = makeCoordinator(store: store, motion: StubMotionHistoryProvider())
        await coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act
        await coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: TestTime.offset(-10), horizontalAccuracy: 42)
        )
        await coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: TestTime.offset(-5), horizontalAccuracy: 18)
        )
        let snapshot = await coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.significantChangeCount == 2)
        #expect(snapshot.lastLocationAccuracy == 18)
        #expect(store.savedCheckpoints.map(\.revision) == [1, 2])
        // State is untouched: transitions are M0A-2.
        #expect(store.savedCheckpoints.allSatisfy { $0.state == .idle })
        // And no coordinate was ever available to persist.
        #expect(store.savedCheckpoints.allSatisfy { $0.lastReliableLocation == nil })
    }

    @Test("A failing write is surfaced rather than lost")
    func surfacesPersistFailure() async {
        // Arrange
        let store = StubCheckpointStore(loadResult: .absent, saveError: .writeFailed("NSCocoaErrorDomain(513)"))
        let coordinator = makeCoordinator(store: store, motion: StubMotionHistoryProvider())

        // Act
        await coordinator.rehydrate(launchReason: .userInitiated)
        let snapshot = await coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.lastPersistError != nil)
    }

    @Test("An invalid fix is ignored before it reaches the checkpoint")
    func rejectsInvalidAccuracy() {
        // Arrange / Act
        let invalid = LocationQualitySample(timestamp: TestTime.offset(0), horizontalAccuracy: -1)
        let valid = LocationQualitySample(timestamp: TestTime.offset(0), horizontalAccuracy: 12)

        // Assert — docs/05_PARKING_DETECTION_ENGINE.md §5.
        #expect(!invalid.isValid)
        #expect(valid.isValid)
    }

    /// Regression: the permission path used to query on the side with `try?`, so a
    /// refusal vanished and the screen showed nothing while the button looked broken.
    @Test("Requesting motion access records the refusal instead of discarding it")
    func motionRefusalReachesTheSnapshot() async {
        // Arrange
        let motion = StubMotionHistoryProvider(
            result: .failure(.notAuthorized),
            authorization: .denied
        )
        let coordinator = makeCoordinator(store: StubCheckpointStore(), motion: motion)

        // Act
        await coordinator.requestMotionHistoryAccess()

        // Assert
        let snapshot = await coordinator.currentSnapshot()
        #expect(snapshot.motionFailure == MotionHistoryError.notAuthorized.diagnosticDescription)
        #expect(snapshot.motionSamples.isEmpty)
    }

    /// The window must stay open. Anchoring on a just-seeded checkpoint collapses it to
    /// zero, `samples(in:)` returns early without touching Core Motion, and the prompt
    /// never appears — the same deadlock by a different route.
    @Test("Requesting motion access queries a non-empty window")
    func motionRequestUsesOpenWindow() async throws {
        // Arrange
        let motion = StubMotionHistoryProvider(authorization: .notDetermined)
        let coordinator = makeCoordinator(store: StubCheckpointStore(), motion: motion)

        // Act
        await coordinator.requestMotionHistoryAccess()

        // Assert
        let window = try #require(motion.requestedWindow)
        #expect(window.start < window.end)
    }
}

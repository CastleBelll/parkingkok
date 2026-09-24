import Foundation
import Testing
@testable import ParkingPin

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
        // This fixture is "drove, then walked", which docs/05 §3a reads all the way to a
        // candidate: the restored session had been driving for 600 s, past the 90 s bar,
        // and the walk is the confirming signal. docs/05 §14 makes each step a checkpoint
        // write. What must still hold is that nothing re-seeds: every write builds on the
        // restored revision rather than resetting it.
        #expect(!store.savedCheckpoints.isEmpty)
        #expect(store.savedCheckpoints.allSatisfy { $0.revision > checkpoint.revision })
        #expect(store.savedCheckpoints.last?.state == .candidatePending)
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

    /// Regression, straight off the device: Core Location replayed a cached fix from
    /// 08:51 into an app installed at 12:12, and it was persisted as a live arrival.
    /// The fix was perfectly accurate — only old — so `isValid` let it through.
    @Test("A cached fix older than the freshness bound never reaches the checkpoint")
    func rejectsStaleSignificantChange() async {
        // Arrange — 3h20m stale, exactly the gap observed on the iPhone.
        let store = StubCheckpointStore()
        let coordinator = makeCoordinator(
            store: store,
            motion: StubMotionHistoryProvider(),
            now: TestTime.offset(12000)
        )
        let cached = LocationQualitySample(timestamp: TestTime.offset(0), horizontalAccuracy: 8)

        // Act
        await coordinator.handleSignificantChange(cached)

        // Assert
        let snapshot = await coordinator.currentSnapshot()
        #expect(snapshot.significantChangeCount == 0)
        #expect(snapshot.lastLocationAt == nil)
        #expect(snapshot.staleLocationDropCount == 1)
        #expect(snapshot.lastStaleLocationAge == 12000)
        #expect(snapshot.currentCheckpoint?.lastLocationAt == nil)
    }

    @Test("A fresh fix still lands, so the guard does not swallow real movement")
    func acceptsFreshSignificantChange() async {
        // Arrange
        let coordinator = makeCoordinator(
            store: StubCheckpointStore(),
            motion: StubMotionHistoryProvider(),
            now: TestTime.offset(60)
        )
        let fresh = LocationQualitySample(timestamp: TestTime.offset(30), horizontalAccuracy: 8)

        // Act
        await coordinator.handleSignificantChange(fresh)

        // Assert
        let snapshot = await coordinator.currentSnapshot()
        #expect(snapshot.significantChangeCount == 1)
        #expect(snapshot.lastLocationAt == TestTime.offset(30))
        #expect(snapshot.staleLocationDropCount == 0)
        #expect(snapshot.currentCheckpoint?.lastLocationAt == TestTime.offset(30))
    }

    @Test("The freshness bound covers delivery delay but not a cached replay")
    func freshnessBoundary() {
        // Arrange / Act / Assert — docs/05_PARKING_DETECTION_ENGINE.md §5.
        let now = TestTime.offset(1000)
        func sample(age: TimeInterval) -> LocationQualitySample {
            LocationQualitySample(timestamp: now.addingTimeInterval(-age), horizontalAccuracy: 8)
        }
        #expect(LocationFreshnessPolicy.isFresh(sample(age: 0), now: now))
        #expect(LocationFreshnessPolicy.isFresh(sample(age: 299), now: now))
        #expect(LocationFreshnessPolicy.isFresh(sample(age: 300), now: now))
        #expect(!LocationFreshnessPolicy.isFresh(sample(age: 301), now: now))
        // Clock skew a little ahead is ordinary; far ahead is not.
        #expect(LocationFreshnessPolicy.isFresh(sample(age: -4), now: now))
        #expect(!LocationFreshnessPolicy.isFresh(sample(age: -60), now: now))
    }

    /// Regression: the 300s freshness bound rejects an hours-old cached fix but not a
    /// recent one, and Core Location replays those too — the same replay that put
    /// duplicate fixes in the trace. A 200s-old sample arriving after a 10s-old one
    /// clears the bound while still being older than the checkpoint knows.
    @Test("A fresh but superseded fix does not walk lastLocationAt backwards")
    func supersededFixDoesNotRegressCheckpoint() async {
        // Arrange — establish a recent location, then deliver an older-but-fresh one.
        let coordinator = makeCoordinator(
            store: StubCheckpointStore(),
            motion: StubMotionHistoryProvider(),
            now: TestTime.offset(1000)
        )
        let recent = LocationQualitySample(timestamp: TestTime.offset(990), horizontalAccuracy: 8)
        let superseded = LocationQualitySample(timestamp: TestTime.offset(800), horizontalAccuracy: 8)

        // Act
        await coordinator.handleSignificantChange(recent)
        await coordinator.handleSignificantChange(superseded)

        // Assert
        let snapshot = await coordinator.currentSnapshot()
        #expect(snapshot.lastLocationAt == TestTime.offset(990))
        #expect(snapshot.currentCheckpoint?.lastLocationAt == TestTime.offset(990))
        #expect(snapshot.supersededLocationDropCount == 1)
        // The wake itself still happened, and both arrivals are real events.
        #expect(snapshot.significantChangeCount == 2)
        #expect(snapshot.staleLocationDropCount == 0)
    }

    @Test("A newer fresh fix still advances the checkpoint")
    func newerFixAdvancesCheckpoint() async {
        // Arrange
        let coordinator = makeCoordinator(
            store: StubCheckpointStore(),
            motion: StubMotionHistoryProvider(),
            now: TestTime.offset(1000)
        )

        // Act
        await coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: TestTime.offset(800), horizontalAccuracy: 8)
        )
        await coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: TestTime.offset(990), horizontalAccuracy: 12)
        )

        // Assert
        let snapshot = await coordinator.currentSnapshot()
        #expect(snapshot.lastLocationAt == TestTime.offset(990))
        #expect(snapshot.lastLocationAccuracy == 12)
        #expect(snapshot.supersededLocationDropCount == 0)
    }

    @Test("A parking saved by hand moves the engine to PARKED and is checkpointed (docs/05 §11c)")
    func userSavedParkingIsCheckpointed() async {
        // Arrange
        let store = StubCheckpointStore(loadResult: .absent)
        let coordinator = makeCoordinator(store: store, motion: StubMotionHistoryProvider())
        await coordinator.rehydrate(launchReason: .userInitiated)
        let savedAt = TestTime.offset(0)

        // Act
        await coordinator.userSavedParking(at: savedAt)

        // Assert — the checkpoint is what makes the next drive away a departure, across
        // process death included.
        #expect(store.savedCheckpoints.last?.state == .parked)
        #expect(store.savedCheckpoints.last?.stateEnteredAt == savedAt)
    }
}

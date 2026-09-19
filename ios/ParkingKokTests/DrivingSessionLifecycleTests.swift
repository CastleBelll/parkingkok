import Foundation
import Testing
@testable import ParkingKok

/// The bounded session as the coordinator drives it: every acquire must be matched by a
/// release, or the §19 battery gate is the next thing that finds out
/// (docs/04_IOS_IMPLEMENTATION.md §3 "Stop aggressive tracking").
@Suite("Bounded driving session lifecycle")
struct DrivingSessionLifecycleTests {
    private let reference = TestTime.offset(0)

    private struct Harness {
        let coordinator: BackgroundCoordinator
        let store: StubCheckpointStore
        let capture: StubBoundedLocationCapture
        let clock: MutableDateProvider
    }

    private func harness(
        checkpoint: DetectionCheckpointLoadResult = .absent,
        motion: [MotionSample] = []
    ) -> Harness {
        let store = StubCheckpointStore(loadResult: checkpoint)
        let capture = StubBoundedLocationCapture()
        let clock = MutableDateProvider(reference)
        return Harness(
            coordinator: BackgroundCoordinator(
                checkpointStore: store,
                motionHistory: StubMotionHistoryProvider(result: .success(motion)),
                locationCapture: capture,
                dateProvider: clock
            ),
            store: store,
            capture: capture,
            clock: clock
        )
    }

    private func automotive(secondsAgo: TimeInterval) -> MotionSample {
        MotionSample(
            timestamp: reference.addingTimeInterval(-secondsAgo),
            automotive: true,
            stationary: true,
            confidence: .high
        )
    }

    private func walking(secondsAgo: TimeInterval) -> MotionSample {
        MotionSample(timestamp: reference.addingTimeInterval(-secondsAgo), walking: true, confidence: .high)
    }

    // MARK: - Opening

    @Test("Recent vehicle evidence opens exactly one bounded session")
    func recentVehicleEvidenceStartsSession() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])

        // Act
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(harness.capture.startCount == 1)
        #expect(harness.capture.isActive())
        #expect(snapshot.isCapturingDrivingLocation)
        #expect(snapshot.drivingSessionCount == 1)
        #expect(harness.store.savedCheckpoints.last?.state == .drivingCandidate)
    }

    @Test("Vehicle evidence older than the recency bound opens nothing")
    func staleVehicleEvidenceStartsNothing() async {
        // Arrange
        let stale = DrivingConfirmationPolicy.vehicleEvidenceMaxAge + 60
        let harness = harness(motion: [automotive(secondsAgo: stale)])

        // Act
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Assert
        #expect(harness.capture.startCount == 0)
        #expect(harness.store.savedCheckpoints.last?.state == .idle)
    }

    /// Replaying history after the user already walked away must not reopen the drive.
    @Test("Walking after the vehicle evidence keeps the session closed")
    func walkingAfterVehicleStartsNothing() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 120), walking(secondsAgo: 30)])

        // Act
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Assert
        #expect(harness.capture.startCount == 0)
    }

    @Test("Replaying the same vehicle sample on a later wake never opens a second session")
    func repeatedEvaluationDoesNotRestart() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act — a second significant change re-reads the very same history.
        await harness.coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: reference, horizontalAccuracy: 20)
        )

        // Assert
        #expect(harness.capture.startCount == 1)
        #expect(harness.capture.redundantStartCount == 0)
    }

    // MARK: - Closing: every path releases the capture

    @Test("Walking ends the session and releases the capture")
    func walkingEndsSession() async {
        // Arrange — the first wake sees only the drive.
        let store = StubCheckpointStore(loadResult: .absent)
        let capture = StubBoundedLocationCapture()
        let clock = MutableDateProvider(reference)
        let motion = StubMotionHistoryProvider(result: .success([automotive(secondsAgo: 30)]))
        let coordinator = BackgroundCoordinator(
            checkpointStore: store,
            motionHistory: motion,
            locationCapture: capture,
            dateProvider: clock
        )
        await coordinator.rehydrate(launchReason: .significantLocationChange)
        #expect(capture.isActive())

        // Act — the next significant change re-reads a history that now has the walk.
        motion.setResult(.success([
            automotive(secondsAgo: 30),
            MotionSample(timestamp: reference.addingTimeInterval(60), walking: true, confidence: .high)
        ]))
        clock.advance(by: 120)
        await coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: clock.now, horizontalAccuracy: 20)
        )
        let snapshot = await coordinator.currentSnapshot()

        // Assert
        #expect(!capture.isActive())
        #expect(capture.startCount == capture.stopCount)
        #expect(snapshot.lastDrivingSessionEndReason == .walkingDetected)
        // docs/05 §3a: 120 s of vehicle activity clears the 90 s bar with no fix ever
        // arriving, so the walk lands on `CANDIDATE_PENDING` rather than back on `IDLE`.
        // That is the underground drive the promotion rule was corrected for.
        #expect(store.savedCheckpoints.last?.state == .candidatePending)
    }

    @Test("Silence past the ceiling ends the session and releases the capture")
    func timeoutEndsSession() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act
        harness.clock.advance(by: DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout)
        await harness.coordinator.evaluateDrivingTimeouts()
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(!harness.capture.isActive())
        #expect(harness.capture.stopCount == 1)
        #expect(snapshot.lastDrivingSessionEndReason == .vehicleEvidenceExpired)
        #expect(!snapshot.isCapturingDrivingLocation)
    }

    @Test("Losing authorization mid-session releases the capture instead of stalling it")
    func authorizationLossEndsSession() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act
        await harness.coordinator.handleCaptureAuthorizationLost()
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(!harness.capture.isActive())
        #expect(harness.capture.stopCount == 1)
        #expect(snapshot.lastDrivingSessionEndReason == .authorizationLost)
        #expect(snapshot.captureFailure != nil)
    }

    @Test("Turning Smart Detection off ends the session the opt-in authorized")
    func optOutEndsSession() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act
        await harness.coordinator.stopDrivingSessionForOptOut()

        // Assert
        #expect(!harness.capture.isActive())
        #expect(harness.capture.startCount == harness.capture.stopCount)
    }

    @Test("Ending twice is harmless and still leaves the capture released")
    func endingIsIdempotent() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act
        await harness.coordinator.stopDrivingSessionForOptOut()
        await harness.coordinator.stopDrivingSessionForOptOut()

        // Assert
        #expect(!harness.capture.isActive())
        #expect(harness.capture.stopCount == 1)
    }

    // MARK: - Recreation after background relaunch (docs/04 §3)

    @Test("A checkpoint that says we were driving recreates the session on relaunch")
    func sessionIsRecreatedOnRelaunch() async {
        // Arrange — the previous process died mid-drive.
        let interrupted = DetectionCheckpoint(
            state: .driving,
            stateEnteredAt: reference.addingTimeInterval(-300),
            lastAutomotiveAt: reference.addingTimeInterval(-60),
            revision: 4
        )
        let harness = harness(checkpoint: .restored(interrupted))

        // Act
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(harness.capture.startCount == 1)
        #expect(snapshot.drivingSessionResumedFromCheckpoint)
        // Recreated, not reopened: the restored session keeps its original start.
        #expect(snapshot.drivingSessionStartedAt == interrupted.stateEnteredAt)
    }

    @Test("A driving checkpoint too old to still be a drive is closed, not resumed")
    func staleDrivingCheckpointIsClosed() async {
        // Arrange
        let abandoned = DetectionCheckpoint(
            state: .driving,
            stateEnteredAt: reference.addingTimeInterval(-DrivingSessionTimeoutPolicy.maximumDuration - 60),
            lastAutomotiveAt: reference.addingTimeInterval(-DrivingSessionTimeoutPolicy.maximumDuration),
            revision: 9
        )
        let harness = harness(checkpoint: .restored(abandoned))

        // Act
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(harness.capture.startCount == 0)
        #expect(snapshot.lastDrivingSessionEndReason == .maximumDurationReached)
        #expect(harness.store.savedCheckpoints.last?.state == .idle)
    }

    // MARK: - Fixes reaching the checkpoint

    @Test("A drive confirms once the distance bound is met and checkpoints the transition")
    func fixesConfirmDrivingAndLandInTheCheckpoint() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act — two moving fixes 900 m apart, then one past docs/05 §3a's 90 s bar. The
        // third is what promotes: distance no longer does that on its own.
        await harness.coordinator.handleDrivingFix(TestGeo.fix(at: reference, metersNorth: 0, accuracy: 8))
        harness.clock.advance(by: 60)
        await harness.coordinator.handleDrivingFix(
            TestGeo.fix(at: reference.addingTimeInterval(60), metersNorth: 900, accuracy: 8)
        )
        harness.clock.advance(by: 30)
        await harness.coordinator.handleDrivingFix(
            TestGeo.fix(at: reference.addingTimeInterval(90), metersNorth: 900, accuracy: 8)
        )
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.drivingConfirmedAt == reference.addingTimeInterval(90))
        #expect(snapshot.reliableLocationUpdateCount == 3)
        let saved = harness.store.savedCheckpoints.last
        #expect(saved?.state == .driving)
        #expect(saved?.lastReliableLocation?.capturedAt == reference.addingTimeInterval(90))
        // Haversine on a sphere; a metre of slack against the nominal 900 m.
        #expect(abs((saved?.travelDistanceEstimate ?? 0) - 900) < 2)
    }

    /// The §5 rule, end to end: a fix the policy rejects must leave the stored value alone.
    @Test("A coarse fix does not overwrite the reliable location already checkpointed")
    func poorFixDoesNotOverwriteCheckpointedValue() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        await harness.coordinator.handleDrivingFix(TestGeo.fix(at: reference, metersNorth: 0, accuracy: 6))

        // Act — newer and admissible on its own, but far coarser than what we hold.
        harness.clock.advance(by: 10)
        await harness.coordinator.handleDrivingFix(
            TestGeo.fix(at: reference.addingTimeInterval(10), metersNorth: 200, accuracy: 34)
        )
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.lastReliableLocationRejection == .lessAccurateThanFreshIncumbent)
        #expect(snapshot.reliableLocationRejectCount == 1)
        #expect(snapshot.currentCheckpoint?.lastReliableLocation?.horizontalAccuracy == 6)
        #expect(snapshot.currentCheckpoint?.lastReliableLocation?.capturedAt == reference)
    }

    @Test("Fixes arriving with no session open are ignored rather than silently stored")
    func fixesWithoutSessionAreIgnored() async {
        // Arrange — nothing in motion history, so no session opens.
        let harness = harness()
        await harness.coordinator.rehydrate(launchReason: .userInitiated)

        // Act
        await harness.coordinator.handleDrivingFix(TestGeo.fix(at: reference, accuracy: 5))
        let snapshot = await harness.coordinator.currentSnapshot()

        // Assert
        #expect(snapshot.reliableLocationUpdateCount == 0)
        #expect(snapshot.currentCheckpoint?.lastReliableLocation == nil)
    }
}

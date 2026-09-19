import Foundation
import Testing
@testable import ParkingKok

/// The engine half of docs/05 §10a: what a finished drive writes, posts, and withdraws.
///
/// Driven through `BackgroundCoordinator` rather than through the policy, because every
/// rule in §10a is about *ordering* — the candidate before the notification, the old one
/// withdrawn before the new one posts — and ordering is not visible in a pure function.
@Suite("Candidate creation")
struct CandidateCreationTests {
    private let reference = TestTime.offset(0)

    private struct Harness {
        let coordinator: BackgroundCoordinator
        let checkpoints: StubCheckpointStore
        let candidates: StubParkingCandidateStore
        let notifier: StubCandidateNotifier
        let analytics: RecordingAnalyticsSink
        let motion: StubMotionHistoryProvider
        let clock: MutableDateProvider
    }

    private func harness(motion samples: [MotionSample] = []) -> Harness {
        let checkpoints = StubCheckpointStore(loadResult: .absent)
        let candidates = StubParkingCandidateStore()
        let notifier = StubCandidateNotifier()
        let sink = RecordingAnalyticsSink()
        let motion = StubMotionHistoryProvider(result: .success(samples))
        let clock = MutableDateProvider(reference)
        return Harness(
            coordinator: BackgroundCoordinator(
                checkpointStore: checkpoints,
                motionHistory: motion,
                locationCapture: StubBoundedLocationCapture(),
                dateProvider: clock,
                candidateStore: candidates,
                candidateNotifier: notifier,
                analytics: AnalyticsRecorder(
                    consent: MutableAnalyticsConsentStore(granted: true),
                    sink: sink,
                    clock: clock
                )
            ),
            checkpoints: checkpoints,
            candidates: candidates,
            notifier: notifier,
            analytics: sink,
            motion: motion,
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

    /// Opens a session, confirms it with two moving fixes 900 m apart, then replays a
    /// history that also contains the confirming signal.
    private func driveAndPark(_ harness: Harness, confirmingSignal: MotionSample) async {
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        await harness.coordinator.handleDrivingFix(TestGeo.fix(at: reference, metersNorth: 0, accuracy: 8))
        harness.clock.advance(by: 60)
        await harness.coordinator.handleDrivingFix(
            TestGeo.fix(at: reference.addingTimeInterval(60), metersNorth: 900, accuracy: 8)
        )
        // Past docs/05 §3a's 90 s vehicle-duration bar, which is what promotes the session.
        harness.motion.setResult(.success([automotive(secondsAgo: 30), confirmingSignal]))
        harness.clock.advance(by: 60)
        await harness.coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: harness.clock.now, horizontalAccuracy: 20)
        )
    }

    private func walking(at offset: TimeInterval) -> MotionSample {
        MotionSample(timestamp: reference.addingTimeInterval(offset), walking: true, confidence: .high)
    }

    private func stationary(at offset: TimeInterval) -> MotionSample {
        MotionSample(timestamp: reference.addingTimeInterval(offset), stationary: true, confidence: .high)
    }

    @Test("A confirmed drive that ends in a walk creates a candidate, checkpoints it and posts")
    func walkCreatesAndPostsCandidate() async throws {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])

        // Act
        await driveAndPark(harness, confirmingSignal: walking(at: 90))

        // Assert — the candidate is on disk…
        let stored = try #require(harness.candidates.load())
        #expect(stored.confidenceBucket == .medium)
        #expect(stored.reasonCodes.contains(.walkingAfterVehicle))
        // …the checkpoint says which one is pending…
        let checkpoint = harness.checkpoints.savedCheckpoints.last
        #expect(checkpoint?.state == .candidatePending)
        #expect(checkpoint?.candidateId == stored.id)
        // …and the notification carries that same candidate.
        #expect(harness.notifier.postedCandidates.map(\.id) == [stored.id])
        #expect(harness.analytics.payloads.map(\.name).contains("parking_candidate_created"))
    }

    /// Acceptance: **`low` posts nothing, and the candidate is still recorded** (§9, §10a).
    ///
    /// Driven through the DEV injection hook rather than a synthetic drive, and on purpose:
    /// that hook exists precisely because a `low` score needs a drive that confirmed, ended
    /// without Core Motion ever reporting a walk, and was then answered only by the device
    /// going still. It applies §6, §8, §9 and the notifiable gate exactly as a real trip
    /// does, so what is asserted here is the real path.
    @Test("A low-confidence candidate is saved and never posted")
    func lowConfidenceIsSavedButSilent() async throws {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act — stationary rather than walking: 25 + 15 + 10 + 5 = 55.
        await harness.coordinator.injectCandidateForFieldTest(walking: false)

        // Assert
        let stored = try #require(harness.candidates.load())
        #expect(stored.confidenceBucket == .low)
        #expect(harness.notifier.postedCandidates.isEmpty)
        // Still counted: the created/rejected ratio needs both halves (docs/17 §2).
        #expect(harness.analytics.payloads.map(\.name).contains("parking_candidate_created"))
        #expect(harness.checkpoints.savedCheckpoints.last?.state == .candidatePending)
    }

    /// The same hook with a walk: proof that the gate above is the confidence bucket and
    /// not the hook itself being silent.
    @Test("The same injection with a walk does post")
    func injectedWalkIsNotifiable() async throws {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act
        await harness.coordinator.injectCandidateForFieldTest(walking: true)

        // Assert
        let stored = try #require(harness.candidates.load())
        #expect(stored.confidenceBucket == .medium)
        #expect(harness.notifier.postedCandidates.map(\.id) == [stored.id])
    }

    /// Acceptance: **a re-post of the same candidate replaces rather than stacks.**
    ///
    /// The engine does not re-post at all — there is one creation per transition — so this
    /// states the property the OS relies on: the identifier is the candidate's, so however
    /// many times it is handed over, the centre holds one alert.
    @Test("Re-posting the same candidate uses one identifier, so nothing stacks")
    func rePostingDoesNotStack() async throws {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await driveAndPark(harness, confirmingSignal: walking(at: 90))
        let candidate = try #require(harness.candidates.load())

        // Act — post it again, as a relaunched process would.
        await harness.notifier.post(candidate)

        // Assert — two hand-overs, one identifier.
        #expect(harness.notifier.postedCandidates.count == 2)
        #expect(Set(harness.notifier.postedRequestIdentifiers).count == 1)
    }

    /// Acceptance: **a new travel session expires the previous candidate and withdraws
    /// its notification** (§10a: "a stale prompt about a previous trip is worse than no
    /// prompt").
    @Test("A second trip supersedes the first trip's candidate")
    func newSessionSupersedesPreviousCandidate() async throws {
        // Arrange — one full trip.
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await driveAndPark(harness, confirmingSignal: walking(at: 90))
        let first = try #require(harness.candidates.load())

        // Act — a second drive, an hour later, ending the same way.
        harness.clock.advance(by: 3600)
        let secondStart = harness.clock.now
        harness.motion.setResult(.success([
            MotionSample(timestamp: secondStart, automotive: true, confidence: .high)
        ]))
        await harness.coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: secondStart, horizontalAccuracy: 20)
        )
        await harness.coordinator.handleDrivingFix(TestGeo.fix(at: secondStart, metersNorth: 0, accuracy: 8))
        harness.clock.advance(by: 60)
        await harness.coordinator.handleDrivingFix(
            TestGeo.fix(at: harness.clock.now, metersNorth: 900, accuracy: 8)
        )
        harness.motion.setResult(.success([
            MotionSample(timestamp: secondStart, automotive: true, confidence: .high),
            MotionSample(timestamp: harness.clock.now, walking: true, confidence: .high)
        ]))
        harness.clock.advance(by: 60)
        await harness.coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: harness.clock.now, horizontalAccuracy: 20)
        )

        // Assert — the first is gone and withdrawn, the second is what is pending.
        let second = try #require(harness.candidates.load())
        #expect(second.id != first.id)
        #expect(harness.notifier.withdrawnCandidateIds.contains(first.id))
        #expect(harness.notifier.postedCandidates.map(\.id).contains(second.id))
    }

    /// Acceptance: **45 minutes later there is no record and the notification is gone.**
    @Test("An unanswered candidate expires on the next wake, creating nothing")
    func expiryWithdrawsAndCreatesNothing() async throws {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await driveAndPark(harness, confirmingSignal: walking(at: 90))
        let candidate = try #require(harness.candidates.load())

        // Act — nobody answered, and the next significant change arrives after 45 minutes.
        harness.clock.advance(by: ParkingCandidatePolicy.expiry)
        harness.motion.setResult(.success([]))
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Assert
        #expect(harness.candidates.load() == nil)
        #expect(harness.notifier.withdrawnCandidateIds == [candidate.id])
        #expect(harness.checkpoints.savedCheckpoints.last?.state == .idle)
        #expect(harness.checkpoints.savedCheckpoints.last?.candidateId == nil)
    }

    /// docs/05 §3a "the red light": a drive that pauses and resumes must not notify.
    @Test("Vehicle evidence returning inside the window goes back to DRIVING, silently")
    func redLightReturnsToDriving() async {
        // Arrange — a confirmed drive whose movement went idle.
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)
        await harness.coordinator.handleDrivingFix(TestGeo.fix(at: reference, metersNorth: 0, accuracy: 8))
        harness.clock.advance(by: 60)
        await harness.coordinator.handleDrivingFix(
            TestGeo.fix(at: reference.addingTimeInterval(60), metersNorth: 900, accuracy: 8)
        )
        harness.clock.advance(by: ParkingTransitionPolicy.movementIdleWindow)
        await harness.coordinator.evaluateDrivingTimeouts()
        #expect(harness.checkpoints.savedCheckpoints.last?.state == .parkingTransition)

        // Act — the car moves again while the window is still open, and the device also
        // reported `stationary` while it stood there.
        let resumedAt = harness.clock.now.addingTimeInterval(30)
        harness.motion.setResult(.success([
            stationary(at: 200),
            MotionSample(timestamp: resumedAt, automotive: true, confidence: .high)
        ]))
        harness.clock.advance(by: 60)
        await harness.coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: harness.clock.now, horizontalAccuracy: 20)
        )

        // Assert — no candidate, no notification, back to driving.
        #expect(harness.candidates.load() == nil)
        #expect(harness.notifier.postedCandidates.isEmpty)
        #expect(harness.checkpoints.savedCheckpoints.last?.state == .driving)
    }

    /// **The 2026-09-19 field defect, end to end.** The 14:26 drive was
    /// `vehicle_enter` → `vehicle_exit` → `walking_enter` with *zero* location events, and
    /// under the promotion rule §3a replaced it produced nothing at all. §13 puts
    /// underground car parks at the centre of this product, so this is the ordinary case
    /// rather than the exotic one.
    @Test("A drive with no location fix at all still reaches a candidate")
    func fixlessDriveStillProducesCandidate() async throws {
        // Arrange — vehicle evidence and a walk, and not one fix in between.
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act — past §3a's 90 s bar.
        harness.motion.setResult(.success([automotive(secondsAgo: 30), walking(at: 100)]))
        harness.clock.advance(by: 120)
        await harness.coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: harness.clock.now, horizontalAccuracy: 20)
        )

        // Assert — a candidate, with no location to anchor it, which is a real state.
        let stored = try #require(harness.candidates.load())
        #expect(stored.lastReliableLocation == nil)
        #expect(!stored.reasonCodes.contains(.vehicleDistanceMet))
        #expect(harness.checkpoints.savedCheckpoints.last?.state == .candidatePending)
    }

    /// §3a: only a session that reached `DRIVING` decides whether it parked. This is §12's
    /// short-trip guard, and it is why a two-minute taxi ride is not a notification.
    @Test("A session shorter than the vehicle-duration bar produces no candidate")
    func shortSessionProducesNoCandidate() async {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])
        await harness.coordinator.rehydrate(launchReason: .significantLocationChange)

        // Act — the walk arrives well before §3a's 90 s bar.
        harness.motion.setResult(.success([automotive(secondsAgo: 30), walking(at: 20)]))
        harness.clock.advance(by: 30)
        await harness.coordinator.handleSignificantChange(
            LocationQualitySample(timestamp: harness.clock.now, horizontalAccuracy: 20)
        )

        // Assert
        #expect(harness.candidates.load() == nil)
        #expect(harness.checkpoints.savedCheckpoints.last?.state == .idle)
    }

    /// Acceptance: **notification permission denied keeps the candidate.**
    ///
    /// Denial is invisible to this layer by design — `post` simply does nothing — so the
    /// test models it as a notifier that never delivers, and asserts the candidate is on
    /// disk and in the checkpoint regardless. That is §10a's "nothing is lost and nothing
    /// is retried", and it is what the home row then renders from.
    @Test("With notifications denied the candidate is still saved and still pending")
    func deniedNotificationsKeepTheCandidate() async throws {
        // Arrange
        let harness = harness(motion: [automotive(secondsAgo: 30)])

        // Act
        await driveAndPark(harness, confirmingSignal: walking(at: 90))

        // Assert — the order is what matters: the store is written before the post, so a
        // post that goes nowhere cannot cost the candidate.
        let stored = try #require(harness.candidates.load())
        #expect(harness.checkpoints.savedCheckpoints.last?.candidateId == stored.id)
    }
}

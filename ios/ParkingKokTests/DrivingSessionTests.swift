import Foundation
import Testing
@testable import ParkingKok

@Suite("Driving confirmation guard")
struct DrivingConfirmationPolicyTests {
    private let start = TestTime.offset(0)

    /// Builds evidence that satisfies everything except what the caller varies.
    private func evidence(
        vehicleEvidenceAt: Date?,
        fixes: [LocationFix]
    ) -> DrivingEvidence {
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: vehicleEvidenceAt)
        for fix in fixes {
            evidence.record(fix: fix)
        }
        return evidence
    }

    /// Two moving fixes 60 s apart, `metres` apart.
    private func movingFixes(metres: Double) -> [LocationFix] {
        [
            TestGeo.fix(at: start, metersNorth: 0, speed: 15),
            TestGeo.fix(at: start.addingTimeInterval(60), metersNorth: metres, speed: 15)
        ]
    }

    // MARK: - "One event alone never confirms" (docs/05 §7)

    @Test("A single vehicle event with no movement never confirms a drive")
    func singleEventNeverConfirms() {
        // Arrange — vehicle evidence is present and recent, and nothing else is.
        let evidence = evidence(vehicleEvidenceAt: start, fixes: [])

        // Act / Assert — even hours later.
        #expect(!DrivingConfirmationPolicy.isConfirmed(evidence, now: start.addingTimeInterval(3600)))
    }

    @Test("A single moving fix is still one event, so it never confirms")
    func singleMovingFixNeverConfirms() {
        // Arrange
        let evidence = evidence(
            vehicleEvidenceAt: start,
            fixes: [TestGeo.fix(at: start, speed: 20)]
        )

        // Act / Assert
        #expect(evidence.movingSampleCount == 1)
        #expect(!DrivingConfirmationPolicy.isConfirmed(evidence, now: start.addingTimeInterval(3600)))
    }

    // MARK: - duration >= 120 s OR distance >= 800 m

    @Test("The 120 s duration boundary confirms at the bound and not one second before")
    func durationBoundary() {
        // Arrange — short distance, so only duration can carry it.
        let evidence = evidence(vehicleEvidenceAt: start.addingTimeInterval(100), fixes: movingFixes(metres: 50))

        // Act
        let justBefore = start.addingTimeInterval(DrivingConfirmationPolicy.minimumDuration - 1)
        let atBound = start.addingTimeInterval(DrivingConfirmationPolicy.minimumDuration)

        // Assert
        #expect(!DrivingConfirmationPolicy.isConfirmed(evidence, now: justBefore))
        #expect(DrivingConfirmationPolicy.isConfirmed(evidence, now: atBound))
    }

    @Test("The 800 m distance boundary confirms before the duration bound is reached")
    func distanceBoundary() {
        // Arrange — only 60 s in, so duration cannot be what confirms it.
        let short = evidence(vehicleEvidenceAt: start, fixes: movingFixes(metres: 700))
        let long = evidence(vehicleEvidenceAt: start, fixes: movingFixes(metres: 900))
        let now = start.addingTimeInterval(60)

        // Assert
        #expect(short.distanceMeters < DrivingConfirmationPolicy.minimumDistance)
        #expect(long.distanceMeters > DrivingConfirmationPolicy.minimumDistance)
        #expect(!DrivingConfirmationPolicy.isConfirmed(short, now: now))
        #expect(DrivingConfirmationPolicy.isConfirmed(long, now: now))
    }

    // MARK: - "recent vehicle evidence"

    @Test("Vehicle evidence older than the recency bound cannot confirm, whatever else is true")
    func staleVehicleEvidenceBlocksConfirmation() {
        // Arrange — 900 m covered, well past the duration bound, but the car signal is old.
        let evidence = evidence(vehicleEvidenceAt: start, fixes: movingFixes(metres: 900))
        let stale = start.addingTimeInterval(DrivingConfirmationPolicy.vehicleEvidenceMaxAge + 1)
        let fresh = start.addingTimeInterval(DrivingConfirmationPolicy.vehicleEvidenceMaxAge)

        // Assert
        #expect(!DrivingConfirmationPolicy.isConfirmed(evidence, now: stale))
        #expect(DrivingConfirmationPolicy.isConfirmed(evidence, now: fresh))
    }

    @Test("With no vehicle evidence at all, distance and duration are not enough")
    func noVehicleEvidenceNeverConfirms() {
        // Arrange
        let evidence = evidence(vehicleEvidenceAt: nil, fixes: movingFixes(metres: 2000))

        // Act / Assert
        #expect(!DrivingConfirmationPolicy.isConfirmed(evidence, now: start.addingTimeInterval(300)))
    }

    @Test("Fixes below the movement threshold are not movement evidence")
    func walkingSpeedIsNotMovementEvidence() {
        // Arrange — 1 m/s is a walk, not a drive.
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, speed: 1))
        evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(60), metersNorth: 60, speed: 1))

        // Assert
        #expect(evidence.movingSampleCount == 0)
        #expect(!DrivingConfirmationPolicy.isConfirmed(evidence, now: start.addingTimeInterval(300)))
    }
}

@Suite("Driving evidence accumulation")
struct DrivingEvidenceTests {
    private let start = TestTime.offset(0)

    @Test("Distance accumulates across consecutive fixes")
    func distanceAccumulates() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start)

        // Act — three legs of 300 m each.
        for step in 0 ... 3 {
            evidence.record(fix: TestGeo.fix(
                at: start.addingTimeInterval(Double(step) * 30),
                metersNorth: Double(step) * 300
            ))
        }

        // Assert — haversine on a sphere, so allow a metre of slack.
        #expect(abs(evidence.distanceMeters - 900) < 1)
        #expect(evidence.fixCount == 4)
        #expect(evidence.outlierCount == 0)
    }

    /// docs/05 §5: an implausible step must not inflate the distance that half the
    /// confirmation guard rests on.
    @Test("A GPS jump is counted as an outlier and never reaches the distance estimate")
    func outlierIsRejected() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0))

        // Act — 10 km in one second.
        let accepted = evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(1), metersNorth: 10000))

        // Assert
        #expect(!accepted)
        #expect(evidence.outlierCount == 1)
        #expect(evidence.distanceMeters == 0)
    }

    @Test("The fix after a jump is measured from the last good fix, not from the jump")
    func outlierDoesNotBecomeTheAnchor() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0))
        evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(1), metersNorth: 10000))

        // Act — an ordinary 500 m leg from the real position.
        let accepted = evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(60), metersNorth: 500))

        // Assert
        #expect(accepted)
        #expect(abs(evidence.distanceMeters - 500) < 1)
    }

    @Test("An invalid fix is counted, never folded in")
    func invalidFixIsCounted() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start)

        // Act
        let accepted = evidence.record(fix: TestGeo.fix(at: start, accuracy: -1))

        // Assert
        #expect(!accepted)
        #expect(evidence.fixCount == 0)
        #expect(evidence.outlierCount == 1)
    }

    @Test("Confirmation latches, so a confirmed session is checkpointed once")
    func confirmationLatches() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start)

        // Act
        evidence.markConfirmed(at: start.addingTimeInterval(120))
        evidence.markConfirmed(at: start.addingTimeInterval(300))

        // Assert
        #expect(evidence.confirmedAt == start.addingTimeInterval(120))
    }
}

@Suite("Bounded session timeouts")
struct DrivingSessionTimeoutPolicyTests {
    private let start = TestTime.offset(0)

    @Test("A session with no further vehicle evidence expires at the silence ceiling")
    func vehicleEvidenceTimeout() {
        // Arrange
        let evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        let timeout = DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout

        // Act / Assert
        #expect(DrivingSessionTimeoutPolicy.expiryReason(
            for: evidence,
            now: start.addingTimeInterval(timeout - 1)
        ) == nil)
        #expect(DrivingSessionTimeoutPolicy.expiryReason(
            for: evidence,
            now: start.addingTimeInterval(timeout)
        ) == .vehicleEvidenceExpired)
    }

    @Test("Fresh vehicle evidence keeps the session open past the silence ceiling")
    func freshEvidenceDefersTimeout() {
        // Arrange — still driving 30 minutes in.
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.noteVehicleEvidence(at: start.addingTimeInterval(1800))

        // Act / Assert
        #expect(DrivingSessionTimeoutPolicy.expiryReason(
            for: evidence,
            now: start.addingTimeInterval(1860)
        ) == nil)
    }

    /// The ceiling that stops a stuck session from becoming the 24h GPS session
    /// docs/00_CORE_RULES.md forbids.
    @Test("The hard ceiling ends the session even while vehicle evidence keeps arriving")
    func maximumDurationWins() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        let ceiling = DrivingSessionTimeoutPolicy.maximumDuration
        evidence.noteVehicleEvidence(at: start.addingTimeInterval(ceiling))

        // Act / Assert
        #expect(DrivingSessionTimeoutPolicy.expiryReason(
            for: evidence,
            now: start.addingTimeInterval(ceiling)
        ) == .maximumDurationReached)
    }

    @Test("A session opened without any vehicle observation still times out")
    func sessionStartAnchorsTheTimeout() {
        // Arrange
        let evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: nil)

        // Act / Assert
        #expect(DrivingSessionTimeoutPolicy.expiryReason(
            for: evidence,
            now: start.addingTimeInterval(DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout)
        ) == .vehicleEvidenceExpired)
    }
}

/// docs/05 §7 "movement evidence consistent with travel" — the distance fallback that
/// runs when Core Location reports no speed.
@Suite("Movement evidence without a speed")
struct MovementEvidencePolicyTests {
    private let start = TestTime.offset(0)

    /// A clean pair `seconds` apart and `metres` apart, neither carrying a speed.
    private func evidence(seconds: TimeInterval, metres: Double, accuracy: Double = 10) -> DrivingEvidence {
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, accuracy: accuracy, speed: nil))
        evidence.record(fix: TestGeo.fix(
            at: start.addingTimeInterval(seconds),
            metersNorth: metres,
            accuracy: accuracy,
            speed: nil
        ))
        return evidence
    }

    // MARK: - Speed still wins when it is there

    @Test("A fix that carries a speed is scored on speed alone, exactly as before")
    func speedPathIsUnchanged() {
        // Arrange — 60 s and 60 m apart is 1 m/s: a walk. The speed says 20 m/s.
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, speed: 20))
        evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(60), metersNorth: 60, speed: 20))

        // Assert — both fixes counted on speed, and the fallback never ran.
        #expect(evidence.movingSampleCount == 2)
        #expect(evidence.derivedMovingSampleCount == 0)
        #expect(evidence.speedAvailableCount == 2)
        #expect(evidence.speedMissingCount == 0)
    }

    @Test("A speed below the threshold stays a rejection, and distance does not rescue it")
    func slowSpeedIsNotRescuedByDistance() {
        // Arrange — 900 m in 60 s is a drive by distance, but the platform says 1 m/s.
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, speed: 1))
        evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(60), metersNorth: 900, speed: 1))

        // Assert
        #expect(evidence.movingSampleCount == 0)
        #expect(evidence.movementEvidenceRejection == nil)
    }

    // MARK: - The fallback itself

    @Test("Without a speed, a clean 60 s / 900 m leg is movement evidence")
    func distanceFallbackCounts() {
        // Act
        let evidence = evidence(seconds: 60, metres: 900)

        // Assert
        #expect(evidence.movingSampleCount == 1)
        #expect(evidence.derivedMovingSampleCount == 1)
        #expect(evidence.speedMissingCount == 2)
        #expect(evidence.movementEvidenceRejection == nil)
    }

    @Test("A single fix with no speed is still one event and can never be movement evidence")
    func firstFixOnlyAnchors() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)

        // Act
        evidence.record(fix: TestGeo.fix(at: start, speed: nil))

        // Assert
        #expect(evidence.movingSampleCount == 0)
        #expect(evidence.movementEvidenceRejection == nil)
    }

    // MARK: - The accuracy gate

    /// The pair from the 2026-09-16 subway trace, to the metre.
    @Test("A 928 m step between a 521 m and a 47.9 m fix is noise, not travel")
    func coarseFixesAreRejected() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, accuracy: 520.97, speed: nil))

        // Act — 928 m in 60 s, well past the 2 m/s threshold, and still not evidence.
        evidence.record(fix: TestGeo.fix(
            at: start.addingTimeInterval(60),
            metersNorth: 927.85,
            accuracy: 47.86,
            speed: nil
        ))

        // Assert
        #expect(evidence.movingSampleCount == 0)
        #expect(evidence.movementEvidenceRejection == .accuracyTooCoarse)
    }

    @Test("The noise floor is exactly two sigma of the two fixes' combined uncertainty")
    func accuracyGateBoundary() {
        // Arrange — 30 m and 40 m accuracy: sqrt(30² + 40²) = 50 m of displacement
        // uncertainty, so two sigma of it is 100 m. 30 s keeps 2 m/s out of the way.
        let floor = MovementEvidencePolicy.noiseFloorSigmas * 50
        #expect(floor == 100)

        // Act
        let below = pair(metres: floor - 1)
        let atBound = pair(metres: floor)

        // Assert
        #expect(below.derivedMovingSampleCount == 0)
        #expect(below.movementEvidenceRejection == .accuracyTooCoarse)
        #expect(atBound.derivedMovingSampleCount == 1)
    }

    /// Two speedless fixes 30 s apart and `metres` apart, 30 m and 40 m accurate.
    /// Haversine on a sphere, so the northing is nudged to land strictly on the far side
    /// of the bound rather than on top of it.
    private func pair(metres: Double) -> DrivingEvidence {
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, accuracy: 30, speed: nil))
        evidence.record(fix: TestGeo.fix(
            at: start.addingTimeInterval(MovementEvidencePolicy.minimumBaseline),
            metersNorth: metres,
            accuracy: 40,
            speed: nil
        ))
        return evidence
    }

    // MARK: - The baseline bounds

    @Test("A baseline shorter than the minimum decides nothing and keeps the anchor")
    func shortBaselineIsInconclusive() {
        // Arrange — 1 s apart at 40 m/s: unambiguous travel on any speed reading.
        let evidence = evidence(seconds: MovementEvidencePolicy.minimumBaseline - 1, metres: 900)

        // Assert — not counted, and not rejected either: the answer is "not yet".
        #expect(evidence.derivedMovingSampleCount == 0)
        #expect(evidence.movementEvidenceRejection == nil)
    }

    @Test("The minimum baseline decides at the bound and not one second before")
    func minimumBaselineBoundary() {
        // Arrange
        let minimum = MovementEvidencePolicy.minimumBaseline

        // Assert
        #expect(evidence(seconds: minimum - 1, metres: 900).derivedMovingSampleCount == 0)
        #expect(evidence(seconds: minimum, metres: 900).derivedMovingSampleCount == 1)
    }

    @Test("A baseline past the ceiling is rejected, because an average over it means nothing")
    func longBaselineIsRejected() {
        // Arrange
        let maximum = MovementEvidencePolicy.maximumBaseline

        // Act — 5 km either side of the bound. Not 20 km: that would be 111 m/s, which
        // `LocationOutlierPolicy` drops as a GPS jump before movement is ever considered.
        let atBound = evidence(seconds: maximum, metres: 5000)
        let pastBound = evidence(seconds: maximum + 1, metres: 5000)

        // Assert
        #expect(atBound.derivedMovingSampleCount == 1)
        #expect(pastBound.derivedMovingSampleCount == 0)
        #expect(pastBound.movementEvidenceRejection == .intervalTooLong)
    }

    // MARK: - The speed threshold, expressed as distance

    @Test("Clearing the noise floor is not enough: the average speed must still be travel")
    func slowTravelIsRejectedAsTooShort() {
        // Arrange — 150 m in 120 s is 1.25 m/s, a walk, but it clears a 10 m/10 m floor.
        let evidence = evidence(seconds: 120, metres: 150)

        // Assert
        #expect(evidence.derivedMovingSampleCount == 0)
        #expect(evidence.movementEvidenceRejection == .distanceTooShort)
    }

    @Test("The movement threshold boundary is the same 2 m/s the speed path uses")
    func speedThresholdBoundary() {
        // Arrange — over 120 s, 2 m/s is exactly 240 m.
        let threshold = DrivingConfirmationPolicy.movingSpeedThreshold * 120

        // Assert
        #expect(evidence(seconds: 120, metres: threshold - 1).movementEvidenceRejection == .distanceTooShort)
        #expect(evidence(seconds: 120, metres: threshold).derivedMovingSampleCount == 1)
    }

    // MARK: - Anchor behaviour

    @Test("A rejection for coarse accuracy keeps the anchor, so a longer baseline can decide")
    func rejectionKeepsTheAnchor() {
        // Arrange — an accuracy rejection at 30 s that the next fix resolves at 60 s.
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, accuracy: 100, speed: nil))
        evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(30), metersNorth: 200, accuracy: 100, speed: nil))
        #expect(evidence.movementEvidenceRejection == .accuracyTooCoarse)

        // Act — 400 m from the *original* anchor, which now clears the 283 m floor.
        evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(60), metersNorth: 400, accuracy: 100, speed: nil))

        // Assert
        #expect(evidence.derivedMovingSampleCount == 1)
        #expect(evidence.movementEvidenceRejection == nil)
    }

    @Test("An outlier is never movement evidence and never becomes the anchor")
    func outlierIsNotMovementEvidence() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        evidence.record(fix: TestGeo.fix(at: start, metersNorth: 0, speed: nil))

        // Act — 10 km in one second.
        evidence.record(fix: TestGeo.fix(at: start.addingTimeInterval(1), metersNorth: 10000, speed: nil))

        // Assert — counted as an outlier only; the speed counters describe accepted fixes.
        #expect(evidence.outlierCount == 1)
        #expect(evidence.speedMissingCount == 1)
        #expect(evidence.derivedMovingSampleCount == 0)
    }

    @Test("An invalid fix never reaches the movement counters at all")
    func invalidFixIsNotCounted() {
        // Arrange
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)

        // Act
        evidence.record(fix: TestGeo.fix(at: start, accuracy: -1, speed: nil))

        // Assert
        #expect(evidence.speedMissingCount == 0)
        #expect(evidence.speedAvailableCount == 0)
    }

    // MARK: - The end-to-end claim

    /// The defect, stated as a test: no speed anywhere, and the session confirms anyway.
    @Test("A drive with no speed on any fix can now reach confirmation")
    func speedlessDriveConfirms() {
        // Arrange — three 40 s legs of 800 m, all without a speed.
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        for step in 0 ... 3 {
            evidence.record(fix: TestGeo.fix(
                at: start.addingTimeInterval(Double(step) * 40),
                metersNorth: Double(step) * 800,
                speed: nil
            ))
        }

        // Assert
        #expect(evidence.speedAvailableCount == 0)
        #expect(evidence.movingSampleCount >= DrivingConfirmationPolicy.minimumMovingSamples)
        #expect(DrivingConfirmationPolicy.isConfirmed(evidence, now: start.addingTimeInterval(130)))
    }
}

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

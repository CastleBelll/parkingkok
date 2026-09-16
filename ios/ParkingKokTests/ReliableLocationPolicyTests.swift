import Foundation
import Testing
@testable import ParkingKok

@Suite("lastReliableLocation selection")
struct ReliableLocationPolicyTests {
    private let now = TestTime.offset(0)

    private func decide(
        accuracy: Double,
        secondsOld: TimeInterval,
        incumbent: LastReliableLocation? = nil
    ) -> ReliableLocationDecision {
        ReliableLocationPolicy.evaluate(
            candidate: TestGeo.fix(at: now.addingTimeInterval(-secondsOld), accuracy: accuracy),
            incumbent: incumbent,
            now: now
        )
    }

    private func incumbent(accuracy: Double, secondsOld: TimeInterval) -> LastReliableLocation {
        LastReliableLocation(
            latitude: TestGeo.originLatitude,
            longitude: TestGeo.originLongitude,
            horizontalAccuracy: accuracy,
            capturedAt: now.addingTimeInterval(-secondsOld)
        )
    }

    // MARK: - The gate (docs/05 §6)

    @Test("A fix at exactly the 35 m accuracy bound is admitted; one metre past it is not")
    func accuracyBoundary() {
        // Arrange / Act
        let atBound = decide(accuracy: ReliableLocationPolicy.maximumHorizontalAccuracy, secondsOld: 1)
        let pastBound = decide(accuracy: ReliableLocationPolicy.maximumHorizontalAccuracy + 1, secondsOld: 1)

        // Assert
        #expect(atBound == .accepted(
            LastReliableLocation(
                latitude: TestGeo.originLatitude,
                longitude: TestGeo.originLongitude,
                horizontalAccuracy: 35,
                capturedAt: now.addingTimeInterval(-1)
            )
        ))
        #expect(pastBound == .rejected(.accuracyTooCoarse))
    }

    @Test("A fix at exactly the 20 s freshness bound is admitted; one second past it is not")
    func freshnessBoundary() {
        // Arrange / Act
        let atBound = decide(accuracy: 10, secondsOld: ReliableLocationPolicy.maximumAge)
        let pastBound = decide(accuracy: 10, secondsOld: ReliableLocationPolicy.maximumAge + 1)

        // Assert
        #expect(atBound != .rejected(.stale))
        #expect(pastBound == .rejected(.stale))
    }

    @Test("Core Location's negative accuracy is rejected before anything else is considered")
    func negativeAccuracyRejected() {
        // Arrange / Act — perfectly fresh, and still invalid.
        let decision = decide(accuracy: -1, secondsOld: 0)

        // Assert
        #expect(decision == .rejected(.invalidAccuracy))
    }

    @Test("A fix from beyond the clock-skew tolerance is rejected as being from the future")
    func futureFixRejected() {
        // Arrange / Act
        let withinTolerance = decide(accuracy: 10, secondsOld: -ReliableLocationPolicy.futureTolerance)
        let beyondTolerance = decide(accuracy: 10, secondsOld: -(ReliableLocationPolicy.futureTolerance + 1))

        // Assert
        #expect(withinTolerance != .rejected(.fromTheFuture))
        #expect(beyondTolerance == .rejected(.fromTheFuture))
    }

    /// The bound this whole policy exists for, and the one that is not
    /// `LocationFreshnessPolicy`'s. A 60 s fix is ordinary on the significant-change path
    /// and unusable as a parking spot.
    @Test("The session bound is far tighter than the significant-change bound, by design")
    func sessionBoundIsNotTheSignificantChangeBound() {
        // Arrange
        let sixtySecondsOld = LocationQualitySample(
            timestamp: now.addingTimeInterval(-60),
            horizontalAccuracy: 10
        )

        // Act / Assert
        #expect(LocationFreshnessPolicy.isFresh(sixtySecondsOld, now: now))
        #expect(decide(accuracy: 10, secondsOld: 60) == .rejected(.stale))
        #expect(ReliableLocationPolicy.maximumAge < LocationFreshnessPolicy.significantChangeMaxAge)
    }

    // MARK: - The comparison (docs/05 §5, §6)

    @Test("With nothing stored, any admissible fix is taken")
    func firstAdmissibleFixIsTaken() {
        // Arrange / Act
        let decision = decide(accuracy: 30, secondsOld: 5)

        // Assert
        guard case let .accepted(selected) = decision else {
            Issue.record("expected the first admissible fix to be accepted")
            return
        }
        #expect(selected.horizontalAccuracy == 30)
    }

    @Test("A coarse fix never overwrites a better fresh one")
    func poorSampleDoesNotOverwrite() {
        // Arrange — newer, still inside the gate, but three times coarser.
        let stored = incumbent(accuracy: 8, secondsOld: 10)

        // Act
        let decision = decide(accuracy: 30, secondsOld: 2, incumbent: stored)

        // Assert
        #expect(decision == .rejected(.lessAccurateThanFreshIncumbent))
    }

    @Test("A newer fix of equal or better accuracy replaces the stored one")
    func newerAndNotWorseWins() {
        // Arrange
        let stored = incumbent(accuracy: 20, secondsOld: 10)

        // Act
        let equal = decide(accuracy: 20, secondsOld: 2, incumbent: stored)
        let better = decide(accuracy: 6, secondsOld: 2, incumbent: stored)

        // Assert
        #expect(equal != .rejected(.lessAccurateThanFreshIncumbent))
        #expect(better != .rejected(.lessAccurateThanFreshIncumbent))
        if case let .accepted(selected) = better {
            #expect(selected.horizontalAccuracy == 6)
        } else {
            Issue.record("a strictly better fix must be accepted")
        }
    }

    @Test("An older fix never walks the stored value backwards in time")
    func olderFixRejected() {
        // Arrange — more accurate, but from before what we already hold.
        let stored = incumbent(accuracy: 20, secondsOld: 5)

        // Act
        let decision = decide(accuracy: 5, secondsOld: 10, incumbent: stored)

        // Assert
        #expect(decision == .rejected(.notNewerThanIncumbent))
    }

    /// Once the stored fix is older than the session bound the car has moved, so a merely
    /// admissible fix beats an accurate memory of somewhere else.
    @Test("A stale stored value is replaced even by a less accurate admissible fix")
    func staleIncumbentIsReplaced() {
        // Arrange
        let stored = incumbent(accuracy: 5, secondsOld: ReliableLocationPolicy.maximumAge + 10)

        // Act
        let decision = decide(accuracy: 30, secondsOld: 1, incumbent: stored)

        // Assert
        guard case let .accepted(selected) = decision else {
            Issue.record("a stale incumbent must not block a fresh admissible fix")
            return
        }
        #expect(selected.horizontalAccuracy == 30)
    }

    @Test("A fix failing the gate is rejected no matter how stale the stored value is")
    func gateBeatsStaleness() {
        // Arrange
        let stored = incumbent(accuracy: 5, secondsOld: 600)

        // Act / Assert
        #expect(decide(accuracy: 80, secondsOld: 1, incumbent: stored) == .rejected(.accuracyTooCoarse))
        #expect(decide(accuracy: 10, secondsOld: 300, incumbent: stored) == .rejected(.stale))
    }

    // MARK: - Checkpoint write pressure (docs/05 §14)

    @Test("Only a materially better value earns a checkpoint write")
    func materialityGuardsTheWrite() {
        // Arrange
        let stored = incumbent(accuracy: 20, secondsOld: 40)
        let marginal = LastReliableLocation(
            latitude: TestGeo.originLatitude,
            longitude: TestGeo.originLongitude,
            horizontalAccuracy: 19,
            capturedAt: now.addingTimeInterval(-39)
        )
        let muchMoreAccurate = LastReliableLocation(
            latitude: TestGeo.originLatitude,
            longitude: TestGeo.originLongitude,
            horizontalAccuracy: 20 - ReliableLocationPolicy.materialAccuracyGain,
            capturedAt: now.addingTimeInterval(-39)
        )
        let muchNewer = LastReliableLocation(
            latitude: TestGeo.originLatitude,
            longitude: TestGeo.originLongitude,
            horizontalAccuracy: 20,
            capturedAt: now.addingTimeInterval(-40 + ReliableLocationPolicy.materialAgeGap)
        )

        // Act / Assert
        #expect(!ReliableLocationPolicy.isMateriallyBetter(marginal, than: stored))
        #expect(ReliableLocationPolicy.isMateriallyBetter(muchMoreAccurate, than: stored))
        #expect(ReliableLocationPolicy.isMateriallyBetter(muchNewer, than: stored))
        #expect(ReliableLocationPolicy.isMateriallyBetter(marginal, than: nil))
    }
}

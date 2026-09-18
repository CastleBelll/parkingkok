import Foundation
import Testing
@testable import ParkingKok

/// docs/05_PARKING_DETECTION_ENGINE.md §7, the `distance >= 800m` clause.
///
/// The clause used to accumulate every step §5's 90 m/s cap let through, which is no gate
/// at all on a coarse fix: two fixes accurate to 1000 m recorded 60 s and 900 m apart read
/// as 15 m/s and passed it. Android had the opposite defect, accumulating only from fixes
/// §6's 35 m reliability bar admitted, which underground is almost none of them.
///
/// The unified rule holds an anchor and adds a leg only when its displacement clears the
/// pair's combined error — the same 2σ floor `MovementEvidencePolicy` uses. The tests below
/// are that rule's moving parts: refuse and keep the anchor, clear and advance it, reach
/// the floor eventually on slow travel, and never let a jump through.
///
/// `TravelDistanceAccumulationTest` asserts the same numbers on Android.
@Suite("Distance accumulation under the §7 noise floor")
struct DrivingDistanceAccumulationTests {
    private let start = TestTime.offset(0)

    /// A session with vehicle evidence already in hand; only the fixes are under test.
    private func session() -> DrivingEvidence {
        DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
    }

    private func record(
        _ evidence: DrivingEvidence,
        atSecond second: TimeInterval,
        metersNorth: Double,
        accuracy: Double = 10
    ) -> DrivingEvidence {
        var next = evidence
        next.record(fix: TestGeo.fix(
            at: start.addingTimeInterval(second),
            metersNorth: metersNorth,
            accuracy: accuracy,
            speed: nil
        ))
        return next
    }

    @Test("The first fix of a session contributes nothing and becomes the anchor")
    func firstFixOnlyAnchors() {
        // Arrange / Act — the fix before it belongs to the previous trip.
        let evidence = record(session(), atSecond: 0, metersNorth: 0)

        // Assert
        #expect(evidence.distanceMeters == 0)
        #expect(evidence.distanceNoiseFloorRejectCount == 0)
    }

    @Test("A leg inside the combined error is not accumulated and the anchor is kept")
    func legInsideTheFloorKeepsTheAnchor() {
        // Arrange — two 10 m fixes put the floor at 2·sqrt(10² + 10²) ≈ 28.28 m. The
        // second fix is 10 m out, the third 29 m out from the *first*. Had the refusal
        // advanced the anchor, the third leg would measure 19 m and be refused too.
        let afterRefusal = record(
            record(session(), atSecond: 0, metersNorth: 0),
            atSecond: 10,
            metersNorth: 10
        )

        // Act
        let afterClearing = record(afterRefusal, atSecond: 20, metersNorth: 29)

        // Assert
        #expect(afterRefusal.distanceMeters == 0)
        #expect(afterRefusal.distanceNoiseFloorRejectCount == 1)
        #expect(abs(afterClearing.distanceMeters - 29) < 0.01)
        #expect(afterClearing.distanceNoiseFloorRejectCount == 1)
    }

    @Test("Clearing the floor advances the anchor, so the same metres are never counted twice")
    func clearingTheFloorAdvancesTheAnchor() {
        // Arrange — 40 m then 80 m, each leg 40 m long and each clearing the 28.28 m floor.
        var evidence = record(session(), atSecond: 0, metersNorth: 0)
        evidence = record(evidence, atSecond: 10, metersNorth: 40)
        evidence = record(evidence, atSecond: 20, metersNorth: 80)

        // Assert — 80 m, not 40 + 80 = 120 m, which is what a held anchor would have given.
        #expect(abs(evidence.distanceMeters - 80) < 0.01)
        #expect(evidence.distanceNoiseFloorRejectCount == 0)
    }

    @Test("Slow travel accumulates once the held anchor is far enough away")
    func slowTravelEventuallyClearsTheFloor() {
        // Arrange — 2 m/s at 1 Hz. No single leg ever clears the 28.28 m floor, which is
        // exactly the case a consecutive-pair rule could never decide.
        var evidence = session()
        for second in 0 ... 15 {
            evidence = record(
                evidence,
                atSecond: TimeInterval(second),
                metersNorth: Double(second) * 2
            )
        }

        // Assert — the 15th second is 30 m from the anchor, so one leg lands; the 14 fixes
        // before it were refused and kept the anchor in place.
        #expect(abs(evidence.distanceMeters - 30) < 0.01)
        #expect(evidence.distanceNoiseFloorRejectCount == 14)
    }

    @Test("A coarse pair whose displacement clearly exceeds its own error still accumulates")
    func coarseButRealTravelAccumulates() {
        // Arrange — the underground case Android's ≤35 m gate made impossible: two fixes
        // accurate to 500 m, 2000 m apart. The floor is 2·sqrt(500² + 500²) ≈ 1414 m.
        var evidence = record(session(), atSecond: 0, metersNorth: 0, accuracy: 500)
        evidence = record(evidence, atSecond: 60, metersNorth: 2000, accuracy: 500)

        // Assert
        #expect(abs(evidence.distanceMeters - 2000) < 1)
        #expect(evidence.distanceNoiseFloorRejectCount == 0)
    }

    @Test("Jitter that the §5 speed cap lets through is refused by the noise floor")
    func coarseJitterIsRefused() {
        // Arrange — docs/05 §7's worked example: two fixes accurate to 1000 m, 900 m apart
        // in 60 s. That is 15 m/s, well under the 90 m/s outlier cap, and it is noise.
        var evidence = record(session(), atSecond: 0, metersNorth: 0, accuracy: 1000)
        evidence = record(evidence, atSecond: 60, metersNorth: 900, accuracy: 1000)

        // Assert — the floor is 2·sqrt(2)·1000 ≈ 2828 m, so nothing accumulates.
        #expect(evidence.distanceMeters == 0)
        #expect(evidence.distanceNoiseFloorRejectCount == 1)
    }

    @Test("An implausible jump is not distance, and it does not disturb the anchor")
    func outlierGateStillRuns() {
        // Arrange — §5's outlier cap stays in front of the noise floor. 10 km in 1 s is a
        // teleport; the fix after it is ordinary travel measured from the original anchor.
        let evidence = record(
            record(session(), atSecond: 0, metersNorth: 0),
            atSecond: 1,
            metersNorth: 10000
        )

        // Act
        let afterOrdinaryFix = record(evidence, atSecond: 11, metersNorth: 40)

        // Assert
        #expect(evidence.distanceMeters == 0)
        #expect(evidence.outlierCount == 1)
        #expect(evidence.distanceNoiseFloorRejectCount == 0)
        #expect(abs(afterOrdinaryFix.distanceMeters - 40) < 0.01)
    }

    @Test("An invalid fix is not a leg at all")
    func invalidFixIsNotALeg() {
        // Arrange — docs/05 §5: a negative accuracy is Core Location saying "not a fix".
        var evidence = record(session(), atSecond: 0, metersNorth: 0)
        evidence = record(evidence, atSecond: 10, metersNorth: 4000, accuracy: -1)

        // Assert — neither accumulated nor counted against the noise floor.
        #expect(evidence.distanceMeters == 0)
        #expect(evidence.distanceNoiseFloorRejectCount == 0)
    }
}

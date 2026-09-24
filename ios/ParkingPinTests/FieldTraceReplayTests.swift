import Foundation
import Testing
@testable import ParkingPin

/// Replays the September 2026 field traces through `DrivingEvidence`.
///
/// ### Why this fixture is shaped like this
/// The traces themselves carry no coordinate — docs/05 §9 bans one, and a test on the
/// encoded bytes enforces it — so what a recorded `location` event preserves is its
/// accuracy, its timestamp and the metres travelled since the previous recorded event.
/// That is exactly enough to rebuild a fix stream: the path is laid out along one
/// meridian, each step advancing by the recorded metres.
///
/// The rebuilt path is therefore **straight**, which makes the anchor-to-fix displacement
/// the full path length rather than the shorter straight line a real route would give. The
/// replay is optimistic about distance, which is the honest direction to err in when the
/// claim being tested is "this gate is not too tight".
///
/// The events are also the *downsampled* stream (`TraceRecorder.minimumLocationInterval`
/// is 15 s), so the cadence here is coarser than the ~1 Hz `DrivingEvidence` sees live.
///
/// ### What the numbers are
/// `distanceFromPreviousM` is present exactly when an event and its predecessor are both
/// bounded fixes, so an event without it starts a new run; significant-change quality
/// samples never reach `DrivingEvidence` and are not replayed.
@Suite("Field trace replay (2026-09-16/17, iPhone15,3)")
struct FieldTraceReplayTests {
    /// One recorded `location` event: seconds since the trace started, the fix accuracy in
    /// metres, and the metres travelled since the previous event in the same run.
    struct Sample {
        let t: TimeInterval
        let accuracy: Double
        let step: Double
    }

    /// Rebuilds one bounded run and folds it into a session that already has fresh vehicle
    /// evidence. **No fix carries a speed**, because not one of the 87 recovered bounded
    /// fixes did.
    private func replay(_ run: [Sample]) -> DrivingEvidence {
        let start = TestTime.offset(0)
        var evidence = DrivingEvidence(startedAt: start, lastVehicleEvidenceAt: start)
        var metersNorth = 0.0
        for sample in run {
            metersNorth += sample.step
            evidence.record(fix: TestGeo.fix(
                at: start.addingTimeInterval(sample.t),
                metersNorth: metersNorth,
                accuracy: sample.accuracy,
                speed: nil
            ))
        }
        return evidence
    }

    /// Run totals. A bounded run is its own session, so each starts from a clean anchor.
    private struct Totals {
        var moving = 0
        var derived = 0
        var speedAvailable = 0
        var speedMissing = 0
        var distanceMeters = 0.0
        var distanceNoiseFloorRejects = 0
    }

    private func totals(_ runs: [[Sample]]) -> Totals {
        runs.map(replay).reduce(into: Totals()) {
            $0.moving += $1.movingSampleCount
            $0.derived += $1.derivedMovingSampleCount
            $0.speedAvailable += $1.speedAvailableCount
            $0.speedMissing += $1.speedMissingCount
            $0.distanceMeters += $1.distanceMeters
            $0.distanceNoiseFloorRejects += $1.distanceNoiseFloorRejectCount
        }
    }

    // MARK: - The observation that motivated the fallback

    /// The whole reason §7 stopped meaning "speed": on the field device it never arrived.
    @Test("Not one recorded bounded fix carried a speed, so the speed-only rule scored zero")
    func speedWasNeverAvailable() {
        // Arrange / Act
        let subway = totals(Self.subwayCommute)
        let walk = totals(Self.officeWalk)

        // Assert — speed available nowhere, so every moving sample is the fallback's.
        #expect(subway.speedAvailable == 0)
        #expect(walk.speedAvailable == 0)
        #expect(subway.speedMissing == 58)
        #expect(walk.speedMissing == 35)
        // This is the "before" number: strip the derived samples and nothing is left.
        #expect(subway.moving - subway.derived == 0)
        #expect(walk.moving - walk.derived == 0)
    }

    // MARK: - Positive case: the underground commute

    /// 2h39m underground: `vehicle_enter` at 18:23, `vehicle_exit` + `walking_enter` at
    /// 19:08, 16 GPS quality degradations in between, 4.1 km of accumulated steps — and,
    /// before the fallback, zero movement evidence.
    @Test("The subway commute clears the movement requirement it could not clear on speed")
    func subwayCommuteNowConfirms() {
        // Act
        let totals = totals(Self.subwayCommute)

        // Assert — 0 before, 3 after, and 3 is past the "one event never confirms" bar.
        #expect(totals.moving == 3)
        #expect(totals.derived == 3)
        #expect(totals.moving >= MovementEvidencePolicy.minimumMovingSamples)
    }

    // MARK: - Negative case: the same device, walking

    /// The control. Same device, same hour, same missing speed — 35 bounded fixes over
    /// 65 m. If the fallback scored here it would be confirming drives on foot.
    @Test("Walking around the office is never movement evidence, fallback or not")
    func officeWalkStaysBelowTheGate() {
        // Act
        let totals = totals(Self.officeWalk)

        // Assert
        #expect(totals.moving == 0)
        #expect(totals.moving < MovementEvidencePolicy.minimumMovingSamples)
    }

    /// The pair the gate was chosen against, in the run it actually appears in.
    ///
    /// Bounded run 3 is the dive underground: a clean 24.9 m fix, then 366 m, then 521 m,
    /// then a 47.9 m fix 928 m further on. Measured from the 521 m fix that is 145 km/h on
    /// a line whose trains do not exceed 80 — noise, and the gate has to say so. Measured
    /// from the clean fix 58 s earlier the same displacement is 57 km/h, which is simply
    /// the train. Holding the anchor is what buys that second reading; a consecutive-pair
    /// rule would only ever have had the first.
    @Test("The coarse fix is rejected as noise, and the same metres decide once a clean anchor spans them")
    func coarsePairIsRejectedButTheTrainIsNot() {
        // Arrange — bounded run 3, truncated just after the 521 m fix.
        let run = Self.subwayCommute[2]

        // Act
        let throughCoarseFix = replay(Array(run.prefix(3)))
        let wholeRun = replay(run)

        // Assert — 2·sqrt(24.9² + 521²) ≈ 1043 m against 0.05 m of measured displacement.
        #expect(throughCoarseFix.derivedMovingSampleCount == 0)
        #expect(throughCoarseFix.movementEvidenceRejection == .accuracyTooCoarse)
        // And the 47.9 m fix, measured against the 24.9 m anchor, clears a 108 m floor.
        #expect(wholeRun.derivedMovingSampleCount == 1)
        #expect(wholeRun.movementEvidenceRejection == nil)
    }

    // MARK: - The §7 distance clause, under the same noise floor

    /// The other half of the 2026-09-18 unification, on the same recorded metres.
    @Test("The noise floor keeps recorded jitter out of the accumulated distance")
    func distanceAccumulatesOnlyWhatClearsTheFloor() {
        // Arrange — every recorded step is under §5's 90 m/s cap, so the pre-unification
        // rule here accumulated all of it, and Android's ≤35 m gate accumulated almost
        // none of it.
        let recordedSteps = Self.subwayCommute.joined().reduce(0) { $0 + $1.step }

        // Act
        let subway = totals(Self.subwayCommute)
        let walk = totals(Self.officeWalk)

        // Assert — these exact figures are asserted on Android too; a difference is a
        // parity defect, not a tuning difference.
        #expect(abs(recordedSteps - 4120.30) < 0.01)
        #expect(abs(subway.distanceMeters - 3966.49) < 0.1)
        #expect(subway.distanceNoiseFloorRejects == 50)
        #expect(abs(walk.distanceMeters - 43.82) < 0.1)
        #expect(walk.distanceNoiseFloorRejects == 33)
    }

    /// The control that says the floor is doing something, not merely passing everything.
    @Test("The stationary office runs accumulate nothing at all")
    func deskJitterAccumulatesNothing() {
        // Arrange — runs 1 and 2 are 30 fixes at a desk before the commute, 26.5 m of
        // jitter between them. Under the old rule every centimetre of that was distance
        // towards §7's 800 m clause.
        let atTheDesk = Array(Self.subwayCommute.prefix(2))

        // Act
        let evidence = totals(atTheDesk)

        // Assert
        #expect(abs(atTheDesk.joined().reduce(0) { $0 + $1.step } - 26.47) < 0.01)
        #expect(evidence.distanceMeters == 0)
        #expect(evidence.distanceNoiseFloorRejects == 28)
    }

    // MARK: - Fixtures

    /// `trace-1789544225798`, label `{mode: subway}`. 58 bounded fixes in 5 runs.
    static let subwayCommute: [[Sample]] = [
        [ // bounded run 1: 16 fixes
            .init(t: 48.952, accuracy: 20.10, step: 0.00),
            .init(t: 70.069, accuracy: 16.06, step: 4.06),
            .init(t: 94.457, accuracy: 14.35, step: 2.77),
            .init(t: 118.076, accuracy: 13.69, step: 1.36),
            .init(t: 137.964, accuracy: 12.31, step: 0.27),
            .init(t: 160.844, accuracy: 11.82, step: 0.74),
            .init(t: 184.842, accuracy: 11.33, step: 1.21),
            .init(t: 209.234, accuracy: 11.01, step: 0.42),
            .init(t: 232.841, accuracy: 10.71, step: 0.89),
            .init(t: 259.848, accuracy: 10.03, step: 1.64),
            .init(t: 277.220, accuracy: 9.70, step: 0.52),
            .init(t: 298.546, accuracy: 8.63, step: 5.09),
            .init(t: 322.530, accuracy: 8.44, step: 0.52),
            .init(t: 346.521, accuracy: 8.38, step: 0.49),
            .init(t: 370.690, accuracy: 8.21, step: 0.48),
            .init(t: 394.527, accuracy: 7.86, step: 0.79)
        ],
        [ // bounded run 2: 14 fixes
            .init(t: 412.731, accuracy: 7.76, step: 0.00),
            .init(t: 437.132, accuracy: 7.53, step: 0.85),
            .init(t: 460.752, accuracy: 7.43, step: 0.20),
            .init(t: 484.894, accuracy: 7.34, step: 0.36),
            .init(t: 511.923, accuracy: 7.18, step: 0.50),
            .init(t: 526.926, accuracy: 7.11, step: 0.24),
            .init(t: 541.931, accuracy: 7.03, step: 0.23),
            .init(t: 556.936, accuracy: 6.96, step: 0.22),
            .init(t: 571.942, accuracy: 6.90, step: 0.21),
            .init(t: 586.947, accuracy: 6.83, step: 0.20),
            .init(t: 601.953, accuracy: 6.77, step: 0.19),
            .init(t: 627.396, accuracy: 6.48, step: 1.20),
            .init(t: 642.398, accuracy: 6.40, step: 0.42),
            .init(t: 657.404, accuracy: 6.31, step: 0.40)
        ],
        [ // bounded run 3: 4 fixes
            .init(t: 8555.603, accuracy: 24.87, step: 0.00),
            .init(t: 8572.553, accuracy: 366.20, step: 0.04),
            .init(t: 8591.070, accuracy: 520.97, step: 0.01),
            .init(t: 8614.089, accuracy: 47.86, step: 927.85)
        ],
        [ // bounded run 4: 7 fixes
            .init(t: 8614.089, accuracy: 47.86, step: 0.00),
            .init(t: 8657.413, accuracy: 29.91, step: 17.30),
            .init(t: 8675.432, accuracy: 315.59, step: 0.23),
            .init(t: 8710.750, accuracy: 426.04, step: 43.63),
            .init(t: 8727.308, accuracy: 51.79, step: 958.07),
            .init(t: 8746.392, accuracy: 50.76, step: 5.08),
            .init(t: 8764.896, accuracy: 39.45, step: 2.99)
        ],
        [ // bounded run 5: 17 fixes
            .init(t: 8809.261, accuracy: 1000.00, step: 0.00),
            .init(t: 8835.468, accuracy: 22.79, step: 963.18),
            .init(t: 8869.081, accuracy: 29.81, step: 4.49),
            .init(t: 8923.598, accuracy: 611.89, step: 88.27),
            .init(t: 8942.585, accuracy: 32.06, step: 783.79),
            .init(t: 8950.226, accuracy: 38.00, step: 9.77),
            .init(t: 8964.138, accuracy: 175.20, step: 15.23),
            .init(t: 8984.483, accuracy: 52.18, step: 30.63),
            .init(t: 9008.848, accuracy: 30.98, step: 36.31),
            .init(t: 9017.846, accuracy: 37.68, step: 7.15),
            .init(t: 9035.699, accuracy: 71.01, step: 80.54),
            .init(t: 9049.813, accuracy: 35.21, step: 47.23),
            .init(t: 9067.713, accuracy: 67.60, step: 6.25),
            .init(t: 9075.494, accuracy: 24.20, step: 54.21),
            .init(t: 9076.664, accuracy: 87.90, step: 2.13),
            .init(t: 9095.240, accuracy: 30.89, step: 2.83),
            .init(t: 9105.628, accuracy: 46.47, step: 6.62)
        ]
    ]

    /// `trace-1789537784682`, label `{mode: walk, note: 사무실}`. 35 bounded fixes.
    static let officeWalk: [[Sample]] = [
        [ // bounded run 1: 35 fixes
            .init(t: 0.000, accuracy: 16.60, step: 0.00),
            .init(t: 22.757, accuracy: 12.42, step: 6.14),
            .init(t: 46.574, accuracy: 11.69, step: 0.34),
            .init(t: 62.881, accuracy: 11.30, step: 2.34),
            .init(t: 80.467, accuracy: 13.60, step: 9.17),
            .init(t: 98.366, accuracy: 13.90, step: 20.38),
            .init(t: 114.030, accuracy: 13.70, step: 5.45),
            .init(t: 131.933, accuracy: 11.80, step: 6.66),
            .init(t: 152.867, accuracy: 11.31, step: 1.91),
            .init(t: 170.625, accuracy: 11.02, step: 0.90),
            .init(t: 186.598, accuracy: 10.66, step: 1.55),
            .init(t: 206.602, accuracy: 10.38, step: 1.35),
            .init(t: 221.608, accuracy: 10.35, step: 0.21),
            .init(t: 236.614, accuracy: 10.32, step: 0.21),
            .init(t: 251.619, accuracy: 10.29, step: 0.21),
            .init(t: 266.624, accuracy: 10.26, step: 0.20),
            .init(t: 281.629, accuracy: 10.23, step: 0.20),
            .init(t: 296.635, accuracy: 10.20, step: 0.20),
            .init(t: 311.640, accuracy: 10.17, step: 0.20),
            .init(t: 326.645, accuracy: 10.15, step: 0.19),
            .init(t: 341.650, accuracy: 10.12, step: 0.19),
            .init(t: 356.656, accuracy: 10.09, step: 0.19),
            .init(t: 371.661, accuracy: 10.06, step: 0.19),
            .init(t: 386.666, accuracy: 10.03, step: 0.18),
            .init(t: 417.490, accuracy: 9.45, step: 1.68),
            .init(t: 441.490, accuracy: 9.00, step: 1.19),
            .init(t: 465.541, accuracy: 8.96, step: 0.27),
            .init(t: 486.627, accuracy: 8.82, step: 0.31),
            .init(t: 507.635, accuracy: 8.55, step: 0.55),
            .init(t: 522.637, accuracy: 8.42, step: 0.25),
            .init(t: 537.643, accuracy: 8.30, step: 0.24),
            .init(t: 552.648, accuracy: 8.18, step: 0.22),
            .init(t: 567.654, accuracy: 8.07, step: 0.21),
            .init(t: 585.623, accuracy: 7.77, step: 1.30),
            .init(t: 609.905, accuracy: 7.63, step: 0.62)
        ]
    ]
}

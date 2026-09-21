package kr.parkingpin.app.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the September 2026 iOS field traces through [DrivingSessionEvidence].
 *
 * ### Why an iOS trace on Android
 * The trace format is the platform-neutral contract in
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9, so a recording made on one platform is a
 * legitimate input to the other's engine. That is the whole point of §7's parity claim:
 * the same fixes have to produce the same verdict. `ParkingPinTests/FieldTraceReplayTests`
 * replays these exact runs and reaches 3 moving samples on the subway commute and 0 on the
 * walking control; anything else here is a parity defect, not a tuning difference.
 *
 * ### Why the fixture is shaped like this
 * The traces carry no coordinate — §9 bans one — so what a recorded `location` event
 * preserves is its accuracy, its timestamp, and the metres travelled since the previous
 * recorded event. That is exactly enough to rebuild a fix stream: the path is laid out
 * along one meridian, each step advancing by the recorded metres.
 *
 * The rebuilt path is therefore **straight**, which makes the anchor-to-fix displacement
 * the full path length rather than the shorter straight line a real route would give. The
 * replay is optimistic about distance, which is the honest direction to err in when the
 * claim under test is "this gate is not too tight".
 *
 * `distanceFromPreviousM` is present exactly when an event and its predecessor are both
 * bounded fixes, so an event without it starts a new run; the isolated significant-change
 * quality samples never reached the bounded session and are not replayed.
 */
class FieldTraceReplayTest {

    /** One recorded `location` event, relative to the start of its trace. */
    data class Sample(val atMillis: Long, val accuracyM: Float, val stepMeters: Double)

    private val start = 1_700_000_000_000L

    /**
     * Rebuilds one bounded run and folds it in. **No fix carries a speed**, because not
     * one of the 87 recovered bounded fixes did.
     *
     * The whole session evidence rather than the movement clause alone, because §7's two
     * measured clauses share a noise floor and this file is where they are checked against
     * the same recorded metres.
     */
    private fun replay(run: List<Sample>): DrivingSessionEvidence {
        var metersNorth = 0.0
        var evidence = DrivingSessionEvidence(
            vehicleFirstSeenAtMillis = start,
            lastVehicleEvidenceAtMillis = start,
        )
        for (sample in run) {
            metersNorth += sample.stepMeters
            evidence = evidence.recordingFix(
                TestGeo.sample(
                    atMillis = start + sample.atMillis,
                    metersNorth = metersNorth,
                    accuracyM = sample.accuracyM,
                    speedMps = null,
                ),
            )
        }
        return evidence
    }

    /** Run totals. A bounded run is its own session, so each starts from a clean anchor. */
    private fun totals(runs: List<List<Sample>>): Totals =
        runs.map(::replay).fold(Totals()) { acc, run ->
            Totals(
                movingSampleCount = acc.movingSampleCount + run.movement.movingSampleCount,
                derivedMovingSampleCount =
                    acc.derivedMovingSampleCount + run.movement.derivedMovingSampleCount,
                speedAvailableCount = acc.speedAvailableCount + run.movement.speedAvailableCount,
                speedMissingCount = acc.speedMissingCount + run.movement.speedMissingCount,
                travelDistanceMeters = acc.travelDistanceMeters + run.travelDistanceMeters,
                distanceNoiseFloorRejectCount =
                    acc.distanceNoiseFloorRejectCount + run.distanceNoiseFloorRejectCount,
            )
        }

    private data class Totals(
        val movingSampleCount: Int = 0,
        val derivedMovingSampleCount: Int = 0,
        val speedAvailableCount: Int = 0,
        val speedMissingCount: Int = 0,
        val travelDistanceMeters: Double = 0.0,
        val distanceNoiseFloorRejectCount: Int = 0,
    )

    @Test
    fun `not one recorded bounded fix carried a speed, so the speed-only rule scored zero`() {
        // Arrange / Act
        val subway = totals(SUBWAY_COMMUTE)
        val walk = totals(OFFICE_WALK)

        // Assert — speed available nowhere, so every moving sample is the fallback's.
        assertEquals(0, subway.speedAvailableCount)
        assertEquals(0, walk.speedAvailableCount)
        assertEquals(58, subway.speedMissingCount)
        assertEquals(35, walk.speedMissingCount)
        // This is the "before" number: strip the derived samples and nothing is left.
        assertEquals(0, subway.movingSampleCount - subway.derivedMovingSampleCount)
        assertEquals(0, walk.movingSampleCount - walk.derivedMovingSampleCount)
    }

    @Test
    fun `the subway commute clears the movement requirement it could not clear on speed`() {
        // Act
        val subway = totals(SUBWAY_COMMUTE)

        // Assert — 0 before the unification, 3 after, and iOS reaches 3 on the same input.
        assertEquals(3, subway.movingSampleCount)
        assertEquals(3, subway.derivedMovingSampleCount)
        assertTrue(subway.movingSampleCount >= DrivingConfirmationGuard.MIN_MOVING_SAMPLES)
    }

    @Test
    fun `walking around the office is never movement evidence, fallback or not`() {
        // Arrange — the control. Same device, same hour, same missing speed: 35 bounded
        // fixes over 65 m. If the fallback scored here it would confirm drives on foot.
        val walk = totals(OFFICE_WALK)

        // Assert
        assertEquals(0, walk.movingSampleCount)
        assertTrue(walk.movingSampleCount < DrivingConfirmationGuard.MIN_MOVING_SAMPLES)
    }

    @Test
    fun `the coarse pair is rejected, and the same metres decide once a clean anchor spans them`() {
        // Arrange — bounded run 3 is the dive underground: a clean 24.9 m fix, then
        // 366 m, then 521 m, then a 47.9 m fix 928 m further on. Measured from the 521 m
        // fix that is 145 km/h on a line whose trains do not exceed 80.
        val run = SUBWAY_COMMUTE[2]

        // Act
        val throughCoarseFix = replay(run.take(3))
        val wholeRun = replay(run)

        // Assert — 2·sqrt(24.9² + 521²) ≈ 1043 m against 0.05 m of measured displacement.
        assertEquals(0, throughCoarseFix.movement.derivedMovingSampleCount)
        assertEquals(
            MovementEvidenceRejectReason.ACCURACY_TOO_COARSE,
            throughCoarseFix.movement.rejectReason,
        )
        // And the 47.9 m fix, measured against the 24.9 m anchor 58 s earlier, clears a
        // 108 m floor at 57 km/h — which is simply the train.
        assertEquals(1, wholeRun.movement.derivedMovingSampleCount)
        assertNull(wholeRun.movement.rejectReason)
    }

    @Test
    fun `the noise floor keeps recorded jitter out of the accumulated distance`() {
        // Arrange — every recorded step is under §5's 90 m/s cap, so the pre-unification
        // iOS rule accumulated all of it and the pre-unification Android rule, gated on
        // §6's 35 m bar, accumulated almost none of it.
        val recordedSteps = SUBWAY_COMMUTE.flatten().sumOf { it.stepMeters }

        // Act
        val subway = totals(SUBWAY_COMMUTE)
        val walk = totals(OFFICE_WALK)

        // Assert — these exact figures are asserted on iOS too; a difference is a parity
        // defect, not a tuning difference.
        assertEquals(4_120.30, recordedSteps, 0.01)
        assertEquals(3_966.49, subway.travelDistanceMeters, 0.1)
        assertEquals(50, subway.distanceNoiseFloorRejectCount)
        assertEquals(43.82, walk.travelDistanceMeters, 0.1)
        assertEquals(33, walk.distanceNoiseFloorRejectCount)
    }

    @Test
    fun `the stationary office runs accumulate nothing at all`() {
        // Arrange — runs 1 and 2 are 30 fixes at a desk before the commute, 26.5 m of
        // jitter between them. Under the old iOS rule every centimetre of that was
        // distance towards §7's 800 m clause.
        val atTheDesk = SUBWAY_COMMUTE.take(2)

        // Act
        val evidence = totals(atTheDesk)

        // Assert
        assertEquals(26.47, atTheDesk.flatten().sumOf { it.stepMeters }, 0.01)
        assertEquals(0.0, evidence.travelDistanceMeters, 0.001)
        assertEquals(28, evidence.distanceNoiseFloorRejectCount)
    }

    private companion object {
        /** `trace-1789544225798`, label `{mode: subway}`. 58 bounded fixes in 5 runs. */
        val SUBWAY_COMMUTE: List<List<Sample>> = listOf(
            listOf( // bounded run 1: 16 fixes
                Sample(48952L, 20.10f, 0.00),
                Sample(70069L, 16.06f, 4.06),
                Sample(94457L, 14.35f, 2.77),
                Sample(118076L, 13.69f, 1.36),
                Sample(137964L, 12.31f, 0.27),
                Sample(160844L, 11.82f, 0.74),
                Sample(184842L, 11.33f, 1.21),
                Sample(209234L, 11.01f, 0.42),
                Sample(232841L, 10.71f, 0.89),
                Sample(259848L, 10.03f, 1.64),
                Sample(277220L, 9.70f, 0.52),
                Sample(298546L, 8.63f, 5.09),
                Sample(322530L, 8.44f, 0.52),
                Sample(346521L, 8.38f, 0.49),
                Sample(370690L, 8.21f, 0.48),
                Sample(394527L, 7.86f, 0.79),
            ),
            listOf( // bounded run 2: 14 fixes
                Sample(412731L, 7.76f, 0.00),
                Sample(437132L, 7.53f, 0.85),
                Sample(460752L, 7.43f, 0.20),
                Sample(484894L, 7.34f, 0.36),
                Sample(511923L, 7.18f, 0.50),
                Sample(526926L, 7.11f, 0.24),
                Sample(541931L, 7.03f, 0.23),
                Sample(556936L, 6.96f, 0.22),
                Sample(571942L, 6.90f, 0.21),
                Sample(586947L, 6.83f, 0.20),
                Sample(601953L, 6.77f, 0.19),
                Sample(627396L, 6.48f, 1.20),
                Sample(642398L, 6.40f, 0.42),
                Sample(657404L, 6.31f, 0.40),
            ),
            listOf( // bounded run 3: 4 fixes
                Sample(8555603L, 24.87f, 0.00),
                Sample(8572553L, 366.20f, 0.04),
                Sample(8591070L, 520.97f, 0.01),
                Sample(8614089L, 47.86f, 927.85),
            ),
            listOf( // bounded run 4: 7 fixes
                Sample(8614089L, 47.86f, 0.00),
                Sample(8657413L, 29.91f, 17.30),
                Sample(8675432L, 315.59f, 0.23),
                Sample(8710750L, 426.04f, 43.63),
                Sample(8727308L, 51.79f, 958.07),
                Sample(8746392L, 50.76f, 5.08),
                Sample(8764896L, 39.45f, 2.99),
            ),
            listOf( // bounded run 5: 17 fixes
                Sample(8809261L, 1000.00f, 0.00),
                Sample(8835468L, 22.79f, 963.18),
                Sample(8869081L, 29.81f, 4.49),
                Sample(8923598L, 611.89f, 88.27),
                Sample(8942585L, 32.06f, 783.79),
                Sample(8950226L, 38.00f, 9.77),
                Sample(8964138L, 175.20f, 15.23),
                Sample(8984483L, 52.18f, 30.63),
                Sample(9008848L, 30.98f, 36.31),
                Sample(9017846L, 37.68f, 7.15),
                Sample(9035699L, 71.01f, 80.54),
                Sample(9049813L, 35.21f, 47.23),
                Sample(9067713L, 67.60f, 6.25),
                Sample(9075494L, 24.20f, 54.21),
                Sample(9076664L, 87.90f, 2.13),
                Sample(9095240L, 30.89f, 2.83),
                Sample(9105628L, 46.47f, 6.62),
            ),
        )

        /** `trace-1789537784682`, label `{mode: walk, note: 사무실}`. 35 bounded fixes. */
        val OFFICE_WALK: List<List<Sample>> = listOf(
            listOf( // bounded run 1: 35 fixes
                Sample(12L, 16.60f, 0.00),
                Sample(22769L, 12.42f, 6.14),
                Sample(46586L, 11.69f, 0.34),
                Sample(62893L, 11.30f, 2.34),
                Sample(80479L, 13.60f, 9.17),
                Sample(98378L, 13.90f, 20.38),
                Sample(114042L, 13.70f, 5.45),
                Sample(131945L, 11.80f, 6.66),
                Sample(152879L, 11.31f, 1.91),
                Sample(170637L, 11.02f, 0.90),
                Sample(186610L, 10.66f, 1.55),
                Sample(206614L, 10.38f, 1.35),
                Sample(221620L, 10.35f, 0.21),
                Sample(236626L, 10.32f, 0.21),
                Sample(251631L, 10.29f, 0.21),
                Sample(266636L, 10.26f, 0.20),
                Sample(281641L, 10.23f, 0.20),
                Sample(296647L, 10.20f, 0.20),
                Sample(311652L, 10.17f, 0.20),
                Sample(326657L, 10.15f, 0.19),
                Sample(341662L, 10.12f, 0.19),
                Sample(356668L, 10.09f, 0.19),
                Sample(371673L, 10.06f, 0.19),
                Sample(386678L, 10.03f, 0.18),
                Sample(417502L, 9.45f, 1.68),
                Sample(441502L, 9.00f, 1.19),
                Sample(465553L, 8.96f, 0.27),
                Sample(486639L, 8.82f, 0.31),
                Sample(507647L, 8.55f, 0.55),
                Sample(522649L, 8.42f, 0.25),
                Sample(537655L, 8.30f, 0.24),
                Sample(552660L, 8.18f, 0.22),
                Sample(567666L, 8.07f, 0.21),
                Sample(585635L, 7.77f, 1.30),
                Sample(609917L, 7.63f, 0.62),
            ),
        )
    }
}

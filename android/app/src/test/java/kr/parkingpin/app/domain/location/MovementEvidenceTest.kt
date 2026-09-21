package kr.parkingpin.app.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

/**
 * docs/05_PARKING_DETECTION_ENGINE.md §7, "양 플랫폼 통일 (2026-09-18 결정)".
 *
 * The clause is judged **per pair of fixes**, speed first and a distance fallback only
 * when there is no speed to read. These tests pin the four gates the fallback is made of
 * and the anchor rule that makes it usable at 1 Hz, on both sides of every boundary.
 *
 * Semantics are shared with iOS `MovementEvidencePolicy` by contract, not by shared code,
 * so the boundaries here are the ones `MovementEvidencePolicyTests` asserts over there.
 */
class MovementEvidenceTest {

    private val start = 1_700_000_000_000L

    /** Metres of displacement the noise floor allows for two fixes of this accuracy. */
    private fun noiseFloor(firstM: Double, secondM: Double): Double =
        MovementEvidencePolicy.NOISE_FLOOR_SIGMAS * sqrt(firstM * firstM + secondM * secondM)

    // MARK: speed route

    @Test
    fun `a fix at or above the threshold speed is movement evidence on its own`() {
        // Arrange — 2.0 m/s exactly, the boundary the unified spec names.
        val evidence = MovementEvidence()

        // Act
        val folded = evidence.recording(TestGeo.sample(start, speedMps = 2.0f))

        // Assert
        assertEquals(1, folded.movingSampleCount)
        assertEquals(1, folded.speedAvailableCount)
        assertEquals(0, folded.derivedMovingSampleCount)
        assertEquals(0, folded.speedMissingCount)
    }

    @Test
    fun `a fix below the threshold speed is counted but is not movement evidence`() {
        // Arrange — walking pace, and the old 8 m_s bar would have rejected a car
        // crawling through a car park the same way.
        val folded = MovementEvidence().recording(TestGeo.sample(start, speedMps = 1.9f))

        // Assert
        assertEquals(0, folded.movingSampleCount)
        assertEquals(1, folded.speedAvailableCount)
    }

    @Test
    fun `a fix that carried a speed still anchors the fallback that follows it`() {
        // Arrange — entering a tunnel: speed, then no speed for the rest of the drive.
        val withSpeed = MovementEvidence().recording(TestGeo.sample(start, speedMps = 12f))

        // Act — 30 s and 900 m later, with no speed at all.
        val folded = withSpeed.recording(
            TestGeo.sample(start + 30_000L, metersNorth = 900.0, speedMps = null),
        )

        // Assert — the fallback did not have to start cold.
        assertEquals(2, folded.movingSampleCount)
        assertEquals(1, folded.derivedMovingSampleCount)
        assertEquals(1, folded.speedMissingCount)
    }

    // MARK: baseline boundaries

    @Test
    fun `a baseline one millisecond under the floor decides nothing and keeps the anchor`() {
        // Arrange
        val anchored = MovementEvidence().recording(TestGeo.sample(start))

        // Act
        val folded = anchored.recording(
            TestGeo.sample(start + MovementEvidencePolicy.MIN_BASELINE_MILLIS - 1L, metersNorth = 900.0),
        )

        // Assert — inconclusive is not a rejection: nothing to report, anchor untouched.
        assertEquals(0, folded.movingSampleCount)
        assertNull(folded.rejectReason)
        assertEquals(start, folded.anchor?.atMillis)
    }

    @Test
    fun `a baseline exactly at the floor is decidable`() {
        // Arrange
        val anchored = MovementEvidence().recording(TestGeo.sample(start))

        // Act
        val folded = anchored.recording(
            TestGeo.sample(start + MovementEvidencePolicy.MIN_BASELINE_MILLIS, metersNorth = 900.0),
        )

        // Assert
        assertEquals(1, folded.derivedMovingSampleCount)
        assertNull(folded.rejectReason)
    }

    @Test
    fun `a baseline exactly at the ceiling still describes travel`() {
        // Arrange
        val anchored = MovementEvidence().recording(TestGeo.sample(start))

        // Act — 180 s, 900 m: 5 m/s, which is a drive.
        val folded = anchored.recording(
            TestGeo.sample(start + MovementEvidencePolicy.MAX_BASELINE_MILLIS, metersNorth = 900.0),
        )

        // Assert
        assertEquals(1, folded.derivedMovingSampleCount)
    }

    @Test
    fun `a baseline past the ceiling is rejected and re-anchors`() {
        // Arrange — past the ceiling an average flattens "drive, stop, drive" into
        // something that describes no moment in between.
        val anchored = MovementEvidence().recording(TestGeo.sample(start))
        val lateMillis = start + MovementEvidencePolicy.MAX_BASELINE_MILLIS + 1L

        // Act
        val folded = anchored.recording(TestGeo.sample(lateMillis, metersNorth = 9_000.0))

        // Assert — this is the one rejection that invalidates the anchor.
        assertEquals(0, folded.movingSampleCount)
        assertEquals(MovementEvidenceRejectReason.INTERVAL_TOO_LONG, folded.rejectReason)
        assertEquals(lateMillis, folded.anchor?.atMillis)
    }

    // MARK: the 2σ noise floor

    @Test
    fun `a displacement inside the combined uncertainty is noise, not travel`() {
        // Arrange — the measured pair the gate was chosen against: accuracies 521 m and
        // 47.9 m, 928 m apart in 23 s. At face value 145 km/h on a line whose trains do
        // not exceed 80. The floor is 2·sqrt(521² + 47.9²) ≈ 1046 m, so it is refused.
        val anchored = MovementEvidence().recording(TestGeo.sample(start, accuracyM = 521f))

        // Act — 40 s so the baseline itself is never the reason.
        val folded = anchored.recording(
            TestGeo.sample(start + 40_000L, metersNorth = 928.0, accuracyM = 47.9f),
        )

        // Assert
        assertEquals(0, folded.movingSampleCount)
        assertEquals(MovementEvidenceRejectReason.ACCURACY_TOO_COARSE, folded.rejectReason)
        // A coarse pair keeps the anchor: a longer baseline is what makes it decidable.
        assertEquals(start, folded.anchor?.atMillis)
    }

    @Test
    fun `a displacement one metre past the floor clears it`() {
        // Arrange — same accuracies, same clock, one metre more displacement than the
        // floor allows, and fast enough to be travel.
        val floor = noiseFloor(24.87, 47.86)
        val anchored = MovementEvidence().recording(TestGeo.sample(start, accuracyM = 24.87f))

        // Act
        val folded = anchored.recording(
            TestGeo.sample(start + 40_000L, metersNorth = floor + 1.0, accuracyM = 47.86f),
        )

        // Assert
        assertEquals(1, folded.derivedMovingSampleCount)
        assertNull(folded.rejectReason)
    }

    @Test
    fun `a coarse fix underground is still evidence when it plainly outruns its own error`() {
        // Arrange — the reason §6's 35 m bar is deliberately absent here. Both fixes
        // would fail that bar; the displacement is ten times their combined uncertainty.
        val anchored = MovementEvidence().recording(TestGeo.sample(start, accuracyM = 100f))

        // Act
        val folded = anchored.recording(
            TestGeo.sample(start + 60_000L, metersNorth = 2_000.0, accuracyM = 300f),
        )

        // Assert
        assertEquals(1, folded.derivedMovingSampleCount)
    }

    // MARK: the travel threshold, expressed as distance

    @Test
    fun `clearing the noise floor slowly is not travel`() {
        // Arrange — 100 m in 180 s is 0.56 m/s: past the floor for these accuracies, and
        // slower than a walk.
        val anchored = MovementEvidence().recording(TestGeo.sample(start, accuracyM = 5f))

        // Act
        val folded = anchored.recording(
            TestGeo.sample(start + 180_000L, metersNorth = 100.0, accuracyM = 5f),
        )

        // Assert
        assertEquals(0, folded.movingSampleCount)
        assertEquals(MovementEvidenceRejectReason.DISTANCE_TOO_SHORT, folded.rejectReason)
        assertEquals(start, folded.anchor?.atMillis)
    }

    // MARK: the anchor rule

    @Test
    fun `holding the anchor through a failure is what makes the next fix decidable`() {
        // Arrange — at 1 Hz a 50 km_h drive covers 13.9 m between fixes against a 28 m
        // noise floor, so a consecutive-pair rule could never decide. Three 10 s steps.
        var evidence = MovementEvidence().recording(TestGeo.sample(start))

        // Act
        evidence = evidence.recording(TestGeo.sample(start + 10_000L, metersNorth = 139.0))
        val midway = evidence
        evidence = evidence.recording(TestGeo.sample(start + 20_000L, metersNorth = 278.0))
        evidence = evidence.recording(TestGeo.sample(start + 30_000L, metersNorth = 417.0))

        // Assert — undecidable twice, then decided across the whole 30 s.
        assertEquals(0, midway.movingSampleCount)
        assertEquals(start, midway.anchor?.atMillis)
        assertEquals(1, evidence.derivedMovingSampleCount)
        assertEquals(start + 30_000L, evidence.anchor?.atMillis)
    }

    @Test
    fun `a success moves the anchor forward`() {
        // Arrange
        var evidence = MovementEvidence().recording(TestGeo.sample(start))
        evidence = evidence.recording(TestGeo.sample(start + 30_000L, metersNorth = 900.0))

        // Act — 20 s past the new anchor is under the floor again.
        val folded = evidence.recording(TestGeo.sample(start + 50_000L, metersNorth = 1_800.0))

        // Assert
        assertEquals(1, folded.derivedMovingSampleCount)
        assertEquals(start + 30_000L, folded.anchor?.atMillis)
    }

    // MARK: fixes that never reach the clause

    @Test
    fun `an implausible jump is counted as an outlier and never anchors anything`() {
        // Arrange — 50 km in 60 s. Fused Location does hand these over.
        val anchored = MovementEvidence().recording(TestGeo.sample(start))

        // Act
        val folded = anchored.recording(TestGeo.sample(start + 60_000L, metersNorth = 50_000.0))

        // Assert
        assertEquals(1, folded.outlierCount)
        assertEquals(0, folded.movingSampleCount)
        assertEquals(1, folded.speedMissingCount)
        assertEquals(start, folded.lastFix?.atMillis)
        assertEquals(start, folded.anchor?.atMillis)
    }

    @Test
    fun `a negative accuracy is not a fix and changes nothing`() {
        // Arrange — docs/05 §5: Fused Location reports a negative accuracy when the fix
        // is invalid, and the caller has already counted it.
        val evidence = MovementEvidence().recording(TestGeo.sample(start))

        // Act
        val folded = evidence.recording(TestGeo.sample(start + 40_000L, metersNorth = 900.0, accuracyM = -1f))

        // Assert
        assertEquals(evidence, folded)
    }
}

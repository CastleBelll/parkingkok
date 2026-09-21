package kr.parkingpin.app.domain.location

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * docs/05_PARKING_DETECTION_ENGINE.md §5: "impossible speed/distance outliers rejected".
 *
 * A GPS jump between two fixes is not travel, and after §7's unification the movement
 * clause reads *every* valid fix rather than only the ones that cleared the 35 m
 * reliability bar — so this is the only thing standing between a teleporting fix and a
 * confirmed drive. Semantics are shared with iOS `LocationOutlierPolicy` by contract.
 */
object LocationOutlierPolicy {

    /** ~324 km/h. Above this the *pair* is implausible for a car, so the newer fix is a jump. */
    const val MAX_PLAUSIBLE_SPEED_MPS: Double = 90.0

    fun isPlausibleStep(from: MovementAnchor, to: LocationSample): Boolean {
        val elapsedMillis = to.atMillis - from.atMillis
        // Same instant or time running backwards: no travel can be attributed either way,
        // so the step is implausible rather than a division by zero.
        if (elapsedMillis <= 0L) return false
        val meters = GeoDistance.meters(from.latitude, from.longitude, to.latitude, to.longitude)
        return meters / (elapsedMillis / MILLIS_PER_SECOND) <= MAX_PLAUSIBLE_SPEED_MPS
    }

    private const val MILLIS_PER_SECOND = 1000.0
}

/**
 * A fix the movement clause still has to measure *from*.
 *
 * This is the one place in the driving guard that holds a position, and it is unavoidable:
 * §7's noise floor is a statement about a **displacement**, and a displacement needs two
 * points. Nothing reads it but [MovementEvidencePolicy], nothing derived from it leaves as
 * anything but metres, and [toString] is redacted so an accidental log cannot leak it —
 * the same treatment [LocationSample] gets.
 */
@Serializable
data class MovementAnchor(
    val atMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float,
) {
    override fun toString(): String =
        "MovementAnchor(redacted, accuracyM=$horizontalAccuracyM, atMillis=$atMillis)"

    companion object {
        fun of(sample: LocationSample): MovementAnchor = MovementAnchor(
            atMillis = sample.atMillis,
            latitude = sample.latitude,
            longitude = sample.longitude,
            horizontalAccuracyM = sample.horizontalAccuracyM,
        )
    }
}

/**
 * Why the distance fallback declined to call one fix movement evidence.
 *
 * Reported in diagnostics rather than logged: a high `speedMissingCount` with
 * `derivedMovingSampleCount` at zero is the shape of the original defect, and this is the
 * field that says whether the gate is set wrong or the device really was not moving.
 * Wire strings are the ones docs/05 §7 names.
 */
enum class MovementEvidenceRejectReason(val wire: String) {
    /** 정확도 부족 — the displacement is inside the combined uncertainty of the two fixes. */
    ACCURACY_TOO_COARSE("accuracyTooCoarse"),

    /** 시간차 초과 — too far apart in time for an average speed between them to describe anything. */
    INTERVAL_TOO_LONG("intervalTooLong"),

    /** 거리 부족 — past the noise floor, but slower than the travel threshold. */
    DISTANCE_TOO_SHORT("distanceTooShort"),
    ;

    /**
     * Whether the *anchor* is what went wrong. A baseline past the ceiling describes two
     * unrelated stretches of travel, so the fix in hand becomes the new anchor. The other
     * two keep it: a longer baseline is precisely what turns an undecidable displacement
     * into a decidable one.
     */
    val invalidatesAnchor: Boolean
        get() = this == INTERVAL_TOO_LONG
}

/** The verdict on one fix that arrived without a speed. */
sealed interface MovementEvidenceOutcome {

    data object Moving : MovementEvidenceOutcome

    /**
     * Not yet decidable — the baseline since the anchor is still short. Not a rejection:
     * the anchor is kept and the same question is asked again on the next fix, so this
     * never reaches diagnostics.
     */
    data object Inconclusive : MovementEvidenceOutcome

    data class Rejected(val reason: MovementEvidenceRejectReason) : MovementEvidenceOutcome
}

/**
 * The distance-based half of docs/05 §7 "movement evidence consistent with travel".
 *
 * ### Why it exists
 * §7 never said *speed*. Both implementations read it as one, and the September 2026 field
 * traces say that reading does not survive contact with the product's main setting: 87 of
 * 87 bounded fixes carried no speed at all, because underground and in tunnels there is no
 * GPS Doppler to derive one from. A rule that can only confirm on speed cannot confirm in
 * an underground car park.
 *
 * ### Why the 35 m reliability bar is deliberately absent
 * §6's 35 m chooses a parking spot worth remembering; it does not decide whether the
 * device moved. Measured underground accuracy ran from 100 m to 2620 m, so gating movement
 * evidence on it would make driving confirmation structurally impossible underground —
 * the same dead end the speed-only rule had, reached by a different road. A coarse fix
 * whose displacement plainly exceeds its own error is still evidence of travel, and the
 * 2σ gate below is what makes that judgement.
 *
 * ### Every constant here is a field-tuning starting point
 * In the same sense as the §8 evidence weights, with one extra caveat: **there is no
 * above-ground car data behind any of them yet** (docs/05 §18).
 */
object MovementEvidencePolicy {

    /**
     * How many standard deviations of positional uncertainty the displacement has to clear.
     *
     * `horizontalAccuracy` is a 1σ radius, so the 1σ uncertainty of a *displacement*
     * between two independent fixes is `sqrt(a₁² + a₂²)`. Requiring two of those is a ~95%
     * one-sided statement that the device really moved. Chosen against a measured pair —
     * accuracies 521 m and 47.9 m recorded 928 m apart in 23 s, which is 145 km/h on a
     * line whose trains do not exceed 80 — whose gate works out at ≈1043 m, so it is
     * rejected. One sigma would have confirmed a drive on a train.
     */
    const val NOISE_FLOOR_SIGMAS: Double = 2.0

    /**
     * The shortest baseline on which a threshold-speed drive can clear the noise floor.
     *
     * Solving `movingSpeedThreshold · T >= NOISE_FLOOR_SIGMAS · sqrt(2) · a` at the 20 m
     * accuracy that ends the trace format's `good` bucket gives `T >= 28.3 s`. Shorter and
     * the gate would reject slow but real travel however clean the fixes were.
     */
    const val MIN_BASELINE_MILLIS: Long = 30_000L

    /**
     * The longest baseline an average speed still describes.
     *
     * Past this the average hides its own shape: a drive, a five-minute stop and another
     * drive average out to something that is not "consistent with travel" at any point in
     * between. It is also the horizon on which the vehicle evidence that must accompany
     * movement evidence expires.
     */
    const val MAX_BASELINE_MILLIS: Long = 180_000L

    /**
     * The displacement a pair of fixes has to clear before it describes travel rather than
     * noise.
     *
     * Shared with the `distance >= 800m` accumulation in [DrivingSessionEvidence]: docs/05
     * §7 puts both clauses behind one floor, so there is one definition of it.
     */
    fun noiseFloorMeters(anchor: MovementAnchor, fix: LocationSample): Double {
        val combinedVariance = anchor.horizontalAccuracyM.toDouble().squared() +
            fix.horizontalAccuracyM.toDouble().squared()
        return NOISE_FLOOR_SIGMAS * sqrt(combinedVariance)
    }

    fun evaluate(anchor: MovementAnchor, fix: LocationSample): MovementEvidenceOutcome {
        val baselineMillis = fix.atMillis - anchor.atMillis
        if (baselineMillis > MAX_BASELINE_MILLIS) {
            return MovementEvidenceOutcome.Rejected(MovementEvidenceRejectReason.INTERVAL_TOO_LONG)
        }
        // Covers a non-positive baseline too: two fixes at the same instant, or a clock
        // that stepped backwards, decide nothing and must not divide.
        if (baselineMillis < MIN_BASELINE_MILLIS) return MovementEvidenceOutcome.Inconclusive

        val displacement =
            GeoDistance.meters(anchor.latitude, anchor.longitude, fix.latitude, fix.longitude)
        if (displacement < noiseFloorMeters(anchor, fix)) {
            return MovementEvidenceOutcome.Rejected(MovementEvidenceRejectReason.ACCURACY_TOO_COARSE)
        }

        val baselineSeconds = baselineMillis / MILLIS_PER_SECOND
        if (displacement / baselineSeconds < DrivingConfirmationGuard.MOVING_SPEED_THRESHOLD_MPS) {
            return MovementEvidenceOutcome.Rejected(MovementEvidenceRejectReason.DISTANCE_TOO_SHORT)
        }
        return MovementEvidenceOutcome.Moving
    }

    private const val MILLIS_PER_SECOND = 1000.0

    private fun Double.squared(): Double = this * this
}

/**
 * What the session has seen of docs/05 §7's "movement evidence consistent with travel".
 *
 * Counted **per pair of fixes**, not accumulated as a session total. A sum cannot tell
 * "150 m travelled steadily" from "150 m of GPS jitter added up"; a pair-wise rule makes
 * each step clear its own error, so jitter never accumulates into a confirmation.
 *
 * Both routes into [movingSampleCount] are folded together on purpose — §7 asks for
 * movement evidence, not for a speed — and [derivedMovingSampleCount] says how much of it
 * the distance fallback contributed.
 */
@Serializable
data class MovementEvidence(
    /** Fixes that showed the device actually travelling, speed route and fallback together. */
    val movingSampleCount: Int = 0,
    /** Accepted fixes that carried a provider speed estimate, and accepted fixes that did not. */
    val speedAvailableCount: Int = 0,
    val speedMissingCount: Int = 0,
    /** The subset of [movingSampleCount] the distance fallback contributed. */
    val derivedMovingSampleCount: Int = 0,
    /** Why the fallback last declined a fix, or null once it last accepted one. */
    val rejectReason: MovementEvidenceRejectReason? = null,
    /** Fixes discarded as implausible jumps (docs/05 §5). */
    val outlierCount: Int = 0,
    /** Previous valid fix, kept only to reject a jump measured against it. */
    val lastFix: MovementAnchor? = null,
    /**
     * The fix the distance fallback measures against. Not the same thing as [lastFix]: it
     * is held until a baseline long enough to decide on has accumulated, so at 1 Hz it
     * spans many fixes. Holding it is the whole trick — between two consecutive 1 Hz fixes
     * a 50 km/h drive covers 13.9 m against a 28 m noise floor and could never decide.
     */
    val anchor: MovementAnchor? = null,
) {

    /**
     * Folds one fix in, for the session state that already exists.
     *
     * Invalid fixes never reach the clause: Fused Location reporting a negative accuracy is
     * saying that is not a fix at all, and the caller has already counted it as
     * [LocationDropReason.INVALID_ACCURACY].
     */
    fun recording(sample: LocationSample): MovementEvidence {
        if (!sample.quality.isValid) return this
        if (!admits(sample)) {
            // Deliberately does not advance [lastFix]: anchoring on a jump would make the
            // *next* legitimate fix look like a jump too.
            return copy(outlierCount = outlierCount + 1)
        }
        return withFixAccepted(sample).copy(lastFix = MovementAnchor.of(sample))
    }

    /**
     * Whether this fix will be folded in rather than counted as an implausible jump (§5).
     *
     * Public because §7's distance clause has to reach the same verdict on the same pair:
     * a GPS jump must not become metres any more than it may become movement evidence, and
     * asking this one question twice is what keeps it one gate rather than two.
     */
    fun admits(sample: LocationSample): Boolean {
        if (!sample.quality.isValid) return false
        val previous = lastFix ?: return true
        return LocationOutlierPolicy.isPlausibleStep(previous, sample)
    }

    /**
     * Speed first, exactly as §7 always meant it; the fallback runs only when there is no
     * speed to read. A fix that carried one is still the freshest anchor available to the
     * next fix that does not, so the fallback does not start cold when the estimate
     * disappears mid-drive — which is what entering a tunnel looks like.
     */
    private fun withFixAccepted(sample: LocationSample): MovementEvidence {
        val speed = sample.speedMps
        if (speed != null) {
            return copy(
                speedAvailableCount = speedAvailableCount + 1,
                movingSampleCount = movingSampleCount +
                    if (speed >= DrivingConfirmationGuard.MOVING_SPEED_THRESHOLD_MPS) 1 else 0,
                anchor = MovementAnchor.of(sample),
            )
        }

        val missing = copy(speedMissingCount = speedMissingCount + 1)
        val currentAnchor = anchor ?: return missing.copy(anchor = MovementAnchor.of(sample))

        return when (val outcome = MovementEvidencePolicy.evaluate(currentAnchor, sample)) {
            MovementEvidenceOutcome.Moving -> missing.copy(
                movingSampleCount = movingSampleCount + 1,
                derivedMovingSampleCount = derivedMovingSampleCount + 1,
                rejectReason = null,
                anchor = MovementAnchor.of(sample),
            )

            MovementEvidenceOutcome.Inconclusive -> missing

            is MovementEvidenceOutcome.Rejected -> missing.copy(
                rejectReason = outcome.reason,
                anchor = if (outcome.reason.invalidatesAnchor) MovementAnchor.of(sample) else currentAnchor,
            )
        }
    }
}

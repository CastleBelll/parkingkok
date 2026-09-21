import Foundation

/// Why a fix did not become the new `lastReliableLocation`.
///
/// A named reason rather than a bare `false`: in the field the interesting failure is
/// "every fix was rejected", and only the reason distinguishes a badly-set threshold
/// from a genuinely bad GPS environment.
enum ReliableLocationRejection: String, Sendable, Equatable, Codable {
    case invalidAccuracy
    case accuracyTooCoarse
    case stale
    case fromTheFuture
    case notNewerThanIncumbent
    case lessAccurateThanFreshIncumbent
}

enum ReliableLocationDecision: Sendable, Equatable {
    case accepted(LastReliableLocation)
    case rejected(ReliableLocationRejection)
}

/// Selects `lastReliableLocation` (docs/05_PARKING_DETECTION_ENGINE.md §6,
/// docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §7).
///
/// Two tiers, deliberately, because §5's "poor samples must not overwrite
/// lastReliableLocation" and §6's "selection favors newer + accurate" answer different
/// questions:
///
/// 1. **A gate.** Accuracy and freshness decide whether a fix is admissible at all. A fix
///    that fails the gate is never written, no matter what is already stored.
/// 2. **A comparison.** Among admissible fixes, a newer one wins, but only if it is not
///    less accurate than a still-fresh incumbent. Once the incumbent has itself gone
///    stale, any admissible fix beats it — during a drive the car has moved, so a
///    20-second-old fix no longer describes where the car is.
///
/// ### Why this freshness bound is not `LocationFreshnessPolicy`'s
///
/// `LocationFreshnessPolicy.significantChangeMaxAge` is 300 s and governs the
/// significant-change path. There, a fix can reach the app minutes late purely because
/// the process was suspended; that is a *delivery* delay and not a defect in the fix, so
/// the bound only has to exclude Core Location's cached-fix replay.
///
/// The 20 s here governs a different question on a different path: during an active
/// bounded session fixes stream at roughly 1 Hz, so a 20-second-old fix has already been
/// superseded several times over, and the value being chosen is the one the user will be
/// shown as *the parking spot*. docs/05 §5 states the separation explicitly — the 20 s
/// bound is session-only and must not be reused for significant-change samples.
///
/// Both numbers are field-tuning starting points in the same spirit as the §8 evidence
/// weights, not values the spec derives.
enum ReliableLocationPolicy {
    /// docs/05 §6 initial default.
    static let maximumHorizontalAccuracy: Double = 35

    /// docs/05 §6 initial default: freshness during an active session.
    static let maximumAge: TimeInterval = 20

    /// Device clock skew tolerance; mirrors `LocationFreshnessPolicy.futureTolerance`.
    static let futureTolerance: TimeInterval = 5

    /// How much better a new value has to be before it is worth a checkpoint write
    /// (docs/05 §14 "materially better lastReliableLocation"). At ~1 Hz an unconditional
    /// write would mean thousands of file writes per drive, which the battery gate in
    /// §19 would never pass.
    static let materialAccuracyGain: Double = 10
    static let materialAgeGap: TimeInterval = 30

    static func evaluate(
        candidate: LocationFix,
        incumbent: LastReliableLocation?,
        now: Date
    ) -> ReliableLocationDecision {
        guard candidate.isValid else { return .rejected(.invalidAccuracy) }

        let age = now.timeIntervalSince(candidate.timestamp)
        guard age >= -futureTolerance else { return .rejected(.fromTheFuture) }
        guard age <= maximumAge else { return .rejected(.stale) }
        guard candidate.horizontalAccuracy <= maximumHorizontalAccuracy else {
            return .rejected(.accuracyTooCoarse)
        }

        guard let incumbent else { return .accepted(candidate.reliableLocation) }
        guard candidate.timestamp > incumbent.capturedAt else {
            return .rejected(.notNewerThanIncumbent)
        }

        let incumbentIsStale = now.timeIntervalSince(incumbent.capturedAt) > maximumAge
        guard incumbentIsStale || candidate.horizontalAccuracy <= incumbent.horizontalAccuracy else {
            return .rejected(.lessAccurateThanFreshIncumbent)
        }
        return .accepted(candidate.reliableLocation)
    }

    /// Whether replacing `incumbent` with `candidate` justifies a checkpoint write.
    static func isMateriallyBetter(
        _ candidate: LastReliableLocation,
        than incumbent: LastReliableLocation?
    ) -> Bool {
        guard let incumbent else { return true }
        let accuracyGain = incumbent.horizontalAccuracy - candidate.horizontalAccuracy
        let ageGain = candidate.capturedAt.timeIntervalSince(incumbent.capturedAt)
        return accuracyGain >= materialAccuracyGain || ageGain >= materialAgeGap
    }
}

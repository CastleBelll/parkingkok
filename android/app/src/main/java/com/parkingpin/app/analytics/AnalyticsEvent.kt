package com.parkingpin.app.analytics

import com.parkingpin.app.domain.parking.ConfidenceBucket
import com.parkingpin.app.domain.trace.LocationQualityBucket
import kotlinx.serialization.Serializable

/**
 * Everything docs/17 §3 permits an event to say about a detection, and nothing else.
 *
 * **This class is the privacy boundary.** §3's forbidden list — lat/lon, route,
 * address/business/place, floor/spot/memo — has no property here and no free-form map to
 * fall back on, so there is nowhere for a caller to put one. That is what docs/07 means by
 * "호출자의 주의가 아니라 타입으로" blocked.
 *
 * Null buckets are omitted from the payload rather than defaulted: an absent property is
 * honest, a defaulted bucket invents a trip that was never measured.
 *
 * `@Serializable` because a [com.parkingpin.app.domain.detection.ParkingCandidate] carries
 * one across a process death: the candidate is created on one drive and answered minutes
 * later, possibly after a restart, and `_confirmed` has to describe the drive rather than
 * the moment of the answer. Persisting this type rather than loose fields keeps the
 * privacy boundary intact on the way to disk as well as on the way to Firebase.
 */
@Serializable
data class DetectionProperties(
    /** The engine's external confidence contract (docs/05 §5). */
    val confidenceBucket: ConfidenceBucket,
    val driveDurationBucket: DriveDurationBucket? = null,
    val distanceBucket: DistanceBucket? = null,
    /**
     * Reuses the recorded-trace bucket (good/fair/poor) rather than defining a third
     * good/fair/poor: docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2 fixed those edges.
     */
    val accuracyBucket: LocationQualityBucket? = null,
    val walkingEvidence: Boolean,
    val gpsDegradation: Boolean,
    val optionalVehicleSignal: Boolean,
    /**
     * Carried rather than stamped at send time, so replaying an older run reports the
     * version that produced it.
     */
    val detectorVersion: Int = DetectorVersion.CURRENT,
)

/**
 * The complete set of client events, and the only thing a call site can hand to
 * [AnalyticsRecording].
 *
 * **Exactly docs/17 §2's fourteen, one subtype each.** A fifteenth event is a change to
 * this hierarchy and therefore a reviewable diff against the contract — which is the point
 * of not having a `logEvent(name, params)` (docs/07 "Analytics 전송 수단").
 *
 * Each subtype carries only the properties docs/17 §3 allows *that* event. An event with
 * nothing to say carries nothing; `platform` is added once, for all of them, by
 * [AnalyticsPayload].
 */
sealed interface AnalyticsEvent {

    /** The wire name, verbatim from docs/17 §2. */
    val name: String

    data object OnboardingCompleted : AnalyticsEvent {
        override val name: String = "onboarding_completed"
    }

    data class PermissionMotionResult(val result: MotionPermissionResult) : AnalyticsEvent {
        override val name: String = "permission_motion_result"
    }

    data class PermissionLocationLevel(val level: LocationPermissionLevel) : AnalyticsEvent {
        override val name: String = "permission_location_level"
    }

    /**
     * Smart Detection's own opt-in. Not the analytics opt-in — see [AnalyticsConsentStore],
     * which is deliberately unreportable.
     */
    data class SmartDetectionEnabled(val enabled: Boolean) : AnalyticsEvent {
        override val name: String = "smart_detection_enabled"
    }

    data class ParkingCandidateCreated(val properties: DetectionProperties) : AnalyticsEvent {
        override val name: String = "parking_candidate_created"
    }

    data class ParkingCandidateConfirmed(val properties: DetectionProperties) : AnalyticsEvent {
        override val name: String = "parking_candidate_confirmed"
    }

    data class ParkingCandidateRejected(val properties: DetectionProperties) : AnalyticsEvent {
        override val name: String = "parking_candidate_rejected"
    }

    data object ParkingManualSaved : AnalyticsEvent {
        override val name: String = "parking_manual_saved"
    }

    data object ParkingAutoEnd : AnalyticsEvent {
        override val name: String = "parking_auto_end"
    }

    data class WidgetFloorChanged(val direction: WidgetFloorDirection) : AnalyticsEvent {
        override val name: String = "widget_floor_changed"
    }

    data object PaywallViewed : AnalyticsEvent {
        override val name: String = "paywall_viewed"
    }

    data class PurchaseCompleted(val store: PurchaseStore) : AnalyticsEvent {
        override val name: String = "purchase_completed"
    }

    data object ReferralShared : AnalyticsEvent {
        override val name: String = "referral_shared"
    }

    data object ReferralRedeemed : AnalyticsEvent {
        override val name: String = "referral_redeemed"
    }

    companion object {

        /** Every name this contract can produce, for the test that holds it against docs/17 §2. */
        val ALL_NAMES: Set<String> = setOf(
            "onboarding_completed",
            "permission_motion_result",
            "permission_location_level",
            "smart_detection_enabled",
            "parking_candidate_created",
            "parking_candidate_confirmed",
            "parking_candidate_rejected",
            "parking_manual_saved",
            "parking_auto_end",
            "widget_floor_changed",
            "paywall_viewed",
            "purchase_completed",
            "referral_shared",
            "referral_redeemed",
        )
    }
}

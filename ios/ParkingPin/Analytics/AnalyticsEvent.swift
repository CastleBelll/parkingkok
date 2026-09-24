import Foundation

/// The complete set of client events, and the only thing a call site can hand to
/// `AnalyticsRecording`.
///
/// **Exactly docs/17 §2's fourteen, one case each.** A fifteenth event is a change to this
/// enum and therefore a reviewable diff against the contract — which is the point of not
/// having a `logEvent(name:parameters:)` (docs/07 "Analytics 전송 수단").
///
/// Each case carries only the properties docs/17 §3 allows *that* event. An event with
/// nothing to say carries nothing; `platform` is added once, for all of them, by
/// `AnalyticsPayload`.
enum AnalyticsEvent: Sendable, Equatable {
    case onboardingCompleted
    case permissionMotionResult(MotionPermissionResult)
    case permissionLocationLevel(LocationPermissionLevel)
    /// Smart Detection's own opt-in. Not the analytics opt-in — see `AnalyticsConsent`,
    /// which is deliberately unreportable.
    case smartDetectionEnabled(Bool)
    case parkingCandidateCreated(DetectionProperties)
    case parkingCandidateConfirmed(DetectionProperties)
    case parkingCandidateRejected(DetectionProperties)
    case parkingManualSaved
    case parkingAutoEnd
    case widgetFloorChanged(WidgetFloorDirection)
    case paywallViewed
    case purchaseCompleted(PurchaseStore)
    case referralShared
    case referralRedeemed

    /// The wire name, verbatim from docs/17 §2.
    var name: String {
        switch self {
        case .onboardingCompleted: "onboarding_completed"
        case .permissionMotionResult: "permission_motion_result"
        case .permissionLocationLevel: "permission_location_level"
        case .smartDetectionEnabled: "smart_detection_enabled"
        case .parkingCandidateCreated: "parking_candidate_created"
        case .parkingCandidateConfirmed: "parking_candidate_confirmed"
        case .parkingCandidateRejected: "parking_candidate_rejected"
        case .parkingManualSaved: "parking_manual_saved"
        case .parkingAutoEnd: "parking_auto_end"
        case .widgetFloorChanged: "widget_floor_changed"
        case .paywallViewed: "paywall_viewed"
        case .purchaseCompleted: "purchase_completed"
        case .referralShared: "referral_shared"
        case .referralRedeemed: "referral_redeemed"
        }
    }

    /// Every name this contract can produce, for the test that holds it against docs/17 §2.
    static let allNames: Set<String> = [
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
        "referral_redeemed"
    ]
}

# 17. Observability & Privacy-safe Analytics — Cross-platform

## 1. Principle
Enough signal to improve detection/monetization, without uploading parking locations or travel traces.

## 2. Common Client Events
- onboarding_completed
- permission_motion_result
- permission_location_level
- smart_detection_enabled
- parking_candidate_created
- parking_candidate_confirmed
- parking_candidate_rejected
- parking_manual_saved
- parking_auto_end
- widget_floor_changed
- paywall_viewed
- purchase_completed
- referral_shared
- referral_redeemed

Properties always include `platform` where useful.

## 3. Allowed Detection Properties
- confidenceBucket
- driveDurationBucket
- distanceBucket
- accuracyBucket
- walkingEvidence bool
- gpsDegradation bool
- optionalVehicleSignal bool
- detectorVersion

Forbidden:
- lat/lon
- route
- address/business/place
- floor/spot/memo

## 4. Detector Versioning
Every scoring/rule change increments logical `detectorVersion`.
This allows rejection-rate comparison without location telemetry.

## 5. Platform Operational Metrics
### iOS
- background wake success coarse
- permission distribution
- widget failures
- Apple notification verify errors

### Android
- transition registration health
- candidate delivery while screen-off coarse
- manufacturer/model bucket where privacy policy permits and only for reliability diagnosis
- RTDN/API verify errors

Do not build invasive fingerprinting.

## 6. Backend Alerts
- Apple notification 5xx/JWS fail
- RTDN/PubSub processing failure
- Google API verification errors
- referral conversion failure
- offer/defer application errors
- App Check rejection spike

## 7. Business Metrics
- active Plus by store
- new paid conversions by store
- referral conversion
- rewards issued/applied
- AdMob metrics from console

Accounting uses store financial reports, not client events.

## 8. Detection Review
Beta review by platform + detector version:
- generated
- confirmed
- rejected
- timeout
- confidence bucket rejection rate
- qualitative battery issues

Threshold changes require changelog + staged rollout.

# 13. Google Play Review & Policy Checklist

## 1. Target API
- targetSdk >=36 for new/update submissions after 2026-08-31.
- verify current Play requirement at release time.

## 2. Background Location
Automatic parking detection is the core user-facing reason.
Before declaration:
- feature visible in store listing/onboarding
- clear prominent disclosure before permission
- permission requested only after user opts into Smart Detection
- manual mode remains available

Prepare Play Console background location declaration assets if required.

## 3. Permissions
- ACTIVITY_RECOGNITION: explain vehicle/walking transition
- ACCESS_FINE/COARSE_LOCATION: parking location
- ACCESS_BACKGROUND_LOCATION: only smart detection
- POST_NOTIFICATIONS: parking candidate alerts
- avoid unrelated permissions

## 4. Foreground Service
If location FGS is used:
- correct declared type
- user-visible notification
- policy justification
- no permanent background service without user benefit

## 5. Data Safety
Declare actual SDK behavior:
- Firebase anonymous auth/account data
- subscription/referral identifiers
- AdMob data
- crash analytics if enabled

Parking coordinate/photo are local-only; verify no SDK custom event sends them.

## 6. Billing
Digital Plus features use Google Play Billing.
- no external payment link inside app for same digital subscription unless allowed under current regional program/policy and intentionally implemented
- product price comes from Play product details
- purchase token verified backend
- acknowledgment handled

## 7. Ads
- no deceptive placement
- no accidental click adjacency
- no disruptive interstitial MVP
- Plus users no ad request
- consent flow valid

## 8. Subscription Disclosure
Paywall shows:
- billing period
- localized price
- auto-renew nature
- cancellation/manage path
- trial/promo terms if any

## 9. Referral
- code is not a bypass for direct paid unlock without store-compliant entitlement handling
- reward issued only after verified eligible conversion
- fraud/self-referral rules disclosed sufficiently

## 10. Testing Before Submission
- fresh install permission flow
- background location accepted/denied
- no notification permission
- battery saver
- no Google Play services edge case messaging
- purchase/restore
- cancelled/expired subscription
- reward defer sandbox/test where supported
- ad consent/no-fill

## 11. Store Listing Claims
Avoid:
- “100% automatic”
- “always knows exactly where your car is”
- “exact underground GPS”

Use:
- “주차 가능성을 스마트하게 감지”
- “마지막으로 확인된 위치 저장”

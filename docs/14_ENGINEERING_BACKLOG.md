# 14. Engineering Backlog & Milestones — Parallel iOS + Android

## Milestone 0A — iOS Feasibility
- Swift 6/iOS18 skeleton
- motion/location permissions
- low-power trigger
- motion history
- bounded location
- lastReliableLocation
- background candidate notification
- 20+ real trips
- battery baseline

## Milestone 0B — Android Feasibility
- Kotlin/Compose skeleton
- targetSdk36/minSdk29
- ACTIVITY_RECOGNITION
- IN_VEHICLE/WALKING transitions
- Fused Location bounded session
- background location permission flow
- screen-off/background candidate
- process death/reboot recovery
- Samsung + Pixel tests
- 20+ real trips
- battery baseline

### Gate M0
Both platforms must prove sufficient feasibility. If one is substantially unreliable, do not hide the issue with UI work; adjust platform scope or detector strategy.

## Milestone 1 — Common Contract
- normalized event model
- state model
- reason codes
- JSON fixture runner in Swift/Kotlin
- first 10 fixtures parity

## Milestone 2 — Local MVP Both
- home
- manual save
- active session
- history
- map/directions
- photo
- candidate notification
- settings/permissions

## Milestone 3 — Detection Beta
- full engines
- confidence tuning
- remote config clamps
- auto-end beta
- negative scenario testing

## Milestone 4 — Widgets
### iOS
WidgetKit/App Intents
### Android
Glance/callbacks

## Milestone 5 — Backend
- Firebase projects
- anonymous auth
- accountId
- App Check App Attest + Play Integrity
- Firestore rules
- Node22 functions
- referral pending

## Milestone 6A — Apple Billing
- StoreKit 2
- restore
- appAccountToken
- ASSN V2
- entitlement
- promo offer

## Milestone 6B — Google Billing
- Billing 9.1.x
- ProductDetails
- purchase token backend verify
- acknowledge
- RTDN
- entitlement
- subscriptionsv2.defer

## Milestone 7 — Referral Reward
- first-paid conversion across both stores
- ledger
- iOS offer apply
- Android defer apply
- abuse/race tests

## Milestone 8 — Ads
- UMP both
- test ads
- banner only
- Plus suppression

## Milestone 9 — Release Hardening
- field matrix both
- battery
- accessibility
- privacy labels/Data Safety
- App Store/TestFlight
- Play internal/closed testing
- review checklists

## Post-launch
- CarPlay entitlement
- Android Auto signal refinement
- optional account-linking for cross-platform recovery
- Siri/App Shortcuts
- Wear/Watch
- annual/lifetime pricing experiment
- KMP pure-domain feasibility only after stable parity

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

## Milestone 5a — Shared Parking (2026-09-20)
`docs/20_SHARED_PARKING.md` is the plan. Ordered so that each step is useful on its own and
the expensive parts come after the cheap ones have proved the shape.

1. **durable accounts + invite** — M5's floor, plus `cars/{carId}` and membership. No
   parking data yet; the screen says who is in the car and nothing else
2. **key exchange** — X25519 per member, car key wrapped per member, platform keystore both
   platforms. Still no parking data: the test is that two devices derive the same key
3. **the encrypted active parking** — one document, written after the local write, deleted
   when the parking ends
4. **candidate reconciliation** — §20 §5. The transaction that makes one confirmation
   supersede the others' pending candidates, reusing §10a's existing path
5. **early capture shutdown** — §20 §5's bonus: a phone that learns the car is parked stops
   its bounded location session instead of waiting for the timeout
6. **member removal + key rotation**, and the UI that says plainly what removal does and does
   not undo

Blocked on: **§20 §4 (위치정보법)** before shipping, and on the detector producing a candidate
on a real drive before starting — measured 2026-09-20, it has never done so.

## Milestones 6A–8 — deferred past v1 (2026-09-20)
Billing, referral and ads are **not in the first release**: v1 ships free and monetisation
follows real usage (docs/08 §1a). The milestones below stand unchanged for when that lands.
What exists now is one entitlement plug-in point per platform — `PlusEntitlementSource`
(iOS) and `plusEntitlement` (Android) — and nothing else.

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

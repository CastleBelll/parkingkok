# 11. QA & Test Strategy — iOS + Android

## 1. Test Pyramid
### Common domain
- floor parser
- state machine
- confidence
- referral ledger
- entitlement resolution
- cross-platform JSON fixtures

### iOS integration
- SwiftData/App Group
- notification action
- StoreKit
- WidgetKit/AppIntent

### Android integration
- Room/DataStore
- Activity Transition PendingIntent
- Fused Location adapter
- Glance callbacks
- Play Billing
- boot/package-update recovery

### Backend
- Emulator Suite
- Apple sandbox/JWS
- Google Play test purchases/RTDN staging

### Field
Mandatory real driving/parking.

## 2. Common Fixture Gate
Every detector PR runs `platform-tests/*.json` equivalent tests in Swift and Kotlin.
Outcome parity required.

## 3. Device Matrix
### iOS
- oldest supported practical iPhone
- mid-generation
- current flagship
- iOS 18 latest patch + current major versions

### Android
Minimum:
- Samsung Galaxy current/mid-range
- Google Pixel
- one additional OEM if feasible
- Android 10/12/14/16 coverage via physical + emulator as applicable

Background detection acceptance cannot rely on emulator only.

## 4. Parking Scenarios
Positive:
- underground mall
- apartment basement
- roadside
- outdoor lot
- hospital/airport style large lot
- mechanical parking where user walks away

Negative:
- bus
- taxi/rideshare
- subway
- tunnel
- long red light
- drive-through
- gas station
- highway rest stop
- passenger in friend's car

## 5. Permission Matrix
### iOS
- motion allow/deny
- location when-in-use/always/deny/revoked
- notifications allow/deny
- background app refresh
- low power mode

### Android
- activity recognition allow/deny
- approximate/precise location
- foreground only/background location
- notifications allow/deny
- battery saver/doze
- app standby/restricted background states where practical

## 6. Process/Lifecycle
### iOS
- OS termination
- foreground/background transitions
- significant-change wake behavior

### Android
- process kill
- force-stop behavior documented separately (force-stop disables many background deliveries until user relaunches)
- reboot
- package update
- screen off/doze
- OEM background restriction

No duplicate candidate after recovery.

## 7. Widget
Both:
- app dead/background
- no active parking
- Plus expiry
- B1 minus boundary
- rapid taps
- app/widget concurrent update
- offline

## 8. Store Tests
### Apple
- purchase/cancel/pending/renew/expire/grace/refund/restore
- promo offer eligible/ineligible

### Google Play
- purchase/cancel
- pending purchase if applicable
- acknowledge
- renewal
- grace/hold
- refund/revoke
- restore/query purchases
- RTDN duplication/out-of-order
- subscriptionsv2.defer reward application

## 9. Referral
- invalid/self/double code
- two inviters race
- first purchase Apple
- first purchase Google
- same account attempts conversion on second platform
- refund before reward
- duplicate webhook/RTDN
- App Check failure

## 10. Ads
Use test IDs.
- free request
- plus no request
- consent false/no-fill/offline
- layout collapses
- critical flow no ad

## 11. Privacy Network Test
Proxy/log inspect both release candidates:
- no coordinates to Firebase
- no photo/memo upload
- no raw route

## 12. Performance
### iOS
- cold launch
- SwiftData 1k/10k
- photo memory
- widget timeline

### Android
- startup benchmark/profile
- Room 1k/10k
- photo decode
- widget update latency
- receiver/service execution time

## 13. Battery
Baseline app disabled vs Smart Detection.
Scenarios:
- idle 8h
- mixed day
- 1h driving
- underground arrival

Report per device/OS, not one universal number.

## 14. Release Exit
- P0/P1 zero
- no duplicate reward
- no active session corruption
- acceptable field precision per platform
- both store purchase lifecycle stable
- review checklists passed by non-implementer

# 03. System Architecture — Dual Native

## 1. Architectural Decision
주차핀 v1은 **dual-native**다.

```text
                    Shared Product Contracts
          ┌──────────────────────────────────────┐
          │ PRD / Domain semantics / JSON tests  │
          │ Firebase / API / Remote Config       │
          └──────────────────────────────────────┘
                   ↑                      ↑
                   │                      │
       iOS Native  │                      │ Android Native
 ┌─────────────────┴───┐           ┌──────┴────────────────┐
 │ SwiftUI / Swift 6   │           │ Compose / Kotlin      │
 │ CoreLocation        │           │ Fused Location       │
 │ CoreMotion          │           │ Activity Recognition │
 │ WidgetKit           │           │ Glance Widget        │
 │ StoreKit            │           │ Play Billing         │
 │ SwiftData           │           │ Room/DataStore       │
 └─────────────────────┘           └───────────────────────┘
                   \                    /
                    \                  /
                     └── Firebase ────┘
```

## 2. Why Not Shared Runtime v1
- 핵심 native APIs have different lifecycle semantics.
- background execution abstraction can hide critical OS behavior.
- widgets are separate platform processes/components anyway.
- billing/referral redemption differs materially by store.
- two P0 feasibility tracks need independent instrumentation.

KMP may be reconsidered post-v1 for pure-domain utilities only after stable parity fixtures exist.

## 3. Common Domain Vocabulary
Both clients must implement:
- DetectionState
- ParkingEvidenceType
- ParkingCandidate
- ParkingSession
- ConfidenceBucket
- FloorValue
- EntitlementState
- ReferralRewardState

No platform SDK types cross the domain boundary.

## 4. iOS Layers
```text
SwiftUI View
 -> Feature Store/ViewModel
 -> UseCase
 -> Domain Protocol
 -> Adapter
    CoreLocation/CoreMotion/SwiftData/StoreKit/Firebase/AdMob
```
Shared mutable detector state protected by actor.

## 5. Android Layers
```text
Compose Screen
 -> ViewModel
 -> UseCase
 -> Domain Interface
 -> Adapter
    ActivityRecognition/FusedLocation/Room/Billing/Firebase/AdMob
```
Use:
- Kotlin coroutines
- StateFlow/SharedFlow
- structured concurrency
- repository interfaces
- lifecycle-aware collection

Avoid:
- global mutable object state
- business logic in BroadcastReceiver/Activity
- long work directly inside receiver callbacks

## 6. Android Modules Recommended
```text
:app
:core:model
:core:domain
:core:data
:core:designsystem
:feature:home
:feature:parking
:feature:history
:feature:subscription
:feature:referral
:feature:settings
:platform:detection
:platform:widget
```
For a small solo codebase these may begin as packages, but dependency direction must remain explicit.

## 7. Shared Backend
Single logical Firebase backend supports both platforms.
Firestore account identifies platform-linked store entitlements separately.

```text
accounts/{accountId}
  referralCode
  rewardBalanceDays

storeEntitlements/{accountId}/ios
storeEntitlements/{accountId}/android

referrals/{id}
rewardLedger/{id}
```

Never infer one store subscription from the other without explicit product policy.

## 8. Account Model
Default: anonymous Firebase session.
Internal stable `accountId` distinct from Firebase UID.

Paid recovery:
- iOS: originalTransactionId/appAccountToken mapping
- Android: purchaseToken/order/product mapping and backend account association

Free-user referral credit after uninstall may not be recoverable without optional account-linking; document this limitation before launch.

## 9. Source of Truth
| Domain | iOS | Android | Backend |
|---|---|---|---|
| active parking | App Group snapshot | DataStore/atomic local snapshot | none |
| completed history | SwiftData | Room | none |
| photo | app filesystem | app-private filesystem | none |
| subscription | verified StoreKit | verified Play purchase | entitlement mirror |
| referral | cache | cache | Firestore ledger |
| widget | active snapshot projection | local snapshot projection | none |

## 10. Detection Configuration
Remote Config keys are common names with client clamps.
Example:
- `detector.drivingMinSeconds`
- `detector.drivingMinMeters`
- `detector.reliableAccuracyMeters`
- `detector.highConfidence`
- `detector.mediumConfidence`
- `detector.walkingWindowSeconds`
- `detector.autoEndDrivingSeconds`

Platform overrides allowed:
- suffix `.ios`
- suffix `.android`
Generic value used if override missing.

## 11. Failure Isolation
Firebase, ads, billing, widget failures cannot block local parking storage.
Subscription verification failure should degrade monetization state without deleting parking records.

## 12. Cross-platform Contract Testing
`platform-tests/*.json` contains sensor event sequences using normalized domain events.
Each platform runs fixtures in unit tests.
Expected:
- final state
- candidate status
- confidence bucket
- reason codes

This is the primary parity mechanism.

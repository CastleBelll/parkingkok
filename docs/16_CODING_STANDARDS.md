# 16. Coding Standards & Repository Conventions

## 1. Swift
- Swift 6 mode
- value types for domain
- actor for shared mutable services
- UI `@MainActor`
- no force unwrap/try! in production except provably generated invariant
- SDK delegates mapped at adapter boundary

## 2. Kotlin
- explicit nullability; avoid `!!` in production
- coroutines structured concurrency
- ViewModel owns UI scopes; repositories do not create global unmanaged scopes
- StateFlow for state, SharedFlow/channel for one-shot events where justified
- business logic not in Activity/Composable/BroadcastReceiver
- receiver/service delegates quickly to domain/application layer
- prefer immutable data classes
- Room writes transactional when multi-step consistency matters

## 3. Cross-platform Naming
Common conceptual names should align:
- ParkingSession
- ParkingCandidate
- DetectionState
- DetectionEffect
- ConfidenceBucket
- EntitlementState
- ReferralReward

Exact language casing differs, semantics do not.

## 4. Errors
SDK-specific errors mapped to domain errors.
Examples:
- ParkingError.permissionDenied
- ParkingError.locationUnavailable
- SubscriptionError.verificationFailed
- ReferralError.alreadyReferred

## 5. UI
### SwiftUI
No side-effectful network/storage work inside `body`.
Typed navigation.

### Compose
Composable functions should remain side-effect controlled.
Use lifecycle-aware collection and ViewModel intents.
Do not launch business network work directly from arbitrary recompositions.

## 6. Async Ownership
Every long-lived listener has clear owner/cancellation.
- iOS location/store transaction tasks
- Android transition/location collectors/billing connection

## 7. Logging
- iOS OSLog
- Android structured Log wrapper/Timber only if dependency approved; release privacy scrubbing required
- backend structured Cloud Logging

No exact coordinates.

## 8. Tests
Every detector/referral bug adds regression test.
Common detector fixture added when bug is semantic rather than SDK-specific.
Inject clock; avoid real sleeps.

## 9. TypeScript
- strict
- payload schema validation
- no `any` business paths
- idempotent mutation
- Node 22

## 10. Dependency Policy
Prefer Apple/AndroidX/Google/Firebase official SDKs.
Third-party dependency requires maintenance/license/privacy justification.

Android versions managed centrally (version catalog recommended).
iOS SPM dependency versions pinned intentionally.

## 11. Git / PR
PR includes:
- What/Why
- platform(s)
- tests
- parity impact
- privacy impact
- migration impact

Example commits:
- `feat(android): register vehicle transition detector`
- `feat(ios): add interactive floor widget`
- `fix(backend): dedupe cross-store referral conversion`
- `test(detector): add tunnel fixture`

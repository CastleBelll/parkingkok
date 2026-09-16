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

### Version pinning
동적 버전 금지. 아래 표기는 어느 생태계에서도 사용하지 않는다.
- Gradle: `latest.release`, `1.2.+`, `[1.0,2.0)`
- SPM: branch/`from:` 없는 무제한 범위
- npm: `latest`, `*`, `x`

플랫폼별 규칙:
- Android: 모든 버전을 `gradle/libs.versions.toml` version catalog에 정확히 고정한다.
- iOS: SPM 의존성은 `exact` 또는 `upToNextMinor`로 의도적으로 고정하고
  `Package.resolved`를 커밋한다.
- backend(npm): `package-lock.json`을 커밋하고 CI는 `npm ci`만 사용한다.
  caret 범위는 lockfile이 있는 한 허용하지만, 누군가 `npm install`을 돌리면
  조용히 드리프트하므로 런타임 SDK(`firebase-admin`, `firebase-functions`)는
  정확히 고정한다.

### Runtime pinning
런타임 버전은 문서로만 적지 않고 기계적으로 강제한다.
- `.nvmrc` = 22, `package.json` `engines.node` = "22"
- `backend/.npmrc`에 `engine-strict=true` — 없으면 잘못된 Node에서도
  `npm ci`/`build`/`test`가 경고 없이 통과한다. Cloud Functions 2nd gen
  배포 런타임은 22이므로 로컬/CI가 그와 어긋나면 배포 시점에야 드러난다.
- CI는 `.nvmrc`를 읽어 Node를 고정한다.

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

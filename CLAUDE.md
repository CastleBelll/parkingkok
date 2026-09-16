# CLAUDE.md — 주차콕 iOS + Android 개발 지침

## Document Priority
1. `docs/00_CORE_RULES.md`
2. `docs/01_PRODUCT_REQUIREMENTS.md`
3. `docs/03_SYSTEM_ARCHITECTURE.md`
4. 현재 작업 플랫폼 문서 (`04_IOS_IMPLEMENTATION.md` 또는 `04_ANDROID_IMPLEMENTATION.md`)
5. `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md`
6. `docs/05_PARKING_DETECTION_ENGINE.md`
7. 현재 기능 관련 문서
8. `docs/10_DESIGN_UX_SPEC.md` + `docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md` (UI 관련 작업 시 필수)
9. `docs/14_ENGINEERING_BACKLOG.md`
10. ADR

## Product Goal
주차콕은 사용자가 차량을 주차한 상황을 스마트하게 감지하고, 마지막으로 신뢰 가능한 위치와 주차 시각을 로컬에 기록하며, 사용자는 층/구역 정보만 최소 입력하는 iOS/Android 생활 유틸리티다.

## Platform Strategy
- iOS: SwiftUI + Swift 6, iOS 18+
- Android: Kotlin + Jetpack Compose, minSdk 29+, targetSdk 36+
- Flutter 사용 금지(v1 기준).
- KMP/공유 UI 프레임워크 도입 금지(v1 기준).
- 감지 엔진의 의미론은 문서/JSON test vectors로 동기화하고 각 플랫폼에서 네이티브 구현한다.

## Hard Constraints
- 위치 좌표/주차 사진/층/구역/spot memo를 Firebase에 저장하지 않는다.
- Firebase는 account/subscription/referral/reward/config/aggregate analytics만 담당한다.
- 24시간 continuous high accuracy location 금지.
- 자동 감지 알고리즘은 플랫폼별 `ParkingDetectionEngine` 하나에 집중한다.
- Android에서 WorkManager를 실시간 주차 감지 엔진 대용으로 사용하지 않는다.
- Android foreground service를 상시 실행하지 않는다.
- StoreKit/Play Billing 검증 없이 Plus server entitlement를 부여하지 않는다.
- Cloud Function 서버 소유 필드는 클라이언트 직접 쓰기 금지.
- UI 가격 문자열 하드코딩 금지. 각 Store가 반환한 localized price 표시.
- 추천 reward는 idempotent ledger.
- 광고는 free only; active parking confirmation/detail 핵심 UX에 금지.
- interstitial/app-open/rewarded ads는 MVP 금지.
- dev/QA에서 실광고 ID 금지.
- 좌표/정확한 이동경로/사진경로를 log/analytics/crash custom field에 기록 금지.
- 권한 거부는 앱 전체 failure가 아님. Manual parking은 항상 가능해야 한다.

## Cross-platform Behavioral Parity
동일 JSON test vector 입력에서 두 플랫폼 engine의 결과가 아래 필드 기준으로 같아야 한다.
- final state
- candidate created 여부
- confidence bucket
- reason codes
- active parking transition

OS timestamp jitter/accuracy 차이 때문에 raw score는 tolerance를 둘 수 있으나, product outcome은 동일해야 한다.

## Quality Gate
PR 완료 조건:
- 플랫폼 빌드 성공
- formatter/lint 성공
- domain logic unit test
- 공통 detection fixture test
- permission failure path
- background/process death path(관련 기능일 때)
- subscription/referral failure path(관련 기능일 때)
- privacy impact 검토
- 새 backend mutation은 auth/App Check/idempotency 검토

## Development Order
UI를 먼저 완성하지 않는다.

### iOS P0
1. low-power location trigger
2. motion history/activity transition 확인
3. bounded driving location session
4. lastReliableLocation
5. candidate notification
6. battery measurement

### Android P0
1. ACTIVITY_RECOGNITION permission + Transition API
2. IN_VEHICLE ENTER/EXIT + WALKING ENTER 실제 기기 수신
3. Fused Location bounded driving capture
4. background location permission UX
5. process death/reboot registration recovery
6. candidate notification
7. battery measurement

두 P0 모두 통과해야 cross-platform v1 scope를 고정한다.


## RELEASE AUTOMATION

Store delivery is part of the product, not a manual afterthought.
Before changing signing, versions, CI, Firebase deployment, App Store Connect, or Google Play delivery, read `docs/18_CI_CD_AUTOMATED_RELEASE.md`.
Never commit store credentials, signing private keys, `.p8`, `.jks`, or service-account JSON.
`main` deploys to internal testing only. Production store submission is triggered by a validated semantic-version tag (`vX.Y.Z`).

## Visual Reference Rule
When building or revising UI, consult the packaged design screenshots in `design-references/` and the mapping in `docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md`.
These images are binding visual references for tone, hierarchy, density, and feature grouping.
Do not radically redesign the app into a different visual language without updating the design spec.

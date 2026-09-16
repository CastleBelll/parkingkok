# 주차콕 Product Engineering Pack v4 — iOS + Android

기준일: 2026-09-16

이 문서 세트는 **주차콕**을 iOS와 Android에서 동시에 개발·출시하기 위한 제품/기술 기준서다. 핵심 원칙은 UI 코드를 억지로 공유하지 않고, 각 OS의 백그라운드/센서/위젯/결제 생태계에 맞춰 네이티브로 구현하되 **제품 규칙·감지 상태 머신·Firebase 백엔드·API 계약·테스트 벡터를 공통 기준으로 공유**하는 것이다.

## 권장 기술 스택

### iOS
- Swift 6 language mode
- SwiftUI
- deployment target: iOS 18.0+
- Core Location / Core Motion / UserNotifications
- WidgetKit + App Intents
- SwiftData + App Group
- StoreKit 2
- Firebase iOS SDK
- AdMob iOS SDK

### Android
- Kotlin
- Jetpack Compose
- minSdk 29 (Android 10)+
- targetSdk 36 (Android 16) — 2026-08-31 이후 Google Play 신규/업데이트 요구사항
- Activity Recognition Transition API
- Fused Location Provider
- Coroutines / Flow
- Room + DataStore
- Jetpack Glance App Widget
- Google Play Billing Library 9.1.x 계열(현재 공식 릴리스 9.1.0, 빌드 시 최신 호환 안정 버전 pin)
- Firebase Android SDK + App Check Play Integrity
- AdMob Android SDK

### Backend
- Firebase Authentication (anonymous)
- Firestore
- Cloud Functions 2nd gen / Node.js 22 + TypeScript
- App Check
  - iOS: App Attest
  - Android: Play Integrity
- Remote Config
- Crashlytics(좌표/민감정보 제외)
- Google Cloud Pub/Sub: Google Play RTDN

## 왜 Flutter 단일 코드베이스가 아닌가

주차콕의 핵심은 일반 UI가 아니라 다음 OS 네이티브 기능이다.
- 백그라운드 위치
- 물리 활동 인식
- 프로세스 재시작/OS wake-up
- 잠금화면/홈 위젯 인터랙션
- 앱스토어/플레이스토어 구독
- CarPlay/Android Auto 계열 보조 신호

Flutter로도 가능하지만, 이 핵심 기능들은 결국 iOS/Android 네이티브 구현과 extension/service가 필요하다. 따라서 v1은 **SwiftUI + Kotlin/Compose 듀얼 네이티브**가 디버깅·배터리·권한·스토어 심사 리스크가 더 낮다.

## 무엇을 공유하는가

코드는 최소 공유하고 아래를 공유한다.
1. Firebase backend
2. Firestore schema
3. Cloud Functions
4. API contract
5. 감지 상태 머신 정의
6. confidence scoring 규칙
7. Remote Config key/value bounds
8. analytics event schema
9. JSON detection test vectors
10. UI design tokens / copy
11. subscription/referral business rules


## Visual Design References

초기 제품 방향을 고정하기 위해 실제 생성된 주차콕 UI mockup 5종을 패키지에 포함한다.

- `design-references/01-home-main.png`
- `design-references/02-auto-detection-confirmation.png`
- `design-references/03-parking-detail.png`
- `design-references/04-history-list.png`
- `design-references/05-settings.png`

이 이미지들은 visual direction reference이며, 상세 기준은 `docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md`를 따른다.

## 읽는 순서

1. `docs/00_CORE_RULES.md`
2. `docs/01_PRODUCT_REQUIREMENTS.md`
3. `docs/02_PRODUCT_SCOPE_AND_FLOWS.md`
4. `docs/03_SYSTEM_ARCHITECTURE.md`
5. `docs/04_IOS_IMPLEMENTATION.md`
6. `docs/04_ANDROID_IMPLEMENTATION.md`
7. `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md`
8. `docs/05_PARKING_DETECTION_ENGINE.md`
9. `docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md`
10. `docs/07_FIREBASE_BACKEND.md`
11. `docs/08_SUBSCRIPTION_REFERRAL_ADS.md`
12. `docs/09_SECURITY_PRIVACY_COMPLIANCE.md`
13. `docs/10_DESIGN_UX_SPEC.md`
14. `docs/11_QA_TEST_STRATEGY.md`
15. `docs/12_RELEASE_OPERATIONS.md`
16. `docs/13_APP_STORE_REVIEW_CHECKLIST.md`
17. `docs/13_PLAY_STORE_REVIEW_CHECKLIST.md`
18. `docs/14_ENGINEERING_BACKLOG.md`
19. `docs/15_API_CONTRACTS_AND_EVENTS.md`
20. `docs/16_CODING_STANDARDS.md`
21. `docs/17_OBSERVABILITY_ANALYTICS.md`
22. `docs/18_CI_CD_AUTOMATED_RELEASE.md`
23. `docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md`
24. `adr/*`

## 절대 원칙

1. 주차 GPS 좌표·층·구역·사진·메모를 Firebase에 올리지 않는다.
2. 주차 핵심 기능은 인터넷 없이 작동한다.
3. iOS와 Android 모두 24시간 고정밀 GPS를 사용하지 않는다.
4. `GPS loss == parking`으로 판단하지 않는다.
5. `IN_VEHICLE/automotive -> WALKING`은 강한 신호지만 자가용임을 보장하지 않으므로 단독 확정하지 않는다.
6. 플랫폼별 센서 이벤트를 공통 DomainEvent로 정규화한 뒤 동일한 상태 머신 의미론을 사용한다.
7. 플랫폼별 구현이 다르더라도 사용자에게 보이는 결과 의미는 동일해야 한다.
8. 구독 entitlement는 Store + backend verification을 기준으로 한다.
9. 추천 보상은 서버 idempotent ledger로만 지급한다.
10. 무료 사용자의 핵심 주차 확인 흐름을 광고로 막지 않는다.

## 2026 공식 기준

- Google Play: 2026-08-31 이후 신규 앱/업데이트 target Android 16(API 36)+
- Android Activity Recognition Transition API는 IN_VEHICLE/WALKING 전환을 지원하며 Google은 주차 위치 저장을 공식 예시로 사용
- Android background location은 앱 핵심 기능일 때만 요청해야 하며 Play 심사 대상
- Android location foreground service는 background에서 시작 시 ACCESS_BACKGROUND_LOCATION 등 제약 존재
- Play Billing 9.1.0 공식 릴리스: 2026-06-18
- Google Play 구독 보상 연장: `purchases.subscriptionsv2.defer`
- RTDN은 상태변경 알림일 뿐 전체 상태가 아니므로 수신 후 Developer API로 재조회
- Firebase App Check Android: Play Integrity

공식 URL 목록은 `SOURCES.md` 참고.

## Automated Store Delivery

주차콕은 최종적으로 App Store와 Google Play까지 CI/CD로 자동 배포한다.

- PR: iOS/Android 자동 테스트
- `main`: TestFlight Internal + Play Internal 자동 업로드
- `vX.Y.Z`: App Review + Google Play production 자동 제출
- 승인 후: Apple phased release / Google Play staged rollout

상세: `docs/18_CI_CD_AUTOMATED_RELEASE.md`

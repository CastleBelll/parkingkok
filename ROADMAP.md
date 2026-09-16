# 주차콕 개발 로드맵 (Orca Orchestration)

작성일: 2026-09-16
기준 문서: `parkingkok-product-pack-v4/` (docs 24종 + ADR 8종 + fixtures 2종)

---

## 0. 실행 원칙

1. **게이트 통과 없이 다음 Task 없음.** 모든 Task는 `개발 → 독립 검증 → 게이트` 순서.
2. **검증은 구현자가 하지 않는다.** dev worker와 QA worker를 분리된 worktree에 각각 배치한다.
   (`docs/11_QA_TEST_STRATEGY.md` §14: "review checklists passed by non-implementer")
3. **UI를 먼저 만들지 않는다.** (`CLAUDE.md` Development Order)
4. **게이트 실패 시 되돌아간다.** 다음 milestone으로 넘어가지 않는다.
5. 좌표/사진/메모는 로컬 only. 모든 게이트에 privacy 검증 포함.

### 오케스트레이션 토폴로지

```
Coordinator (이 세션)
├── Run: parkingkok-M{n}
│   ├── Task A → Dispatch → dev worker   (worktree: feat/ios-xxx)
│   ├── Task B → Dispatch → dev worker   (worktree: feat/android-xxx)  [병렬]
│   └── Task V → Dispatch → QA worker    (worktree: qa/M{n})  [A,B 의존]
└── Gate 판정 → 다음 Run
```

- 독립 작업은 **병렬 wave**로 던진다. 체인은 3~4단계 이하로 유지.
- iOS/Android는 M0~M4까지 대부분 병렬 가능.
- QA worker는 dev worker의 `worker_done` 정착 후 dispatch.

---

## 1. 현재 환경 실사 결과

| 항목 | 상태 | 조치 |
|---|---|---|
| Xcode 26.6 / Swift 6.3.3 | ✅ | 그대로 사용 (spec: Xcode 26+ 요구 충족) |
| iOS Simulator 26.2~26.5 | ✅ | deployment target 18.0 빌드 가능 |
| 실기 iPhone (26.6) | ⚠️ 등록됐으나 현재 미연결 | P-1에서 연결 필요 |
| **Android SDK** | ❌ **없음** | P-1에서 설치 |
| **JDK** | ❌ **없음** | P-1에서 설치 (Temurin 21) |
| **adb / gradle** | ❌ **없음** | Android SDK와 함께 설치 |
| Node v24.13.1 | ⚠️ spec은 Node 22 | nvm으로 22 고정 (`.nvmrc`) |
| Firebase CLI 15.8.0 | ✅ | |
| nvm | ✅ | |
| **git 저장소** | ❌ **없음** | P-1에서 `git init` (worktree 전제조건) |
| SwiftLint/SwiftFormat | ❌ 없음 | P-1에서 설치 |
| Homebrew | ❌ 없음 | 선택 — 없이도 전부 설치 가능 |

> **Android 트랙은 현재 코드 한 줄도 빌드 불가.** P-1이 최우선.

---

## Phase -1 — Foundation (게이트: G-1)

병렬 3-wave. 코드 작성 전 단계.

### T-1.1 저장소 부트스트랩
- `/Users/sjkim/dev/parkingkok` 에 `git init`
- `parkingkok-product-pack-v4/` 내용을 repo root로 승격
  (pack의 `CLAUDE.md`, `.github/workflows/`, `platform-tests/`는 root에 있어야 동작)
- 최종 구조:
  ```
  parkingkok/
    CLAUDE.md  README.md  SOURCES.md
    docs/  adr/  design-references/
    platform-tests/          # 공통 fixture (parity의 단일 진실)
    .github/workflows/
    ios/                     # Xcode project
    android/                 # Gradle project
    backend/                 # Firebase Functions (TS, Node 22)
    .gitignore  .nvmrc
  ```
- `.gitignore`: `.DS_Store`, `xcuserdata`, `build/`, `.gradle/`, `local.properties`,
  `node_modules/`, `GoogleService-Info.plist`, `google-services.json`, `*.p8`, `*.jks`, `.env`

### T-1.2 Android 툴체인 (병렬)
- Temurin JDK 21 (tar.gz, sudo 불필요) → `~/Library/Java/`
- Android commandline-tools → `~/Library/Android/sdk`
- `sdkmanager`로 `platform-tools`, `platforms;android-36`, `build-tools;36.x`, `cmdline-tools;latest`
- `ANDROID_HOME` / `PATH` / `JAVA_HOME` → `~/.zshrc`
- Gradle은 wrapper 사용 (별도 설치 불필요)

### T-1.3 iOS 툴체인 (병렬)
- SwiftLint / SwiftFormat 바이너리 설치 (Homebrew 없이 릴리스 바이너리)
- `.swiftlint.yml` / `.swiftformat` — `docs/16_CODING_STANDARDS.md` 기준, high-signal 룰만

### T-1.4 백엔드 툴체인 (병렬)
- `.nvmrc` = 22, `nvm install 22`
- `backend/` TypeScript strict 스캐폴드 (아직 배포 안 함)

### 🚦 Gate G-1
- [ ] `git log` 존재, worktree 생성 테스트 통과
- [ ] `xcodebuild -version` + 빈 iOS 프로젝트 시뮬레이터 빌드 성공
- [ ] `sdkmanager --list` 동작 + 빈 Compose 프로젝트 `./gradlew assembleDebug` 성공
- [ ] `adb devices` 동작
- [ ] `node -v` = v22.x
- [ ] 위 4개를 **QA worker가 clean worktree에서 재현**

---

## Phase 0 — Feasibility (M0A/M0B) · 가장 중요한 단계

> `docs/14_ENGINEERING_BACKLOG.md` Gate M0: "한쪽이 현저히 불안정하면 UI로 덮지 말고
> 플랫폼 스코프나 detector 전략을 조정한다."

여기서 제품의 성패가 갈립니다. **실기기 2대 필수.**

### M0A — iOS Feasibility (병렬 트랙)
1. Swift 6 / iOS 18 스켈레톤 + `.xcconfig` (DEV/STAGING/PROD)
2. 위치·모션 권한 플로우 (Always는 Smart Detection opt-in 이후 컨텍스트 요청)
3. `startMonitoringSignificantLocationChanges()` 저전력 트리거
4. `CMMotionActivityManager.queryActivityStarting` 모션 히스토리 복원
5. `CLLocationUpdate.liveUpdates(.automotiveNavigation)` + `CLBackgroundActivitySession` bounded 세션
6. `lastReliableLocation` 선택 로직 (정확도 ≤35m, 신선도 ≤20s)
7. 백그라운드 candidate 로컬 알림 (`PARKING_CANDIDATE` 카테고리 + 텍스트 액션)
8. **실주행 20회 이상** + 배터리 baseline (Instruments)

### M0B — Android Feasibility (병렬 트랙)
1. Kotlin/Compose 스켈레톤, minSdk 29 / targetSdk 36, version catalog
2. `ACTIVITY_RECOGNITION` 런타임 권한
3. Activity Transition API: IN_VEHICLE ENTER/EXIT, WALKING ENTER **실기기 수신 확인**
4. Fused Location bounded 캡처
5. background location 권한 UX (첫 실행 금지, 단계적 요청)
6. **process death / reboot 재등록 복구** (BOOT_COMPLETED, MY_PACKAGE_REPLACED)
7. 화면 꺼진 상태 candidate 알림
8. **FGS 없이 동작하는지 검증** ← spec이 명시적으로 P0에서 확인하라고 요구
9. **실주행 20회 이상** + 배터리 baseline

### 🚦 Gate M0 (수동 필드 데이터 필요 — 자동화 불가)
- [ ] 양 플랫폼 실주행 20+ 세션, candidate 생성률 측정치 제출
- [ ] 택시/버스 false-positive 데이터셋 수집
- [ ] 배터리 baseline (앱 비활성 vs Smart Detection): idle 8h / 1h 주행
- [ ] Android: 상시 FGS 없이 성립하는지 판정
- [ ] 좌표가 로그/analytics에 없는지 검사
- [ ] **판정: 양쪽 통과 → M1 / 한쪽 불안정 → 스코프·전략 조정 (UI로 덮지 않음)**

---

## Phase 1 — Common Contract (M1)

detection 엔진의 **의미론 단일화**. 여기가 parity의 기반.

1. `platform-tests/*.json` fixture 스키마 확정 (현재 2개 → **10개로 확장**)
   - spec §17 필수 목록: 지하주차 / 신호대기 / 주유소 / 택시 / 버스 / 터널 /
     프로세스 사망 / 권한 회수 / 저전력 / 위젯 동시편집
2. Swift fixture runner (XCTest 또는 Swift Testing — 혼용 금지)
3. Kotlin fixture runner (JUnit)
4. DomainEvent / DetectionState / ReasonCode / ConfidenceBucket 양 언어 구현
5. 결정론적 state machine (clock 주입, 실제 sleep 금지)

### 🚦 Gate M1
- [ ] 10개 fixture가 Swift/Kotlin에서 **동일 결과** (finalState / candidate / bucket / reasonCodes)
- [ ] CI에서 양 플랫폼 fixture job 통과
- [ ] QA worker가 fixture를 1개 추가 작성해도 양쪽이 동일하게 동작

---

## Phase 2 — Local MVP (M2)

1. 홈 / 수동 저장 / active session / 히스토리 / 지도 / 사진 / 설정
2. iOS: SwiftData `SchemaV1` + `SchemaMigrationPlan` (v1부터 준비)
3. Android: Room + DataStore, active snapshot은 Room 소형 테이블
4. 사진: 장변 ~1600px 다운샘플, app-private, atomic write
5. 지도 라벨은 반드시 `마지막으로 확인된 위치`
6. UI는 `design-references/` 5종 + `docs/19` 매핑 준수

### 🚦 Gate M2
- [ ] 오프라인(기내모드)에서 주차 저장·조회·사진 전부 동작
- [ ] 권한 전부 거부 상태에서도 manual parking 성공
- [ ] 1k/10k 레코드 성능, 사진 메모리
- [ ] 접근성: VoiceOver / TalkBack, 색상 단독 의존 없음
- [ ] 프록시 검사: Firebase로 좌표/사진 전송 0건

---

## Phase 3 — Detection Beta (M3)
엔진 완성, confidence 튜닝, Remote Config clamp, auto-end, negative 시나리오.
### 🚦 Gate M3
- [ ] 필드 100+ 세션 (플랫폼당 40+), 지하/실외/택시/버스
- [ ] false positive 허용치 내, 알림 폭주 없음
- [ ] Remote Config 값이 클램프 밖으로 나가도 앱이 안전

## Phase 4 — Widgets (M4)
iOS WidgetKit + AppIntent / Android Glance + callback.
### 🚦 Gate M4
- [ ] 앱 종료 상태, active 없음, Plus 만료, B1 경계, 빠른 연타, 오프라인

## Phase 5 — Backend (M5)
Firebase 프로젝트, anonymous auth, accountId, App Check(App Attest/Play Integrity),
Firestore rules, Node 22 Functions.
### 🚦 Gate M5
- [ ] Emulator Suite 통과, rules 음성 테스트, 서버 소유 필드 클라이언트 쓰기 차단

## Phase 6 — Billing (M6A iOS / M6B Android) — 병렬
StoreKit 2 / Play Billing 9.1.x + 서버 검증 + RTDN.
### 🚦 Gate M6
- [ ] 구매/취소/보류/갱신/유예/환불/복원 전 시나리오
- [ ] RTDN 중복·순서역전 내성

## Phase 7 — Referral (M7)
idempotent ledger, iOS promo offer / Android `subscriptionsv2.defer`.
### 🚦 Gate M7
- [ ] 자기추천·이중추천·두 초대자 레이스·환불 선행·웹훅 중복
- [ ] **크로스 스토어 이중 보상 0건** (launch blocker)

## Phase 8 — Ads (M8)
UMP, 테스트 ID만, 배너 only, Plus 완전 차단.
### 🚦 Gate M8
- [ ] 핵심 주차 플로우에 광고 0, Plus는 ad request 자체가 없음

## Phase 9 — Release Hardening (M9)
디바이스 매트릭스, 배터리, 접근성, privacy label / Data Safety, 스토어 체크리스트.
### 🚦 Gate M9 (Release Exit)
- [ ] P0/P1 0건
- [ ] `docs/10_LAUNCH_BLOCKERS` 7개 항목 전부 해소
- [ ] 스토어 체크리스트를 **비구현자**가 통과 처리

---

## 2. 사용자 확인/제공 필요 항목

| # | 항목 | 필요 시점 | 비고 |
|---|---|---|---|
| A | **실기 iPhone 연결** | P-1 끝 ~ M0A | 현재 오프라인. 케이블 연결 + 신뢰 |
| B | **실기 Android 연결** | M0B | USB 디버깅 ON. Samsung/Pixel 권장 |
| C | **Apple Developer Program 가입 여부** | M0A | 미가입 시 배경 위치 실기 테스트 제약 |
| D | **Bundle ID / package name** | P-1 | 기본 제안: `com.parkingkok.app` |
| E | **GitHub 원격 저장소** | M1 (CI) | private repo 권장 |
| F | Firebase 프로젝트 | M5 | Google 계정 |
| G | 스토어 계정 (App Store Connect / Play Console) | M9 | |

> B~G는 해당 Phase 직전에 다시 요청합니다. **지금 당장 필요한 것은 D뿐**이며,
> A는 P-1 완료 시점에 요청합니다.

---

## 3. 즉시 시작 범위

Phase -1 (T-1.1 ~ T-1.4)을 병렬 wave로 dispatch → Gate G-1 검증 → 보고.
Phase 0 진입 전 실기기 연결을 요청합니다.

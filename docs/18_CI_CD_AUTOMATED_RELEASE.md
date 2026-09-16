# 18. CI/CD 및 App Store / Play Store 자동 배포

## 1. 목표

주차콕은 iOS와 Android를 동시에 운영하므로, 릴리스는 사람의 로컬 Mac/PC 상태에 의존하지 않는다.

목표 파이프라인:

```text
Pull Request
  -> lint / unit test / domain fixture test / build verification

main merge
  -> iOS archive/sign/upload -> TestFlight Internal
  -> Android AAB/sign/upload -> Play Internal

release tag (vX.Y.Z)
  -> full release gate
  -> iOS production build -> App Store Connect -> App Review 자동 제출
  -> Android production AAB -> Google Play production staged rollout

store review approved
  -> iOS automatic release + phased release
  -> Android staged rollout progression
```

핵심 원칙:

- `main` 머지를 운영 스토어 즉시 배포와 연결하지 않는다.
- 내부 테스트 배포는 완전 자동화한다.
- 운영 심사 제출은 signed semantic version tag를 단일 release trigger로 사용한다.
- 운영 배포는 양 스토어 모두 점진 배포를 기본값으로 한다.
- 스토어 심사는 자동화할 수 있지만 Apple/Google의 검토 자체를 우회할 수는 없다.
- 모든 credential은 CI secret store에서만 사용한다.

---

## 2. CI 플랫폼

권장: GitHub Actions.

### iOS

- Runner: `macos-26` 또는 프로젝트에서 검증 후 고정한 stable macOS runner
- Xcode: 프로젝트에서 검증한 최신 stable 버전을 pin
- 2026 배포 기준을 만족하는 iOS SDK 사용

### Android

- Runner: `ubuntu-24.04` 또는 팀에서 pin한 안정 Linux runner
- JDK: 현재 Android Gradle Plugin 요구 버전에 맞춰 pin
- Gradle Wrapper 사용
- `targetSdk >= 36`

`latest` 의존성을 최소화하고, production workflow에서는 runner/Xcode/JDK/Gradle 버전을 명시적으로 pin한다.

---

## 3. Git branching / release model

```text
feature/*
  -> PR
  -> main
       -> internal distributions

main
  -> tag v1.2.0
       -> production release pipeline
```

버전 규칙:

- marketing version: SemVer `MAJOR.MINOR.PATCH`
- iOS build number: CI run 기반 monotonic integer
- Android versionCode: CI에서 monotonic integer

예:

```text
v1.4.2

iOS
CFBundleShortVersionString = 1.4.2
CFBundleVersion = 2409

Android
versionName = 1.4.2
versionCode = 2409
```

운영 태그는 반드시 현재 `main` commit을 가리켜야 한다.

---

## 4. PR CI

모든 PR에서 다음이 통과해야 merge 가능하다.

### Common

- secret scanning
- dependency validation
- detection fixture validation
- backend unit tests

### iOS

- SwiftFormat / SwiftLint 정책 검사
- compile
- unit test
- ParkingDetectionEngine fixture tests
- Widget extension compile

### Android

- ktlint / detekt
- assemble
- unit tests
- ParkingDetectionEngine fixture tests
- lint

스토어 credential은 PR workflow에 노출하지 않는다.

fork PR에는 어떠한 production secret도 제공하지 않는다.

---

## 5. main merge 자동 배포

`main` merge 시 production이 아니라 내부 배포까지 진행한다.

### iOS

```text
main
 -> build/test
 -> archive
 -> sign
 -> upload
 -> TestFlight Internal Testing
```

내부 그룹 예:

- `parkingkok-internal`

외부 TestFlight 그룹은 필요 시 별도 workflow에서 관리한다. 외부 테스터용 빌드는 Beta App Review가 필요할 수 있으므로 internal과 동일하게 취급하지 않는다.

### Android

```text
main
 -> build/test
 -> signed AAB
 -> Play Internal track upload
```

Internal track은 QA용 최신 빌드가 항상 존재하도록 유지한다.

---

## 6. iOS production 자동화

### 인증

App Store Connect API Key 사용.

CI secret 예:

```text
ASC_KEY_ID
ASC_ISSUER_ID
ASC_PRIVATE_KEY_P8
APPLE_TEAM_ID
IOS_MATCH_PASSWORD  # match를 사용할 경우
```

Apple API Key는 CI 전용 key를 생성하고 최소 권한 원칙을 적용한다.

개인 Apple ID/password 기반 자동화보다 API key 기반 인증을 우선한다.

### Signing

권장 2안:

A. fastlane match + private encrypted signing repository
B. CI에서 App Store Connect/Developer signing asset을 명시적으로 관리

주차콕 초기 팀에는 A를 권장한다.

단:

- signing repository access는 production environment에서만 허용
- certificates/profiles rotation runbook 작성
- Widget/App Groups entitlement까지 profile consistency를 검증

### production flow

```text
v1.2.0 tag push
 -> verify tag == main HEAD
 -> full tests
 -> archive Release
 -> export IPA
 -> upload App Store Connect
 -> wait for build processing
 -> attach build to App Store version
 -> sync release notes / metadata
 -> validate required compliance metadata
 -> create review submission
 -> submit to App Review
```

App Store Connect API는 review submission을 생성하고 App Review에 제출하는 작업까지 자동화할 수 있다.

### 승인 후 release

기본 정책:

- Automatically release after approval
- App update: phased release ON

Apple phased release 기본 progression:

```text
Day 1   1%
Day 2   2%
Day 3   5%
Day 4  10%
Day 5  20%
Day 6  50%
Day 7 100%
```

첫 출시(1.0)는 운영상 수동 final release를 선택할 수도 있다. 이후 update부터 automatic + phased release를 권장한다.

---

## 7. Android production 자동화

### 인증

Google Play Developer API service account 사용.

CI credential 예:

```text
GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Google Play Console에서는 service account에 필요한 앱/릴리스 권한만 부여한다.

키스토어는 repository에 commit하지 않는다.

가능하면 Google Play App Signing을 사용하고 CI에는 upload key만 보관한다.

### production flow

```text
v1.2.0 tag push
 -> verify tag == main HEAD
 -> full tests
 -> bundleRelease
 -> sign AAB
 -> upload Play Developer API
 -> production track
 -> staged rollout
```

초기 production rollout 권장값:

```text
10%
```

안정화 후:

```text
10% -> 25% -> 50% -> 100%
```

사용자가 적은 초기 서비스라면 rollout sample이 너무 작아 의미가 없을 수 있으므로 early-stage에서는 25% 또는 100%를 선택할 수 있다.

Play Developer API의 track release에서 `userFraction`과 release status를 통해 staged rollout을 제어한다.

---

## 8. Fastlane 사용 원칙

권장 구조:

```text
fastlane/
  Fastfile
  Appfile
  Matchfile (iOS only, 선택)
```

대표 lane:

```text
ios test
ios beta
ios release

android test
android internal
android release
```

Fastlane은 빌드 시스템 자체가 아니다.

- iOS build: `xcodebuild`
- Android build: Gradle
- fastlane: signing/upload/store automation orchestration

을 담당한다.

스토어 API 변경에 대응하기 위해 Fastlane version을 lock file로 pin한다.

---

## 9. GitHub Actions workflows

권장 파일:

```text
.github/workflows/
  pr-ci.yml
  main-internal.yml
  release.yml
  backend.yml
```

### pr-ci.yml

PR 검증만 수행.

### main-internal.yml

`push: main`

```text
backend test/deploy (조건부)
iOS -> TestFlight Internal
Android -> Play Internal
```

### release.yml

trigger:

```text
push tag v*.*.*
```

production submission 수행.

### backend.yml

Firebase Functions / Firestore Rules / Remote Config 관련 변경을 별도 검증한다.

Functions와 mobile app release를 반드시 같은 cycle로 묶지 않는다.

---

## 10. Production deployment protection

최소 보호 조건:

```text
release tag
AND
main HEAD 일치
AND
all CI green
AND
working tree/repository clean
AND
version not previously released
```

GitHub Environment:

```text
ios-production
android-production
firebase-production
```

으로 secret scope를 분리한다.

GitHub plan/repository visibility에 따라 required reviewer 기능 사용 가능 여부가 다를 수 있으므로, 이를 production safety의 유일한 수단으로 의존하지 않는다.

---

## 11. Store metadata as code

앱 설명 / release notes / 키워드 등은 가능한 repository에서 관리한다.

예:

```text
store/
  ios/
    ko-KR/
      description.txt
      keywords.txt
      release_notes.txt
  android/
    ko-KR/
      title.txt
      short_description.txt
      full_description.txt
      release_notes.txt
```

CI가 이 내용을 스토어에 반영한다.

단, 개인정보/Data Safety/App Privacy 같은 고위험 선언은 자동 덮어쓰기를 최소화하고 별도 review check를 둔다.

---

## 12. Screenshot automation

MVP 이후 적용.

### iOS

XCUITest 기반 deterministic screenshot scenario.

### Android

Compose UI test / emulator screenshot scenario.

샘플 데이터 fixture:

```text
B3
A구역 · 142
1시간 24분째 주차 중
```

를 고정해 앱스토어 이미지가 매번 동일한 데이터로 생성되게 한다.

생성 이미지는 사람이 검토한 뒤 store assets에 merge한다.

---

## 13. Firebase deployment

Firebase도 CI에서 배포한다.

```text
Firestore Rules
Firestore Indexes
Cloud Functions
Remote Config templates
```

환경:

```text
parkingkok-dev
parkingkok-staging
parkingkok-prod
```

production Firebase 변경은 mobile app과 별도로 backwards-compatible하게 배포한다.

Functions API는 최소 한 버전 backward compatibility를 유지한다.

---

## 14. Secrets policy

절대 git commit 금지:

```text
.p8
.jks / .keystore
service-account.json
GoogleService-Info production secrets if team policy requires separation
signing certificates private key
Firebase service account credentials
```

CI log에 secret 출력 금지.

Base64 encoding은 encryption이 아니므로 secret store 바깥에서 보안 수단으로 취급하지 않는다.

퇴사/유출 시 rotation 가능한 runbook을 유지한다.

---

## 15. 자동 롤백에 대한 원칙

모바일 앱은 웹 서버처럼 store binary를 즉시 rollback할 수 없다.

따라서 release 안정성은 다음으로 확보한다.

```text
staged/phased rollout
Remote Config kill switch
Firebase server-side feature flag
backwards-compatible backend
emergency patch release
```

주차 감지 엔진의 위험한 신규 로직은 Remote Config로 disable할 수 있어야 한다.

예:

```text
auto_detection_v2_enabled = false
car_connection_signal_enabled = false
```

즉, 문제가 발생했을 때 스토어 새 버전 심사를 기다리지 않고 핵심 기능을 안전 모드로 전환할 수 있어야 한다.

---

## 16. Release health gate

Production rollout 확대 전 확인:

### iOS

- crash-free sessions
- launch failures
- subscription purchase error
- parking false-positive metric
- backend 5xx

### Android

- Play Android vitals
- crash / ANR
- subscription purchase error
- background permission regression
- parking false-positive metric

중대한 문제가 있으면 rollout을 pause/halt한다.

---

## 17. 권장 자동화 수준

### Phase A — 개발 초기

```text
PR CI
main -> TestFlight Internal + Play Internal
```

### Phase B — Closed beta

```text
release tag -> store build upload
human verifies metadata
human triggers review submission
```

### Phase C — Production 안정화 이후

```text
release tag
 -> build
 -> upload
 -> metadata
 -> review submission
 -> review approval
 -> automatic phased/staged release
```

주차콕의 목표 최종 상태는 Phase C이다.

---

## 18. Definition of Done — Release Automation

다음이 모두 만족되어야 자동 배포 구축 완료로 본다.

```text
[ ] PR에서 iOS/Android CI 실행
[ ] main merge 시 TestFlight Internal 자동 업로드
[ ] main merge 시 Play Internal 자동 업로드
[ ] iOS signing이 local developer machine에 의존하지 않음
[ ] Android signing이 local developer machine에 의존하지 않음
[ ] App Store Connect API Key 기반 인증
[ ] Google Play service account 기반 인증
[ ] vX.Y.Z tag release workflow
[ ] version/build number 자동 계산
[ ] release notes 자동 반영
[ ] App Review 자동 제출
[ ] Play production track 자동 제출
[ ] Apple phased release 설정
[ ] Play staged rollout 설정
[ ] production secrets 분리
[ ] credential rotation 문서화
[ ] Remote Config kill switch
[ ] release failure notification
[ ] release audit log 확인 가능
```

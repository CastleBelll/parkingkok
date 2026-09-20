# CLAUDE.md — 주차핀 iOS + Android 개발 지침

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
주차핀은 사용자가 차량을 주차한 상황을 스마트하게 감지하고, 마지막으로 신뢰 가능한 위치와 주차 시각을 로컬에 기록하며, 사용자는 층/구역 정보만 최소 입력하는 iOS/Android 생활 유틸리티다.

## Platform Strategy
- iOS: SwiftUI + Swift 6, iOS 18+
- Android: Kotlin + Jetpack Compose, minSdk 29+, targetSdk 36+
- Flutter 사용 금지(v1 기준).
- KMP/공유 UI 프레임워크 도입 금지(v1 기준).
- 감지 엔진의 의미론은 문서/JSON test vectors로 동기화하고 각 플랫폼에서 네이티브 구현한다.

## Hard Constraints
- 위치 좌표/주차 사진/층/구역/spot memo를 Firebase에 저장하지 않는다.
  **예외 하나, 2026-09-20:** 공유 주차(`docs/20_SHARED_PARKING.md`). **진행 중인 주차 한
  건**에 한해, **종단간 암호화된 상태로만** 올라간다. 키는 Firestore에 절대 저장하지 않으므로
  서버는 끝까지 읽지 못한다. 기록(history)·사진·트레이스는 여전히 올라가지 않으며, 주차가
  끝나면 서버 문서는 삭제된다. 평문 저장은 이 예외에 포함되지 않는다.
- Firebase는 account/subscription/referral/reward/config/aggregate analytics를 담당하고,
  여기에 위 예외의 **암호문 한 건**이 더해진다.
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

## Product Name
The user-facing brand is **주차핀**. Every string a user can read says 주차핀 — app name,
widget labels, notifications, settings, paywall, store copy ("주차핀 Plus", "주차핀 가족 공유",
"주차핀에 주차 위치가 저장됐어요").

Internal identifiers are **not** renamed and must not be: package names, Bundle IDs, the
Firebase project, class names, directory names, module names all stay `parkingkok`. A PR
that renames an identifier to match the brand is wrong.

## Visual Reference Rule
When building or revising UI, consult the packaged design screenshots in `design-references/`
and the mapping in `docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md`.

Those mocks are binding for **information hierarchy, content, density and feature grouping**.
They are **not** binding for surface treatment: they were drawn with drop shadows, gradient
blooms and a card around every row, and the Design Harness below overrides all three. Where a
mock and the harness disagree about how a surface is *finished*, the harness wins; where they
disagree about what is *on screen and how it is ranked*, the mock wins.

Do not radically redesign the app into a different visual language without updating the design
spec.

## Design Harness

The single goal: 주차핀 must read as **"a small team built this carefully"**, never as
**"an AI generated this"**. `docs/10_DESIGN_UX_SPEC.md` holds the full direction. These are the
rules that are cheap to check and expensive to get wrong, so they live here.

### Forbidden — these are the AI tells
- Purple or violet **in any form**, including in gradients, tints, illustrations and icons
- Purple→blue gradients; neon; any large gradient CTA
- Heavy or multi-pass drop shadows on cards
- Decorative background blobs, glows or washes
- Glassmorphism beyond an OS-native material
- A card wrapped around every row (the "AI dashboard")
- Pill-shaping every element
- Decorative icons or illustrations that carry no meaning
- Paywalls with crowns, sparkles, "BEST VALUE" badges or fake discounts
- Mixing many pastels; giving each button or card its own colour

### Required
- **Separate layers with background, border and spacing — not shadow.** If a shadow is truly
  needed it must be barely perceptible.
- **One emphasised primary CTA per screen.** Everything else is secondary, text or icon.
- **One accent per screen.** Primary Blue carries actions; Mint appears rarely, for state.
  Colour marks state and action — it is not decoration.
- **System fonts and system icons.** SF Pro / SF Symbols on iOS, Roboto / Material Symbols on
  Android. Hierarchy comes from size and weight, and from few weights: Bold hero, Semibold
  title, Regular body and supporting.
- **Radius:** 18–22 major cards, 12–16 buttons, 10–14 small controls.
- **Real density.** Generous spacing, but not so few elements per screen that the app stops
  being useful.
- **Platform-native interaction.** Navigation, switches, dialogs and sheets follow each OS.
  Brand, colour, hierarchy, spacing and feature structure are shared; iOS must not be made to
  look like Android, or the reverse.

### The palette, and nothing beside it
| role | light |
|---|---|
| background | `#F7F8FA` |
| surface | `#FFFFFF` |
| text primary | `#111827` |
| text secondary | `#6B7280` |
| primary | `#2563EB` |
| accent (sparing) | `#14B8A6` |
| divider | `#E5E7EB` |
| danger | `#EF4444` |

Tokens only. A literal colour at a call site is a defect. Adding a colour to the palette is a
spec change, not an implementation detail.

### Screen rules that are easy to violate
- **Home:** the floor (`B3`) is the largest thing on screen. Logo, illustration, recent
  history and ads must never outrank the current parking location.
- **Auto-detection:** the copy is **"주차한 것 같아요"**. Never state detection as settled
  fact. **"주차 아님"** must be easy to find, not buried.
- **Detail:** the map and the text must not compete for attention.
- **History:** a plain list. No charts, no graphs. Detected records carry a small `자동` badge.
- **Settings:** ordinary OS-style sections. Not one big card per setting.
- **Ads:** never push or cover the current location, save, end-parking, map, or the detection
  confirmation.

### Priority when these conflict
readability → hierarchy → speed of use → consistency → restrained brand → prettiness.

# 00. CORE RULES

## Product
- 이름: 주차핀
- 한 줄 정의: **주차를 기억하지 않아도 되게 하는 스마트 주차 기록 앱**
- 핵심 UX: 자동 감지 → 알림에서 최소 확인/층 입력 → 위젯/앱에서 즉시 확인
- iOS/Android 동시 제품으로 설계한다.

## Platform / Toolchain
### iOS
- iOS 18.0+
- SwiftUI
- Swift 6 language mode
- 최신 안정 Xcode pin
- App Store 제출 toolchain은 Apple current requirement 이상 유지

### Android
- minSdk 29+
- targetSdk 36+
- Kotlin + Jetpack Compose
- 최신 안정 Android Studio/AGP/Kotlin 조합을 repository에서 pin
- Google Play 2026-08-31 target API requirement 준수

## Native First
- v1 Flutter 금지.
- v1 KMP shared runtime 금지.
- OS API는 각 플랫폼에서 직접 사용한다.
- 공통 로직 공유는 spec/test vector/backend 수준으로 한다.

## Data
- parking GPS: local only
- floor/zone/spot/memo: local only
- parking photo: local only
- backend: anonymous account/subscription/referral/reward/config only

## Monetization
- Free: core parking + AdMob
- Plus: monthly auto-renew, target ~₩1,500
- Plus: ads removed, unlimited history, interactive widgets, advanced automation
- Referral: first verified paid conversion = inviter 7-day reward credit
- iOS reward application: StoreKit promotional-offer based strategy
- Android reward application: Play Developer API `purchases.subscriptionsv2.defer` where eligible

## Detection
- state machine first, heuristics second.
- GPS loss alone never confirms parking.
- motion transition alone never proves private-car parking.
- CarPlay/Android Auto are optional confidence signals only.
- platform signal providers normalize into common evidence categories.
- detection thresholds remote-configurable only within app-side safe bounds.

## Background / Battery
- no always-on high accuracy GPS.
- Android: no permanent foreground service.
- iOS: no indefinite navigation-grade location session.
- location capture escalates only around suspected driving/parking windows.
- state must survive process death/restart.

## Privacy
- exact location absent from logs/analytics/backend.
- photo metadata not uploaded.
- exports should strip EXIF GPS where practical.
- Ads initialize only after required consent state evaluation.

## Store Review
- subscription must provide ongoing value.
- digital unlock uses platform billing.
- background location must be essential to automatic parking feature and clearly disclosed.
- review assets/test instructions must demonstrate manual fallback and automatic detection rationale.

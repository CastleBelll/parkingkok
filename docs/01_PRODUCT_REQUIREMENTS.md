# 01. Product Requirements Document (PRD) — iOS + Android

## 1. Problem
대형마트, 아파트, 병원, 공항, 복합쇼핑몰 등에서 사용자는 차량의 층/구역/기둥 정보를 잊는다. 기존 방식은 사진/메모/지도 수동 저장이어서 습관화가 어렵다.

주차핀은 **주차 기록을 사용자가 기억해서 실행해야 하는 행동 자체를 줄이는 것**을 해결한다.

## 2. Product Promise
> 차에서 내린 뒤, 주차핀이 먼저 주차 가능성을 감지하고 위치와 시간을 기억한다. 사용자는 필요할 때 층만 빠르게 확인한다.

## 3. Platforms
- iOS 18+
- Android 10(API 29)+
- 기능 의미는 동일하지만 OS capability 차이는 허용한다.

## 4. Goals
### G1 자동 후보
실제 주차 후 reasonable window 내 parking candidate가 생성된다.

### G2 zero-navigation lookup
앱 홈을 열면 현재 주차 위치가 즉시 보인다.

### G3 underground usefulness
지하 GPS를 과장하지 않고 last reliable outdoor/entrance location + floor/zone/photo를 결합한다.

### G4 battery-conscious
고정밀 위치는 bounded session에서만 사용한다.

### G5 local privacy
서버 침해가 발생해도 정확한 주차 좌표/사진은 유출되지 않는다.

### G6 cross-platform parity
동일한 제품 규칙, entitlement, referral, history limit, copy semantics를 유지한다.

## 5. Non-goals — MVP
- indoor navigation
- parking fee payment/calculation
- parking lot map partnerships
- vehicle manufacturer API
- BLE OBD hardware
- family live car sharing
- Apple Watch/Wear OS
- exact parking-stall GPS
- community
- web dashboard
- cloud parking-history sync

## 6. Functional Requirements

### FR-001 Manual Parking
위치 권한 없이도 저장 가능:
- startedAt
- floor
- zone
- spot/memo
- optional photo
위치 사용 가능 시 last reliable/current location 포함.

### FR-002 Smart Candidate
OS-specific signals를 공통 evidence로 정규화해 `ParkingCandidate` 생성.
필드:
- id
- detectedAt
- confidenceBucket
- reasonCodes
- lastReliableLocation(local only)
- expiresAt
- platform

### FR-003 Candidate Confirmation
알림 actions:
- confirm / 층 입력
- 주차 아님
- optional quick floor actions where OS UI permits
확인되지 않은 candidate를 자동 confirmed로 승격하지 않는 것이 기본값.

### FR-004 Active Parking
한 번에 active parking 1개.
새 parking confirmation 시 기존 active record 충돌 정책을 명시적으로 처리.

### FR-005 Floor
- B1...Bn
- 1F...nF
- free text fallback
- numeric parseable floor만 widget +/- 지원

### FR-006 Zone / Spot
- optional
- local only
- 각각 max 40 chars

### FR-007 Photo
- 1 photo/record MVP
- local app-private storage
- long edge target ~1600px
- no Firebase Storage

### FR-008 Map
- iOS MapKit
- Android Google Maps SDK 또는 system maps intent 중 MVP 선택; 기본 권장: 앱 내부 lightweight map이 필요하면 Maps SDK, 아니면 external maps deep link로 시작
- label: `마지막으로 확인된 위치`
- exact car location wording prohibited when underground confidence poor

### FR-009 History
Free: 최근 5건 visible
Plus: local unlimited
Downgrade 시 데이터 삭제하지 않고 older records locked/read-restricted 처리.

### FR-010 Auto End
PARKED 이후 새 driving session이 충분히 확인되면 parking end candidate 생성.
MVP에서는 silent destructive end보다 notification/recoverable end preferred.

### FR-011 Widgets
#### iOS
- WidgetKit
- current floor/zone/elapsed
- Plus interactive +/-
- controls optional

#### Android
- Jetpack Glance App Widget
- current floor/zone/elapsed
- Plus interactive +/- via callback/broadcast/service action

### FR-012 Subscription
- monthly recurring
- target retail ~₩1,500 equivalent per store price tier
- actual localized store price shown
- purchase/restore/manage

### FR-013 Ads
Free only.
- history/settings safe surfaces
- no active confirmation/detail blocking
- MVP interstitial/app-open/rewarded prohibited

### FR-014 Referral
- referral code linked before first paid subscription
- first verified paid conversion only
- self referral prohibited
- one invitee -> one inviter immutable
- one conversion -> 7-day credit
- server ledger only

### FR-015 Settings
- smart detection toggle
- auto-end Plus toggle
- permission statuses
- privacy options
- restore purchase
- invite/reward
- export Plus
- delete local data

## 7. Platform-Specific Capability Notes

### iOS
- Core Motion provides automotive/walking/stationary evidence.
- background wake behavior is OS-controlled and not guaranteed in every circumstance.
- interactive widget action on locked device may require authentication.

### Android
- Activity Recognition Transition API directly supports IN_VEHICLE/WALKING transitions.
- background location requires separate permission/policy justification.
- manufacturer battery optimizations may delay background behavior; app must not instruct users to disable optimizations unless truly necessary and policy-compliant.
- reboot/process death requires transition registration/recovery strategy.

## 8. Non-functional Requirements

### Offline
Parking core, local history, photo, widget must work offline.

### Startup
Network must not block home.

### Reliability
- crash-free target >=99.8%
- active state recoverable after process death

### Battery
field test required on both platforms before launch.

### Accessibility
- iOS Dynamic Type/VoiceOver
- Android font scaling/TalkBack
- minimum touch targets
- state not color-only

## 9. Success Metrics (privacy-safe)
- candidate_generated
- candidate_confirmed
- candidate_rejected
- candidate_timeout
- manual_saved
- auto_end_confirmed/reverted
- detection_confidence_bucket
- platform
- purchase_success
- referral_conversion

Never upload exact coordinates or raw motion trace.

## 10. Launch Blockers
- Android/iOS one platform has severe false positives
- excessive battery use
- background permission policy cannot be justified
- purchase restore broken
- cross-platform referral double reward
- ad shown during critical parking flow
- sensitive parking data found in backend/log payload

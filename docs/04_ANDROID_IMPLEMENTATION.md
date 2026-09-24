# 04. Android Implementation Guide — 2026

## 1. Baseline
- Language: Kotlin
- UI: Jetpack Compose
- minSdk: 29 (Android 10)
- targetSdk: 36 (Android 16)
- compileSdk: >=36, latest stable compatible SDK pinned in CI
- architecture: ViewModel + UseCase + Repository/Adapter
- async: Coroutines + Flow
- persistence: Room + DataStore
- widget: Jetpack Glance
- billing: Google Play Billing Library 9.1.x line (9.1.0 released 2026-06-18; pin exact project version)

Google Play requires new apps/updates to target Android 16(API 36)+ from 2026-08-31.

## 2. Core APIs
### Activity Recognition Transition API
Use Google Play services Activity Recognition Transition API.
Track at minimum:
- IN_VEHICLE ENTER
- IN_VEHICLE EXIT
- WALKING ENTER
- STILL ENTER/EXIT as supporting evidence

Permission:
`android.permission.ACTIVITY_RECOGNITION`
Runtime permission required on Android 10+.

Google's official example explicitly identifies a parking app detecting exit-vehicle then walking to save parking location.

### Fused Location Provider
Use bounded requests around confirmed driving windows.
Do not keep high accuracy request indefinitely.

Recommended conceptual modes:
- IDLE: no continuous high-rate request
- DRIVING_CANDIDATE: coarse/limited confirmation request if needed
- DRIVING: bounded balanced/high accuracy based on speed/quality
- PARKING_TRANSITION: capture last reliable points then stop

## 3. Background Location
If automatic detection requires location when app is not visible:
- request foreground location first
- explain feature benefit in-app
- request background location separately, according to Android version UX
- Play Console background-location declaration/review material required

Do not request background location during first launch before user enables Smart Detection.

## 4. Foreground Service Strategy
A `location` foreground service has strict while-in-use/background start rules.
Do not design v1 as permanent FGS.

Use an FGS only if a bounded active driving capture truly requires it and policy/runtime prerequisites are satisfied.
Manifest requires correct service type and `FOREGROUND_SERVICE_LOCATION`.
Starting a location FGS from background may require ACCESS_BACKGROUND_LOCATION.

P0 must test whether Activity Transition events + bounded location capture work adequately without persistent FGS.

### 4a. The P0 test above was run on 2026-09-20. It fails without an FGS.

A full day of real driving on a Galaxy S21 (Android 15, One UI 7) produced **three location
fixes**, all between 12:32 and 12:42, all 56–100 m. Three bounded sessions started and three
stopped; `deliveryCount` was **0**. Activity transitions were fine throughout —
`transitionEventCount: 20` — so it is location alone that fails.

Then measured directly, one `DRIVING` session at a 15-second interval:

| condition | deliveries in 3 minutes | expected |
|---|---|---|
| app in the foreground | 3 in the first 25 s | ~12 |
| backgrounded, screen off | **0** | ~12 |
| backgrounded, screen off, exempt from battery optimisation | **1** | ~12 |
| backgrounded, screen off, **`location` foreground service** | **45** | ~12 |

The third row is the one that decided the design: **a doze exemption does not lift the
throttle.** Android's background location limit applies to an app with no foreground
service however the subscription was made, and the PendingIntent form — which does survive
process death, as §2 says — does not exempt it.

The consequence was not subtle. With no fix newer than noon, the candidate created at 17:32
inherited the noon one; the home screen read `오후 12:00 · 확인해 주세요` and the confirmation
screen drew a map with `약 17m 이내` beside it. (That second bug is fixed separately, in
docs/05 §5 — but it only ever fired because this one starved it.)

### 4b. `DrivingLocationService`, and why it is not the permanent FGS §4 forbids

One `location` foreground service, started by `FusedLocationSessionRegistrar.request` and
stopped by `remove`. Its lifetime is therefore exactly one bounded Fused Location request's,
which `LocationSessionPlanner` already caps. A parked phone runs no service; an idle one
runs no service. What §4 forbids is a service that stands whether or not anything is
happening, and this is the opposite — if it is ever seen alive while `sessionMode` is
`IDLE`, that is a leak to fix, not the design.

It is `START_NOT_STICKY`: a system restart with no session behind it would be exactly that
leak. Verified on the device: stopping the capture leaves `sessionMode: IDLE`,
`lastSessionStopReason: DESIRED_IDLE` and **no** `ServiceRecord` for it.

**Starting it from the background still needs the exemption.** Android 12+ refuses a
background foreground-service start unless the app qualifies, and battery-optimisation
exemption is the qualifying route available here. So the exemption is still required — not
to lift the throttle, but to be allowed to raise the service that does. `start` is
best-effort and silent on refusal: a throttled session beats none, and that is what the app
had before.

Settings therefore carries it as a fifth permission row, `배터리 사용량 제한 해제`, beside the
four real runtime permissions. It is not one of those, and it is listed with them anyway
because it fails in exactly the same way: without it the app detects nothing, and a user
debugging "왜 감지가 안 되지" has to be able to see it in the same place as the rest.

The row opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` — the system list — rather than
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which is one tap fewer and Play-policy
sensitive (docs/13_PLAY_STORE_REVIEW_CHECKLIST.md). A location-tracking core function is
the kind of thing the policy contemplates, so the direct prompt is available later with a
declaration; it is not worth the review risk before the app has been reviewed once.

## 5. Event Pipeline
```text
PendingIntent transition event
 -> thin BroadcastReceiver
 -> persist raw normalized event quickly
 -> enqueue/launch bounded application work
 -> ParkingDetectionEngine
 -> candidate/result
```
Receiver must finish quickly. No long DB/network/location loops inside `onReceive`.

Use `goAsync()` only for short bounded async processing where justified.

## 6. Re-registration / Process Death / Reboot
Activity transition registrations use PendingIntent and can survive process lifecycle, but app must maintain explicit desired-registration state.
Implement boot/update recovery where platform behavior requires.

Consider receivers for:
- BOOT_COMPLETED
- MY_PACKAGE_REPLACED

On app start:
- reconcile Smart Detection setting
- ensure transition registrations are present
- do not duplicate registration semantics

## 7. Detection Adapter
```kotlin
interface MotionSignalProvider {
    val events: Flow<MotionDomainEvent>
    suspend fun setEnabled(enabled: Boolean)
}

interface LocationSignalProvider {
    val samples: Flow<LocationSample>
    suspend fun startDrivingSession(config: LocationSessionConfig)
    suspend fun stopDrivingSession()
}
```
SDK types must be mapped before entering domain engine.

## 8. Location Reliability
Store a bounded ring buffer in memory + periodic checkpoint.
`lastReliableLocation` selected by:
- accuracy <= configured threshold
- not stale
- plausible speed/distance
- newer reliable sample preferred

Never treat `location == null` or accuracy deterioration alone as parking.

## 9. Notification
Use NotificationCompat.
Create dedicated channels:
- parking_detection
- parking_status

Candidate notification actions:
- confirm
- not_parking
- optional open floor editor

Inline text reply may be used for floor when UX is reliable, but must have an app-screen fallback.

Android 13+ notification runtime permission must be handled contextually.


### 9a. The candidate channel is `IMPORTANCE_HIGH`, at a second id (2026-09-23)

`parking_detection` was created `IMPORTANCE_DEFAULT`, which makes a sound and nothing else:
the prompt waits in the shade rather than appearing over what the user is doing. Reported
from the device as "알림이 너무 늦게 뜬다" — not late, unseen. The user is walking away from
the car and has 45 minutes to answer.

**A channel's importance cannot be raised in place.** Android hands ownership to the user at
creation and ignores it on later `createNotificationChannel` calls, so the new importance
needs a new id: `parking_detection_v2`. The old one is deleted when the new one is created,
so Settings lists one channel rather than two, and nothing is posted to it again.

This is the Android half of iOS's `.timeSensitive` interruption level, which needed its own
entitlement for the same reason — the system quietly downgrades a prompt that does not
prove it is one.

Measured on the Galaxy with the DEV drive replay: `channel=parking_detection_v2
importance=4`, where it read `channel=parking_detection importance=3` before.

## 10. Widgets
Use Jetpack Glance.
Data source: local active parking snapshot.

Free:
- read-only summary

Plus:
- +/- floor callbacks

Widget actions must be idempotent and update snapshot atomically.
Rapid taps need revision/version conflict handling.

## 11. Local Persistence
### Room
Completed parking history.

Suggested entity:
```text
ParkingRecordEntity
- id UUID/string
- startedAt epoch
- endedAt nullable
- latitude nullable
- longitude nullable
- accuracy nullable
- floorRaw nullable
- floorParsed nullable
- zone nullable
- spot nullable
- memo nullable
- photoRelativePath nullable
- detectionType
- confidenceBucket nullable
- createdAt
- updatedAt
```

### DataStore
- settings
- permission education completion flags
- detector registration desired state
- active parking lightweight snapshot if suitable

If atomic active snapshot needs stronger transactional semantics, use small Room table rather than complex preferences serialization.

## 12. Maps
MVP options:
A. External maps intent — lowest SDK/privacy complexity.
B. Google Maps SDK — richer in-app detail.

Decision rule:
- if product requires only pin + directions, prefer external map at v1.
- if in-app map materially improves UX, use Maps SDK but update privacy/data safety disclosures.

**결정 (2026-09-18): v1은 A, external maps intent.**
`docs/19` §3이 상세 화면의 1차 CTA를 `길찾기`로 규정했고 그건 핀 하나와 길찾기다.
Maps SDK를 쓰면 API 키 관리와 Data Safety 공시가 늘어나는데 얻는 것이 그 비용을 넘지
않는다. 홈 카드의 지도 미리보기(`01-home-main.png`)는 **정적 표현**으로 대체한다 —
좌표를 지도 타일 요청으로 내보내지 않는 편이 `docs/00` Privacy에도 맞는다.
iOS는 MapKit이 OS 기본 제공이라 키도 공시도 늘지 않으므로 그대로 쓴다.

**개정 (2026-09-24): 홈·상세·후보 확인의 지도는 Google Maps SDK lite mode.**
정적 표현은 지도처럼 보이지만 어떤 장소도 가리키지 않았다 — 사용자가 실기기에서 "정확한
지도가 아니고 9개로 나눠진 사각형 가운데로 표시된다"고 지적했다. 지도도 아니고 "위치 없음"도
아닌 것이 둘 중 어느 쪽보다 나쁘다는 판단으로 제품 오너가 A→B 전환을 결정했다.
- **lite mode**: 비트맵 한 장, 제스처 없음. 탭은 삼킨다 — 앱 밖으로 나가는 길은 여전히
  `주차 위치 보기`/`길찾기`의 external intent 하나다.
- **비용(수용됨)**: 지도를 그릴 때 좌표 주변 타일 요청이 Google로 나간다. docs/09 §Maps와
  Play Data Safety에 공시한다. 좌표를 **저장**하는 곳은 여전히 기기뿐이다.
- **키**: `PK_MAPS_API_KEY` — 로컬은 `~/.gradle/gradle.properties`, CI는 secret. 커밋 금지.
  Google Cloud 콘솔에서 Maps SDK for Android 전용으로 만들고 패키지명 + 서명 SHA-1로 제한한다.
  키가 없는 빌드(CI, fork)는 정적 표현으로 대체되어 그대로 빌드된다(`ParkingLocationMap`).
- **버전**: maps-compose 8.3.0 — compileSdk 36에 맞는 마지막 판. 8.4.0+는 core-ktx 1.19를 끈다.

## 13. Car Connection Optional Signal
`androidx.car.app.connection.CarConnection` can report:
- NOT_CONNECTED
- NATIVE
- PROJECTION

Treat projection disconnect as optional confidence evidence, not required signal.
Do not make Android Auto availability a prerequisite for smart detection.

## 14. Billing
Use current Play Billing 9.1.x stable pinned version.
Client responsibilities:
- query product details
- launch purchase
- send purchase token to backend
- acknowledge only according to verified flow
- query active purchases for recovery

Server responsibilities:
- Google Play Developer API verify
- entitlement mirror
- RTDN handling
- refunds/cancel/grace/hold
- referral reward

## 15. Referral + 7 Days on Android
For eligible active subscriptions use Google Play Developer API:
`purchases.subscriptionsv2.defer`

Effect:
- user keeps full entitlement
- no charge during deferred period
- renewal date moves

Use server-only credentials.
Use V2 API; legacy subscription defer APIs are deprecated in 2026.

## 16. RTDN
Google Play Real-time Developer Notifications arrive via Pub/Sub.
RTDN tells you state changed, not complete authoritative state.
On receipt:
1. validate message context
2. dedupe by message/event identity where possible
3. call Google Play Developer API
4. recompute entitlement
5. update server mirror
6. apply referral conversion if first verified paid purchase

## 17. Firebase App Check
Android provider: Play Integrity.
Rollout:
1. register signing SHA-256
2. monitor metrics
3. debug provider for dev/CI
4. enable enforcement after valid traffic confirmed

## 18. AdMob
- UMP consent before request where required
- free only
- banner on safe surfaces
- no app-open/interstitial/rewarded MVP
- Plus path must avoid ad request entirely

## 19. Permissions Matrix
| Feature | Permission |
|---|---|
| motion | ACTIVITY_RECOGNITION |
| nearby location | ACCESS_COARSE/FINE_LOCATION |
| background smart detection | ACCESS_BACKGROUND_LOCATION where required |
| notification | POST_NOTIFICATIONS Android 13+ |
| camera | CAMERA only when taking photo if system picker/camera flow requires |

Request progressively, not all at onboarding start.

## 20. Manufacturer Battery Restrictions
Do not build product correctness around vendor-specific whitelist hacks.
Collect QA evidence on Samsung/Pixel and at least one additional OEM.
If a vendor kills transitions/location excessively:
- document limitation
- show diagnostic state
- avoid aggressive “disable battery optimization” prompts unless necessary and policy-safe

## 21. P0 Exit Criteria
- IN_VEHICLE/WALKING transitions received on supported devices
- background candidate works with screen off
- process death/reboot recovery understood
- 20+ real driving sessions
- taxi/bus false-positive dataset collected
- battery profile acceptable enough to proceed

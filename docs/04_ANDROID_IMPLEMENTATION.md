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

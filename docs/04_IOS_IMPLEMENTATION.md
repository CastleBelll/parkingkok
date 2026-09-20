# 04. iOS Technical Implementation — 2026

## 1. Baseline

- Deployment target: iOS 18.0+
- Build SDK: latest installed stable SDK; App Store upload must satisfy current minimum (2026-04-28 onward Xcode 26+/iOS 26 SDK+)
- Recommended current toolchain: Xcode 27 stable where CI provider supports it
- Swift language mode: Swift 6
- UI: SwiftUI
- Persistence: SwiftData + FileManager
- Package management: Swift Package Manager

Reason for iOS 18 floor:
- modern Core Location live updates path
- simplifies lifecycle/background implementation
- avoids maintaining iOS 17 legacy path for a new 2026 app

## 2. App Lifecycle

SwiftUI `App` + `UIApplicationDelegateAdaptor`.
AppDelegate responsibilities:
- Firebase configure
- App Check configure before Firebase calls
- background location launch reason processing
- notification categories registration
- significant-change service restoration

Avoid placing business logic in AppDelegate.
Delegate forwards lifecycle event to `BackgroundCoordinator`.

## 3. Core Location Strategy

### IDLE

Use `CLLocationManager.startMonitoringSignificantLocationChanges()` when Smart Detection + Always permission are enabled.

Apple documents that significant-change updates can relaunch the app in background on a new significant event. This makes it suitable as the low-power travel trigger.

On relaunch:
- instantiate manager immediately
- re-register significant-change service
- do minimal synchronous work
- restore persisted detector state
- query motion history

### DRIVING

Use modern async sequence:

`CLLocationUpdate.liveUpdates(.automotiveNavigation)`

During required background live update period:
- `CLServiceSession` with required authorization
- `CLBackgroundActivitySession`

Sessions must be recreated on relevant background relaunch per Apple guidance.

### Stop aggressive tracking

As soon as parking candidate reaches terminal state or driving times out:
- cancel liveUpdates task
- invalidate background session
- return to significant-change monitoring

## 3a. OPEN: the bounded capture ran 42 minutes late on a real drive (2026-09-20)

Measured on an iPhone 14 Pro Max, two real drives in one day, **zero candidates created**.

Drive 2, fixes bucketed by ten minutes (`vehicle_enter` at 16:09):

```text
16:10   2 fixes   median accuracy   136 m
16:20   2 fixes   median accuracy  1414 m
16:30   2 fixes   median accuracy  1000 m
16:40   2 fixes   median accuracy  1075 m
16:50   2 fixes   median accuracy   200 m
17:00  17 fixes   median accuracy    20 m   ← the walk after parking
17:10  29 fixes   median accuracy    20 m
```

Two fixes per ten minutes at kilometre accuracy is significant-change grade, not
`liveUpdates(.automotiveNavigation)`. The dense, accurate run only begins at 17:00 — after
the car had stopped — and `drivingSessionCount` is 1 with
`lastDrivingSessionEndReason: vehicleEvidenceExpired` at 17:15.

Everything downstream follows from it: `distanceNoiseFloorRejectCount: 352` of 378 fixes,
`drivingDistanceMeters: 169.6` for two hours of driving, and
`movementEvidenceRejectReason: accuracyTooCoarse`. §7's clauses cannot be met from
kilometre-accuracy fixes, so no candidate is ever justified.

**What does not add up, and is the thing to investigate.** `drivingConfirmedAt` is 16:18 —
nine minutes into the drive — so the engine did decide it was driving and should have
emitted `startBoundedLocationCapture` then. `LiveDrivingLocationCapture` holds a
`CLServiceSession(authorization: .always)` and a `CLBackgroundActivitySession` precisely so
delivery survives backgrounding. Either the capture was never started at 16:18, or it was
started and produced nothing until the app came to the foreground. The diagnostics as they
stand cannot tell those apart.

Before the next instrumented drive, add to the diagnostics report: when
`startBoundedLocationCapture` was last emitted, when `LiveDrivingLocationCapture.start()`
last ran, and whether either session object is currently non-nil. Without those three the
next drive produces the same ambiguity.

Android failed the same gate for a different reason and it is fixed there — see
docs/04_ANDROID_IMPLEMENTATION.md §4a/§4b. The Android answer (a drive-scoped foreground
service) has no iOS equivalent; the session objects above are already meant to be it.


## 4. Permission Design

Info.plist:
- `NSLocationWhenInUseUsageDescription`
- `NSLocationAlwaysAndWhenInUseUsageDescription`
- `NSMotionUsageDescription`
- `NSCameraUsageDescription` when camera used
- `NSUserTrackingUsageDescription` only if ATT is actually requested

Background Modes:
- Location updates only if implementation needs it

Always permission is requested contextually after user opts into smart detection. Explain:
- why background needed
- what is stored
- that exact parking history is not uploaded to server

## 5. Core Motion

`CMMotionActivityManager`.

Two uses:
1. foreground/current updates when process alive
2. `queryActivityStarting(from:to:)` to reconstruct motion state after significant-change wake/relaunch

Important: `automotive` and `stationary` may both be true. Treat activity as weighted evidence, not enum.

Normalize:

```swift
struct MotionSample: Sendable {
    let timestamp: Date
    let automotive: Bool
    let walking: Bool
    let stationary: Bool
    let running: Bool
    let confidence: MotionConfidence
}
```

## 6. Background Rehydration

Persist `DetectionCheckpoint` locally after meaningful transition:

```text
state
stateEnteredAt
lastAutomotiveAt
lastReliableLocation
lastLocationAt
travelDistanceEstimate
candidateId
revision
```

On launch due to location:
1. read checkpoint
2. validate age
3. query motion history from max(checkpoint.time, now-30m)
4. feed reconstructed events into engine
5. start/stop live location according to resulting state

## 7. Background Execution Rules

Do not assume unlimited execution time.
Background callback should:
- persist minimal state
- schedule local notification if necessary
- avoid Firebase network request unless optional and bounded
- never wait for ads/Remote Config

## 8. Notifications

Use `UNUserNotificationCenter`.
Categories:

`PARKING_CANDIDATE`
- text input `ENTER_FLOOR`
- destructive-ish `NOT_PARKING`

`PARKING_ENDED`
- optional `UNDO_END`

Response handler must complete quickly.
Text action updates local active session, writes snapshot atomically, reloads widgets.

## 9. MapKit

Use SwiftUI `Map`.
- camera fit around last reliable location
- optional accuracy circle using horizontalAccuracy
- no server map snapshot

Label wording:
- `마지막으로 확인된 위치`
- not `차량 정확한 위치`

## 10. Photo Handling

Camera/Photos picker → downsample before store.

Suggested storage:
`Application Support/ParkingPhotos/{recordId}.heic`

Rules:
- write atomic temp → final move
- remove orphan on record delete
- periodic orphan cleanup
- exclude cache thumbnails from backup if generated

## 11. SwiftData

Models should avoid storing CLLocation object directly.
Store primitive latitude/longitude/accuracy/date values.

Schema migration must be planned from v1.

Create `SchemaV1` versioned model and `SchemaMigrationPlan` even if first release has no migration. This avoids ad-hoc migrations later.

## 12. StoreKit 2

`SubscriptionStore` loads products and observes `Transaction.updates`.
On startup:
- iterate `Transaction.currentEntitlements`
- verify results
- derive local entitlement
- asynchronously reconcile with backend

Purchase:
- product.purchase(options: [.appAccountToken(accountId)])
- verify transaction
- finish only after local processing
- backend receives authoritative notification independently

Never trust unverified transaction.

## 13. WidgetKit / App Intents

Widget reads shared active snapshot.
Interactive `+/-` uses AppIntent.
Intent requirements:
- validates active session exists
- floor is numeric and bounded
- atomic snapshot mutation
- increments revision
- requests widget reload

App reconciles App Group revision to in-memory UI on activation.

## 14. Firebase SDK Integration

Use SPM.
Initialize App Check before other Firebase traffic.
Production: App Attest provider.
Debug/simulator: App Check debug provider, never release.

Anonymous Auth occurs lazily when first backend-required feature is used (referral/paywall sync), not necessarily before home can render.

## 15. AdMob

Initialize after UMP consent info update.
Only request ads if consent API says allowed.

Use anchored adaptive banner or inline adaptive where design requires scrolling placement.

Free history/settings only.
Plus gate checked before SDK ad request.

## 16. Deep Links

Custom URL or universal link later:
- `parkingkok://referral/PKXXXX`

MVP may use share text with code to avoid universal-link infrastructure.

## 17. Build Configuration

Use `.xcconfig`:
- DEV
- STAGING
- PROD

Secrets:
- no App Store private keys in app bundle
- no Firebase admin credentials in client
- promo signing key server only

## 18. Code Quality

Recommended:
- SwiftFormat
- SwiftLint with minimal high-signal rules
- XCTest/Swift Testing depending team standard, do not mix styles arbitrarily
- deterministic tests for domain engine

CI treats Swift 6 concurrency warnings as errors.

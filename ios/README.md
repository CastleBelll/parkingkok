# iOS — 주차콕

`ParkingKok.xcodeproj` is **generated**, not committed. `ios/project.yml` is the
source of truth; the `.pbxproj` is a build artifact (see ADR note in the PR for T-1.3).

## Toolchain

No Homebrew, no `sudo`. Binaries live in `~/.local/bin` (already on `PATH`):

| Tool | Version | Source |
| --- | --- | --- |
| XcodeGen | 2.46.0 | `yonaskolb/XcodeGen` release `xcodegen.zip` → `~/.local/{bin,share}` |
| SwiftLint | 0.65.1 | `realm/SwiftLint` release `portable_swiftlint.zip` |
| SwiftFormat | 0.63.0 | `nicklockwood/SwiftFormat` release `swiftformat.zip` |

XcodeGen needs its `SettingPresets` at `<prefix>/share/xcodegen`, so install the
`bin/` and `share/` folders from the release together. Without them XcodeGen
silently drops the platform/product setting presets.

## Generate and build

```sh
cd ios && xcodegen generate

xcodebuild -project ParkingKok.xcodeproj -scheme ParkingKok \
  -configuration DEV \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

xcodebuild -project ParkingKok.xcodeproj -scheme ParkingKok \
  -configuration DEV \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```

## Build and install on a real device

`DEVELOPMENT_TEAM` is intentionally empty in `Config/Base.xcconfig` — the team ID is not
committed (docs/18_CI_CD_AUTOMATED_RELEASE.md keeps it as `APPLE_TEAM_ID`). Pass it in:

```sh
xcodebuild -project ParkingKok.xcodeproj -scheme ParkingKok \
  -configuration DEV -destination 'id=<device-udid>' \
  PK_DEVELOPMENT_TEAM=<team-id> -allowProvisioningUpdates build

xcrun devicectl device install app --device <device-udid> <path>/ParkingKok.app
xcrun devicectl device process launch --device <device-udid> com.parkingkok.app.dev
```

If `xcodebuild` reports "Unable to find a destination matching the provided destination
specifier", check the tunnel rather than the project:

```sh
xcrun devicectl device info details --device <device-udid> | grep -E 'tunnelState|ddiServices'
```

`tunnelState: unavailable` means the phone is not actually reachable. Plug it in, unlock
it, and trust the host — the developer disk image only mounts while unlocked.

## Configurations (docs/04_IOS_IMPLEMENTATION.md §17)

| Config | Bundle ID | Build type |
| --- | --- | --- |
| DEV | `com.parkingkok.app.dev` | debug |
| STAGING | `com.parkingkok.app.staging` | release |
| PROD | `com.parkingkok.app` | release |

All three share `Config/Base.xcconfig`: iOS 18.0, Swift 6 language mode, complete
strict concurrency, warnings-as-errors.

## M0A field-test checklist — low-power wake and rehydration

Neither of the two paths this milestone exists for can be verified in the simulator: the
simulator does not relaunch a terminated app for a significant location change, and it
has no Core Motion history to query. Both need the device, a real trip, and the
diagnostics screen (`감지 진단`).

Run each step and record what the screen showed.

1. **Permission ladder.** Fresh install → open the app. Only the When-In-Use prompt may
   appear. Confirm no Always prompt on first launch (docs/04 §4).
2. **Contextual Always.** Turn on `Smart Detection`. The Always prompt should appear only
   now. Grant it, then confirm `significant-change 모니터링` reads `ON`.
3. **Motion history returns real samples.** Grant motion permission, walk for 5+ minutes,
   reopen the app and tap 새로고침. `샘플 수` must be > 0 and the rows should show
   `walking` and `stationary`. Record whether any row shows `automotive+stationary`
   together — that pair is the case the engine design depends on.
4. **Significant-change delivery.** Travel far enough to trigger one (Apple's threshold is
   roughly 500 m plus a few minutes). `significant-change 수신` should increment and
   `마지막 수평 정확도` should be populated.
5. **Process death and relaunch.** Force-quit the app from the app switcher, travel again,
   then open the app *after* the change fires. `실행 사유` should read
   `significantLocationChange` and `체크포인트` should show a non-zero `rev`. This is the
   path the whole milestone exists for; a `rev` of 0 means the wake never happened.
6. **Locked-device wake.** Repeat step 5 with the phone locked for the whole trip. The
   checkpoint must still restore. If `체크포인트` shows `unreadable: ...`, the data
   protection class is wrong — that is the specific failure
   `.completeFileProtectionUntilFirstUserAuthentication` is there to prevent.
7. **Reboot.** Restart the phone, do not open the app, travel. Core Location should still
   relaunch the app. Record whether it did.
8. **Permission revoked mid-trip.** Downgrade Always to When-In-Use in Settings while
   Smart Detection stays on. Monitoring should read `OFF` and the app must remain usable.

## M0A-2 field-test checklist — bounded driving session

The bounded session (`CLLocationUpdate.liveUpdates(.automotiveNavigation)` +
`CLServiceSession` + `CLBackgroundActivitySession`) needs the `location` background mode
and cannot run in the simulator at all. Everything below is device-only, and steps 3–6
need a real drive.

Recover the evidence with the diagnostics file — no `sudo`, no `log collect`:

```sh
xcrun devicectl device copy from --device <device-udid> \
  --domain-type appDataContainer --domain-identifier com.parkingkok.app.dev --user mobile \
  --source "Library/Application Support/Detection/diagnostics.json" --destination ./diagnostics.json
```

1. **Plumbing, without a car.** DEV builds honour a launch hook that opens a session
   immediately, so a failed drive means a detection problem rather than a wiring problem:

   ```sh
   xcrun devicectl device process launch --device <device-udid> \
     --environment-variables '{"PK_FORCE_DRIVING_SESSION":"1"}' com.parkingkok.app.dev
   ```

   After ~30 s the report must show `isCapturingDrivingLocation: true`,
   `state: DRIVING_CANDIDATE`, a rising `drivingFixCount`, and
   `reliableLocationUpdateCount > 0`. A stationary phone must **not** confirm:
   `drivingMovingSampleCount` stays 0 and `drivingConfirmedAt` stays absent.
2. **The session ends by itself.** Leave that forced session alone for
   `DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout` (10 min). The report must flip to
   `isCapturingDrivingLocation: false` with
   `lastDrivingSessionEndReason: vehicleEvidenceExpired`. A session still capturing here
   is the leak the §19 battery gate exists to catch.
3. **A real drive opens a session.** Drive with the app killed. `drivingSessionCount`
   must reach 1 and `lastVehicleEvidenceAt` must be populated. If it is not, read
   `lastVehicleEvidenceConfidence` — Core Motion reporting only `low` automotive is a
   tuning problem, not a wiring one.
4. **Driving confirmation.** Past 120 s or 800 m, `drivingConfirmedAt` must be set and
   `state` must read `DRIVING`. Confirm it did **not** fire on the first fix.
5. **Parking transition.** Park and walk away. `lastDrivingSessionEndReason` must read
   `walkingDetected`, `state` must return to `IDLE`, and `hasReliableLocation` must be
   true with `reliableLocationAccuracy` <= 35 m captured within seconds of stopping.
6. **Process death mid-drive.** Force-quit during a drive, keep driving.
   `drivingSessionResumedFromCheckpoint` must be true on the next wake — that is the
   "sessions must be recreated on relevant background relaunch" rule from docs/04 §3.
7. **Privacy.** Every retrieved `diagnostics.json` must contain no coordinate. `grep -i
   'latitude\|longitude'` returns nothing, and no number in the file falls in the
   device's lat/lon range.

## M1 field-data checklist — trace recording

`docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §9. The point of recording is that ordinary
movement becomes fixture material, so the checklist is mostly "go somewhere and come back".
Bus, subway and taxi rides count, and so does a passenger seat.

A trace session is a run of events with no long silence in it (§9: 30 min idle gap, 4 h and
1000 events as ceilings, judged when the next event arrives — no timer). **It is not tied to
the bounded driving session**: a walk, a subway ride and a lone significant-change wake open
a session just as a drive does, which is what makes the negative cases collectable without a
car. Turning Smart Detection off closes the open session immediately; nothing is recorded
while it is off.

Retrieve the whole recording directory the same way as `diagnostics.json`, no `sudo`:

```sh
xcrun devicectl device copy from --device <device-udid> \
  --domain-type appDataContainer --domain-identifier com.parkingkok.app.dev --user mobile \
  --source "Library/Application Support/Detection/traces" --destination ./traces
```

1. **Plumbing, without a car.** Turn Smart Detection on and walk for five minutes with the
   app backgrounded. `감지 진단 → 이동 기록 (trace)` must show `세션 수` ≥ 1 and a rising
   `이벤트 수` — **with no drive anywhere in it**. Retrieve the directory and confirm the file
   contains `walking_enter` and at least one `location`. This is the case the bounded-session
   boundary used to drop entirely, so an empty directory here is the regression.
   `PK_FORCE_DRIVING_SESSION=1` (see the M0A-2 checklist) additionally exercises the 1 Hz fix
   path: `location` events with `accuracy`, and `distanceFromPreviousM` on all but the first.
2. **Motion vocabulary on device.** Walk, sit still, ride something. The trace must contain
   `vehicle_enter` / `vehicle_exit` / `walking_enter` / `stationary_enter` /
   `stationary_exit` — no Core Motion enum names. Record which ones your phone actually
   produced; a missing `vehicle_exit` is the inference in `TraceRecorder.transitions`
   failing, not the contract being wrong.
3. **Quality transitions.** Drive into an underground car park or a tunnel. The trace must
   contain `location_quality_degraded` with `fromBucket`/`toBucket` drawn from
   `good`/`fair`/`poor`.
4. **Privacy.** Every retrieved trace file must contain no coordinate. `grep -i
   'latitude\|longitude\|coordinate'` returns nothing, and no number in the file falls in
   the device's lat/lon range. `distanceFromPreviousM` is metres, never a position.
5. **Labelling.** Tag a ride from `이동 기록 → 세션 목록`. Retrieve again and confirm the
   `label` object is in the file and that `라벨 없음` dropped by one. Label a ride *while
   still on it* and confirm the next event does not wipe the label.
6. **Rolling cap.** `상한으로 버림` must stay 0 in ordinary use and must become non-zero
   rather than the directory growing without bound. Check the directory size after a week
   of commuting: 40 sessions and 4 MB are the ceilings.
7. **Process death mid-trip.** Force-quit during a drive. The abandoned file must already
   have a sensible `endedAt` (no repair pass runs). On the next wake **inside the idle gap
   the same file must keep growing** — §9's boundary belongs to the event stream, not to the
   process — with no second `vehicle_enter` restating a drive that never stopped, and no
   `distanceFromPreviousM` on the first fix after the relaunch.
8. **The boundary itself.** After a commute and half an hour at the desk, the return trip
   must be a *separate* file starting at its own first event. One file spanning both is the
   boundary failing; a file per wake is the open-session pointer failing.
9. **The label prompt.** The only step that needs a human hand, because notification
   permission cannot be granted from the command line on a physical device. Grant it from
   `감지 진단 → 권한 → 알림 권한 요청`, then travel and stop for half an hour. When the
   session rotates, a notification must appear reading
   `이 이동, 무엇이었나요?` over a line like `14:03–14:32 · 29분 · 차량 17분 + 도보 · 이벤트 12개`,
   with 자동차 / 버스 / 지하철 / 도보 buttons when expanded. Tap one and confirm, after
   retrieving the directory, that the session's `label` carries that mode — and that
   `알림 못 띄움` on the diagnostics screen stayed put. **A body containing a coordinate or
   an address is a privacy defect, not a formatting one.** Without the grant nothing is
   shown and `알림 못 띄움`/`traceLabelPromptSuppressedCount` climbs instead, which is the
   expected reading and not a failure. A session with no motion event is deliberately never
   prompted for: there is nothing a person could answer about three location fixes.

## DEV-only UI fixtures

The product screens need an active parking and a history to be worth looking at, and
neither `devicectl` nor the simulator can drive a touchscreen. Two launch hooks fill that
gap. Both are inside `#if PK_DEV`, so STAGING and PROD do not contain them.

```sh
xcrun devicectl device process launch --device <device-udid> --terminate-existing \
  --environment-variables '{"PK_SEED_SAMPLE_PARKING":"1","PK_INITIAL_ROUTE":"history"}' \
  com.parkingkok.app.dev
```

| Variable | Effect |
| --- | --- |
| `PK_SEED_SAMPLE_PARKING=1` | **Replaces** the local store with the fixture behind `design-references/01-home-main.png`: B3 · A구역 142 parked 1시간 24분 ago, over four earlier records. The active parking carries a Seoul City Hall coordinate at 24m accuracy and a drawn placeholder photo, so `03-parking-detail.png` can be compared against a screen with the FR-008 map and the FR-007 photo panel populated. The four history records carry no coordinate, which is the FR-001 state. |
| `PK_INITIAL_ROUTE=history\|settings\|detail\|diagnostics` | Opens the stack on that screen instead of home. |
| `PK_SEED_WITHOUT_LOCATION=1` | Seeds the active parking with neither a coordinate nor a photo — the FR-001 state. Use it with `PK_INITIAL_ROUTE=detail` to photograph the degraded detail screen: no map card, `길찾기` dimmed and explained, empty photo panel. |

Appearance for the light/dark pass:

```sh
xcrun devicectl device settings appearance --device <device-udid> --mode light
xcrun devicectl device capture screenshot --device <device-udid> --destination shot.png
```

Capture a few seconds after launch — a screenshot taken during the launch animation
catches the scroll view mid-bounce and looks like a safe-area bug that is not there.

## M2 field check — map and photo

Neither half of M2 is fully observable in the simulator: the simulator has no camera, so
`사진 촬영` never appears there, and MapKit tiles render differently under the simulator's
GPU. Both need the device.

```sh
xcodebuild -project ParkingKok.xcodeproj -scheme ParkingKok \
  -configuration DEV -destination 'id=<device-udid>' \
  PK_DEVELOPMENT_TEAM=<team-id> -allowProvisioningUpdates build

xcrun devicectl device install app --device <device-udid> <path>/ParkingKok.app
xcrun devicectl device process launch --device <device-udid> --terminate-existing \
  --environment-variables '{"PK_SEED_SAMPLE_PARKING":"1","PK_INITIAL_ROUTE":"detail"}' \
  com.parkingkok.app.dev
xcrun devicectl device capture screenshot --device <device-udid> --destination detail.png
```

Check, against `design-references/03-parking-detail.png`:

1. **Map.** Centred on the stored coordinate with the accuracy circle visible, captioned
   `마지막으로 확인된 위치 · 약 24m 이내`. No wording anywhere on the screen claims the exact
   car position (FR-008, docs/04 §9).
2. **Hierarchy.** The hero floor is still the largest element; the map does not outweigh
   it. Order is map → summary → facts → `길찾기`/`사진 보기` → 주차 사진 → 주차 종료.
3. **Photo.** The panel shows the stored image and a `…에 저장됨` caption. Tapping it opens
   the full-screen viewer.
4. **길찾기.** Opens Apple Maps with walking directions. On a record with no coordinate
   (open one from history) it is dimmed and explains why.
5. **Camera.** `사진 추가` offers 사진 촬영 and 앨범에서 선택. Denying camera access must leave
   the album path and the rest of the screen working.

## Lint

```sh
swiftformat --lint ios     # formatting
swiftlint lint --strict    # correctness; run from the repo root
```

## Tests

Swift Testing only — do not add XCTest (docs/04 §18 forbids mixing styles).

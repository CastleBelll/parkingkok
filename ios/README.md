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

## Lint

```sh
swiftformat --lint ios     # formatting
swiftlint lint --strict    # correctness; run from the repo root
```

## Tests

Swift Testing only — do not add XCTest (docs/04 §18 forbids mixing styles).

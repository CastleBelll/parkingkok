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

## Configurations (docs/04_IOS_IMPLEMENTATION.md §17)

| Config | Bundle ID | Build type |
| --- | --- | --- |
| DEV | `com.parkingkok.app.dev` | debug |
| STAGING | `com.parkingkok.app.staging` | release |
| PROD | `com.parkingkok.app` | release |

All three share `Config/Base.xcconfig`: iOS 18.0, Swift 6 language mode, complete
strict concurrency, warnings-as-errors.

## Lint

```sh
swiftformat --lint ios     # formatting
swiftlint lint --strict    # correctness; run from the repo root
```

## Tests

Swift Testing only — do not add XCTest (docs/04 §18 forbids mixing styles).

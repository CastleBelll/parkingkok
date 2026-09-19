# 06. Local Data, Storage & Widget Sync — iOS + Android

## 1. Data Classification
Sensitive local-only:
- latitude/longitude/accuracy
- floor/zone/spot/memo
- photo

Backend-safe:
- anonymous/internal account id
- referral code/state
- store transaction identifiers
- reward ledger
- feature flags

## 2. Common Record Schema
Logical fields must match across platforms:
```text
id
startedAt
endedAt?
source(manual/detected)
confidenceBucket?
latitude?
longitude?
horizontalAccuracy?
locationCapturedAt?
floorRaw?
floorKind?
floorNumber?
zone?
spot?
memo?
photoRelativePath?
createdAt
updatedAt
```

## 3. iOS Persistence
- completed history: SwiftData
- settings: UserDefaults/AppStorage as appropriate
- active cross-process session: App Group atomic snapshot
- photo: app-private Application Support/Documents depending backup policy

Widget reads App Group projection only.

## 4. Android Persistence
- completed history: Room
- settings: Preferences/DataStore
- active session: small Room table or atomic DataStore model; choose one source of truth
- widget projection: Glance state/DataStore derived from active session
- photo: app-private files directory

Do not keep separate unsynchronized active parking copies in Room and DataStore. If Room is canonical, widget projection is derived/cache.

## 5. Revision Model
Active session includes:
- sessionId
- revision monotonic local integer
- updatedAt

Every widget/app mutation is read-modify-write with revision increment.

## 6. iOS Atomic Snapshot
App Group JSON may be used because widget extension is separate process.
Use atomic file replacement and shared mutation helper.

The file lives at `Library/Application Support/Widget/active-parking.json` inside the
group container, not at its root: that is where the rest of the app keeps local state, and
it is the only part of a shared container `devicectl device copy from` will read — which is
what lets a field test pull the projection off a real device and check the revision.

## 7. Android Widget Mutation
Glance callback must delegate to repository/application layer.
Do not store product business state only inside widget state.
After mutation:
1. update canonical active session
2. increment revision
3. request widget refresh

## 7a. Widget Contract (v1)

Sections 5-7 fix how the widget *syncs*. These fix what it *is*, so the two platforms
cannot each invent an answer.

### Sizes
- iOS: `systemSmall` and `systemMedium`. No Lock Screen or StandBy family in v1.
- Android: 2x2 and 4x2 cells.

Small/2x2 shows the floor and the elapsed duration. Medium/4x2 adds the `zone · spot`
line and, when entitled, the stepper.

### What it shows
There is no widget mock. The binding reference is the hero card of
`design-references/01-home-main.png`: the floor as the one large element, `zone · spot`
beneath it, then the elapsed duration. The widget is that card with the map thumbnail
and the primary action removed.

With no active parking the widget shows a single line inviting the user to open the
app. It never renders a coordinate, an address, or a photo (docs/09).

### Rapid taps resolve by delta, not by value
A stepper callback carries a **delta** (`+1` / `-1`), never a resulting floor. Each
mutation, under the platform's shared-write lock:

1. re-reads the current snapshot,
2. checks `sessionId` still matches the one the widget rendered,
3. applies the delta through the shared floor domain (`stepped(by:)` and its Kotlin
   twin — signed level, no zero, bounded by `maximumNumber`),
4. increments `revision`,
5. writes atomically and requests a reload.

Two taps landing together therefore move two floors. Had the callback carried a value,
the second write would have silently discarded the first.

If step 2 fails — the session ended between render and tap — the mutation is dropped.
It is never applied to whatever session came next.

A delta the domain rejects (out of bounds) is a no-op that still reloads the widget, so
the display snaps back to the true value rather than appearing to have moved.

### Entitlement
Interactive stepping is a Plus feature (docs/02 §14, docs/04_ANDROID §10). Plus does not
exist until M6, so v1 reads a single hardcoded source — one function, one file per
platform — that returns true for DEV/STAGING and false for PROD. The interactive path is
therefore fully built and tested now, while a shipped build stays read-only. M6 replaces
the hardcode and nothing else.

Tests must cover both branches; entitlement must not be read anywhere but that one
function.

## 8. Completion Commit
Logical sequence on both platforms:
1. load active
2. insert completed record with same sessionId
3. verify commit
4. clear active
5. refresh widget

Startup repair:
if completed record exists with same active sessionId -> clear stale active projection.

## 9. History Limit
Never delete local user data on subscription downgrade solely due to entitlement.
Free UI shows latest N (default 5), older data remains local and unlocks again if Plus returns.

## 10. Photo
- one photo per record MVP
- downsample ~1600 px long edge
- local only
- orphan cleanup job after failed record deletion/commit

## 11. Export
Plus CSV default excludes coordinates.
Optional coordinate export requires explicit user action/warning.
Exports should strip photo EXIF GPS if photos are ever exported/shared.

## 12. Backup
### iOS
Decide iCloud device backup participation; no CloudKit sync.

### Android
App Auto Backup/device transfer could copy app-private data depending manifest/config. Decide before release whether parking history/photo should be included. Ensure privacy policy wording matches actual behavior.

No Firebase cloud sync MVP.

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
- iOS: `systemSmall` and `systemMedium`, plus the three Lock Screen accessory families.
  No StandBy family in v1.
- Android: 2x2 and 4x2 cells.

**Every size shows the floor, `zone · spot` and the elapsed duration** (2026-09-20). Those
three are the widget: the floor tells you which level, the zone tells you where on it, and
the elapsed line is how you know the reading is current. This section used to give the
2x2/small only the floor and the elapsed time, on the grounds that a third line would cost
the floor its size — it does, by a few points, and the zone is worth more than those points.
A user standing on B3 without the zone still has to search the level.

Medium/4x2 adds nothing but the stepper, when entitled, and a little more room for the hero.
The `zone · spot` line is absent only when the record carries neither.

## 7b. The Lock Screen (2026-09-20)

This section used to read "No Lock Screen or StandBy family in v1". The product owner asked
for the parking **zone** on the lock screen, and it is the right ask: the moment the user
needs this is standing in a car park with the phone not yet unlocked.

### iOS
The three accessory families, from the same `ActiveParkingSnapshot` and the same timeline —
a lock screen widget here is a fourth rendering of one projection, not a second source.

| family | shows |
|---|---|
| `accessoryRectangular` | floor, `zone · spot`, elapsed — the only one with room for all three, and the one the ask is about |
| `accessoryCircular` | the floor alone |
| `accessoryInline` | `B3 · A구역` on the one line iOS gives it |

**No stepper.** Interactive buttons work on the Lock Screen from iOS 17, and they are still
wrong here: the keys are a Plus feature behind a tap target smaller than a fingertip, on a
surface the user reaches without authenticating. The Lock Screen is read-only.

**Monochrome by contract, not by accident.** The system renders accessory widgets in its own
vibrant tint; the palette in docs/10 §2 does not apply and must not be fought. Hierarchy
comes from size and weight alone, which is what §10's harness says it should come from
anyway.

### Android
The 2x2 provider declares `android:widgetCategory="home_screen|keyguard"` and an
`initialKeyguardLayout`, so the same Glance widget offers itself to the lock screen. A host
with no lock-screen slot ignores the category, so it costs such a build nothing.

**An earlier draft of this section said Android could not do this at all**, on the strength
of a `dumpsys appwidget` sweep in which every keyguard-category provider was a Samsung
system app. That sweep proved only that no third-party app *on that phone* had declared the
category — which is a very different claim, and the product owner had seen other apps do it.
Recorded because the reasoning error is the interesting part: "no example on this device" is
not "the platform forbids it".

#### The shade notice, as a fallback
Lock-screen slots are host-dependent, so there is also an ongoing notification carrying the
same three facts. It is **off by default** and lives behind a switch in Settings: the widget
is the answer, and this is for a host that has no slot to offer.

- `VISIBILITY_PUBLIC`, because the content is a floor and a zone. It is never a coordinate,
  an address or a photo (docs/09), so there is nothing to hide on a locked screen.
- `setOngoing(true)` and **no foreground service.** CLAUDE.md forbids a permanent service;
  an ongoing notification needs none, and it is posted and cancelled by the same writes that
  already move the widget projection.
- Its own channel, at `IMPORTANCE_LOW`: it must never make a sound. It is a readout, not an
  alert, and the candidate notification is the only thing in this app allowed to interrupt.
- Tapping it opens the app on the active parking, like the widget.

### What both sides share
It renders no coordinate, no address and no photo. With no active parking there is nothing:
the iOS families fall back to the same single line the home-screen tile shows, and the
Android notification is cancelled rather than replaced by an empty one.

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

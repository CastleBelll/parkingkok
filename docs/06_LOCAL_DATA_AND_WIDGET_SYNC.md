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

## 7. Android Widget Mutation
Glance callback must delegate to repository/application layer.
Do not store product business state only inside widget state.
After mutation:
1. update canonical active session
2. increment revision
3. request widget refresh

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

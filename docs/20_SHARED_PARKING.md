# 20. Shared Parking — one car, several people

Decided 2026-09-20. The product owner asked for family sharing and lifted the standing rule
that parking data never reaches Firebase. This is the plan that replaces it.

## 1. What is shared, and what is not

**The active parking, and nothing else.**

| | shared | why |
|---|---|---|
| the open parking: floor, zone, spot, memo, coordinate, started-at | **yes** | it is the whole feature — 우리 차 어디 있지 |
| history | no | a finished trip is one person's memory of their own day. Syncing it multiplies the exposure for no product gain, and `docs/01 §5` keeps cloud history a non-goal |
| the parking photo | no in v1 | the largest and most revealing artefact, and a pillar photo is taken by whoever parked, for whoever parked. Revisit with a reason, not by default |
| detection traces, candidates, diagnostics | never | device-local by `docs/05 §9` and useless to another member |

When the parking ends the shared document is **deleted**, and each member's device keeps its
own local history row. The server therefore holds at most one live location per car, and
holds nothing at all for a car that is not parked.

## 2. The unit is a car, not a family

`cars/{carId}` with a member list. One car, several people.

Not "family" because families own two cars, and a car is what the app is actually about. A
user belongs to zero or more cars; belonging to two needs no redesign, only a picker, and
that picker is out of scope until someone asks.

## 3. It is end-to-end encrypted, and here is why that is worth the work

The payload in §1 is encrypted on the device with a per-car AES-GCM-256 key. Firestore holds
ciphertext and a nonce. **The key is never written to Firestore.**

The cheaper option — plaintext, ordinary Firestore rules — was considered and rejected:

- **The app's own copy says otherwise.** Settings reads 주차핀은 위치, 사진, 메모를 서버로
  보내지 않습니다. Plaintext makes that a line that has to be deleted; E2E makes it a line
  that has to be narrowed to "읽을 수 없습니다", which is still true and still the product's
  most distinctive claim.
- **A live parking coordinate is the sharp end of location data.** Not "where they went last
  Tuesday" — where this family's car is *right now*. A rules mistake or a breach exposes
  present whereabouts, and the blast radius of the same mistake against ciphertext is a
  blob.
- **It is unusually cheap here**, because the shared document is ephemeral. The recovery
  story for a lost key — normally the hardest part of E2E — is "the current parking becomes
  unreadable; make a new car". For a record that lives hours, that is an acceptable worst
  case. It would not be for a notes app.

### Key handling

1. The car's creator generates a random 256-bit key on device and keeps it in the platform
   keystore (`docs/09 §6`: Keychain, Android Keystore — never DataStore).
2. Each member publishes an **X25519 public key** in their member document. Private halves
   never leave the device.
3. To add a member, an existing member wraps the car key to the newcomer's public key and
   writes the wrapped blob to that member's document. The server stores wrapped keys and
   cannot open them.
4. Removing a member **rotates** the car key and re-wraps for everyone who remains. A removed
   member may still hold plaintext they cached before removal; that is inherent and must be
   said plainly in the UI, not papered over.

### What E2E does not buy

It does not remove a single compliance obligation. The app still collects and transmits
location, and the Data Safety form and the App Store privacy label still say so. **In
particular it does not answer the 위치정보법 question below.**

## 4. OPEN before launch: 위치정보법

Korea's 위치정보의 보호 및 이용 등에 관한 법률 attaches obligations to a service that handles
*other people's* location — which is exactly what sharing turns this into, and is exactly
what the local-only design avoided. Encryption does not obviously exempt a provider, because
the obligation attaches to providing the service.

This needs a real answer from someone qualified before the feature ships. It is written here
rather than left to store-review time because it can change the design — if the answer is
that a plaintext store and an encrypted store are treated the same, §3's trade is different,
and if the answer is that neither is viable without registration, the one-off share in §8
becomes the whole feature.

## 5. Two phones, one car: the duplicate candidate

Detection runs on every member's phone. A family driving together is three phones seeing
`vehicle_enter`, three reaching `CANDIDATE_PENDING`, and — today — three notifications and
three records for one parking. `docs/05 §12`'s "one candidate per travel session" is per
*device*; shared parking needs it per *car*.

**Do not try to work out who was driving.** Motion cannot tell: the passenger's phone sees
the same trip. It also does not matter, because the answer is one record either way.

The car document is the arbiter, and the mechanism already exists:

1. Every device detects and prompts its own user, unchanged.
2. The first confirmation writes the active parking to the car in a transaction.
3. Other devices observe the write and **supersede** their own pending candidate — the same
   path `docs/05 §10a` already defines, with the same `응답 없음` history entry and the same
   notification withdrawal.
4. A device that was offline keeps prompting and resolves on reconnect. A stale prompt is a
   failure mode §10a already tolerates; a duplicate record is not, and the transaction in (2)
   is what prevents it.

**A bonus worth taking.** Once the car is known parked, the other phones can leave `DRIVING`
and stop their bounded location capture immediately instead of waiting for a timeout. That
is the `DrivingLocationService` on Android and the `CLBackgroundActivitySession` on iOS shut
down minutes early, on every shared trip.

## 6. The local record stays canonical

The device that made the parking owns it. The car document is a **projection**, written
after the local write, exactly as the widget projection is (`docs/06 §4`). A device offline
saves normally and pushes when it can; a device that cannot read the car shows its own last
known state rather than an error.

This keeps the app's existing property that every screen works with no network, which
`docs/01 §8` requires and which sharing must not quietly cost.

## 7. Sequencing, and one thing that comes first

M5 in `docs/14` — durable accounts, App Check, Firestore rules — is the floor. None of it
has started; today identity is Firebase anonymous auth (`docs/07 §13`).

**And before any of it: the detector has never produced a candidate on a real drive.**
Measured 2026-09-20 — iOS produced zero from two real drives, Android one carrying a fix five
hours stale. Fixes for both landed the same day and are unverified in the field. Shared
parking multiplies whatever the detector does; multiplying zero is zero, so the next real
drive comes before the first line of this document is built.

## 8. The smaller thing this is not

"주차 위치를 가족에게 보내기" — a share sheet, one message, no account, no sync, no stored
location. It answers *tell them where I parked*, not *we all see where the car is*, and it
needs none of §3 through §7.

It is recorded here because it is a genuinely different product that is often mistaken for
this one, and because if §4 comes back badly it is the fallback that still ships.

# 05. Parking Detection Engine Specification — Cross-platform

## 1. Why This Is the Product
주차핀의 차별화는 UI가 아니라:
1. parking candidate precision
2. 적절한 candidate timing
3. low battery cost
4. false-positive recovery UX
5. iOS/Android 의미론 parity

엔진은 UI/SDK에서 분리해 deterministic state machine으로 테스트한다.

## 2. Fundamental Limitation
공개 iOS/Android activity APIs는 `차량 이동`을 알려줄 수 있지만 **사용자의 자가용인지**는 보장하지 않는다.
포함 가능:
- taxi
- bus
- rideshare
- friend's car

따라서 trusted vehicle signal이 없는 기본 UX는:
> 주차한 것 같아요.

Android의 Activity Recognition Transition API가 IN_VEHICLE -> WALKING을 직접 제공해도 이 한계는 동일하다.

## 3. Common States
```text
IDLE
DRIVING_CANDIDATE
DRIVING
PARKING_TRANSITION
CANDIDATE_PENDING
PARKED
DEPARTURE_CANDIDATE
```

### IDLE
Low-power monitoring only.
- iOS: low-power location/motion strategy
- Android: activity transitions registered, no permanent high-rate GPS

### DRIVING_CANDIDATE
Vehicle evidence appeared. Validate duration/distance/context.

### DRIVING
Meaningful vehicle session confirmed. Begin bounded location capture.

### PARKING_TRANSITION
Vehicle activity ended/low speed. Wait bounded window for walking/stationary/location stop.

### CANDIDATE_PENDING
Persist candidate + notify.

### PARKED
User-confirmed or policy-confirmed active parking — or one the user saved themselves (§11c).

### DEPARTURE_CANDIDATE
New meaningful vehicle session while PARKED.

## 3a. Transitions

Section 3 describes what each state *is*. This says what moves between them, because
"vehicle evidence appeared" and "validate duration/distance" are sentences two engines
would each read differently — and this project has already had two platforms diverge from
a contract that left a decision open.

Every threshold below either points at the section that already fixes it, or is named here
as a starting constant. **A constant marked `unvalidated` is a hypothesis**: no
above-ground drive has been replayed against it yet (§18), and it is expected to move once
field data exists. Neither platform may pick its own value for one.

| from | to | condition |
|---|---|---|
| `IDLE` | `DRIVING_CANDIDATE` | `vehicle_enter` |
| `DRIVING_CANDIDATE` | `DRIVING` | vehicle activity sustained ≥ `minimumVehicleDuration` |
| `DRIVING_CANDIDATE` | `IDLE` | `vehicle_exit`, or no promotion within `drivingCandidateWindow` |
| `DRIVING` | `PARKING_TRANSITION` | `vehicle_exit`, **or** no movement evidence for `movementIdleWindow`, measured from the last moving sample or a later blind fix (see "A blind fix is not a stop") |
| `PARKING_TRANSITION` | `CANDIDATE_PENDING` | any of `walking_enter`, `stationary_enter`, location stop — within `transitionWindow` (location stop: see "The `PARKING_TRANSITION` rows, exactly") |
| `PARKING_TRANSITION` | `DRIVING` | movement evidence returns before `transitionWindow` elapses — a fix that clears §7's movement bar, or `vehicle_enter` |
| `PARKING_TRANSITION` | `IDLE` | `transitionWindow` elapses with no confirming signal |
| `CANDIDATE_PENDING` | `PARKED` | user confirms (§10a) |
| `CANDIDATE_PENDING` | `IDLE` | user rejects, or 45-minute expiry (§10) |
| `CANDIDATE_PENDING` | `DRIVING_CANDIDATE` | `vehicle_enter` — a new journey starts |
| `CANDIDATE_PENDING` | `DRIVING` | a **stop-only** candidate, before `transitionWindow` has run from the drive's end: a second fix that *reports* ≥ 2.0 m/s, or `vehicle_enter` — the long light ending (see "A stop-only candidate can still be a long light") |
| `PARKED` | `DEPARTURE_CANDIDATE` | vehicle ≥ 90s **and** movement ≥ 500m (§11) |
| `DEPARTURE_CANDIDATE` | `DRIVING` | departure confirmed (§11) |
| `DEPARTURE_CANDIDATE` | `PARKED` | evidence lapses |
| *any* | `PARKED` | `user_saved` — the user saved a parking themselves (§11c) |
| *any* | `PARKED` | `user_kept_parking` — the user answered a departure proposal with 아직 주차 중 (§11a); the `user_saved` row without a new record |

### Constants

| name | value | source |
|---|---|---|
| `minimumVehicleDuration` | 90s | §11 uses 90s for departure; entry uses the same bar so one direction cannot be laxer than the other |
| `drivingCandidateWindow` | 300s | §7 `vehicleEvidenceMaxAge` — evidence older than this is already not counted |
| `movementIdleWindow` | 180s | §7 `maximumBaseline`. **unvalidated** |
| `transitionWindow` | 300s | §7 vehicle window, reused so a walk that starts late still counts. **unvalidated** |
| `blindAccuracyMeters` | 80 m | "A blind fix is not a stop": a speedless fix coarser than this cannot show movement or stillness. Field tunnels 89–2,300 m, a parked car in a garage 30–55 m. **unvalidated** |
| `nearEndHorizon` | 300s | §8b: how far before the drive's end "near end" evidence may lie. `transitionWindow` reused. **unvalidated** |
| `vehicleEvidenceTimeout` | 600s | silence bound on a session: no vehicle evidence for this long ends it even if no walk ever arrives. Long enough to survive a tunnel or a long queue, where the vehicle signal can drop for minutes; short enough that a missed walk costs one bounded session, not hours of GPS. iOS `DrivingSessionTimeoutPolicy` applies it to a live session (the adapter's `.vehicleEvidenceExpired`) and to every restore; Android uses it (`VEHICLE_EVIDENCE_TIMEOUT_MILLIS`) only to drop a stale `PARKED` get-in at a system reset (§14). Not a §3a window: no fixture reaches it. **unvalidated** |
| `sessionMaximumDuration` | 2h | hard ceiling on one `DRIVING` session. Longer than any ordinary commute, far shorter than a day; without it a drive that never sees another fix keeps the location capture up for ever (§19). Ends in `IDLE` with no candidate — two hours in, nothing knows where the car was left. Android gained it 2026-09-21; iOS always had it |

### The car link

A phone attached to a car — by Android Auto / CarPlay projection, or by Bluetooth to the
car's audio system — is the strongest signal this product can get, and the only one that
knows the *moment* the driver leaves. Motion heuristics infer parking minutes later, from
absence. A disconnect is an event.

| from | to | condition |
|---|---|---|
| `IDLE` | `DRIVING_CANDIDATE` | `projection_connected` or `bluetooth_car_connected` |
| `DRIVING` | `CANDIDATE_PENDING` | `projection_disconnected` or `bluetooth_car_disconnected` |
| `CANDIDATE_PENDING` | `DRIVING` | a car link reconnects |
| `DRIVING_CANDIDATE` | `IDLE` | a car link disconnects — the driver got in and changed their mind; same outcome as `vehicle_exit` (2026-09-27) |
| `PARKING_TRANSITION` | `CANDIDATE_PENDING` | a car link disconnects — §6's fourth confirming signal, and the vehicle's end (2026-09-27) |
| `PARKING_TRANSITION` | `DRIVING` | a car link reconnects — the red light ending, told by the strongest signal there is (2026-09-27) |

The last three rows were implemented on Android and absent on iOS until 2026-09-27; they are
now the rule on both. A disconnect or connect in any state not listed is a no-op for the
state machine, and only updates the latch below. The latch is a property of the **link**, not
of the travel session: it survives a session ending in `IDLE`, a confirmation or a manual save
while the link is still up (iOS keeps it on the engine; Android keeps it on the session and
must hoist it).

Connecting does **not** promote straight to `DRIVING`: people sit in parked cars. The
90-second sustain in §3a still applies, so getting in and changing your mind produces
nothing.

Disconnecting **does** go straight to `CANDIDATE_PENDING`, skipping `PARKING_TRANSITION`.
Waiting for a walk would lose exactly the case §3a was corrected for — an underground car
park where no walk is ever detected — and the link has already told us the engine stopped
and the phone left the car.

The reconnect row is what makes a fuel stop safe (§17 fixture #3): disconnect, pump, get
back in, and the candidate is retired and its notification withdrawn before it is worth
anything. It is also why `CANDIDATE_PENDING → DRIVING` exists at all.

Reason codes: `car_projection_disconnected` already covers both, since §4 is closed and the
product distinction — the phone was attached to a car and stopped being attached — is the
same. The *kind* of link belongs in §8 weighting, not in a new code.

#### Platform reality

These are not equally available, and the contract says so rather than pretending:

- **Android** can observe both. `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` with a
  `BluetoothClass` of `AUDIO_VIDEO_CAR_AUDIO` or `AUDIO_VIDEO_HANDSFREE` identifies a car
  device, and it works from a broadcast receiver in the background.
- **iOS does not expose classic Bluetooth connect/disconnect as an event.** CoreBluetooth
  is BLE-only, ExternalAccessory needs an MFi accessory, and AccessorySetupKit matches
  declared BLE/Wi-Fi accessories. But the earlier claim that the audio route costs an
  audio session was only half right, and the half that is wrong matters:
  `AVAudioSession.currentRoute` is **read-only and needs no activation**, and `.carAudio`
  covers CarPlay and car Bluetooth alike — so *sampling* the link on each wake is free.
  What needs an audio session is background route-*change* callbacks, which this app does
  not buy. iOS therefore polls where Android observes.
  There is also `com.apple.developer.carplay-parking`, a CarPlay entitlement category
  matched to exactly this product; granted, it would turn the projection row into a real
  background event rather than a poll.

So Bluetooth is an **optional vehicle signal** — the property `optionalVehicleSignal` in
docs/17 §3 already anticipated one. Where it exists the engine becomes far more accurate;
where it does not, nothing regresses, because every §3a transition still stands on motion
and location alone. No fixture may depend on a link event being present.

Before building the iOS side, confirm the limitation against current SDKs rather than
taking this paragraph's word for it, and report what you find.

### Movement evidence does not gate promotion

An earlier draft of this table required movement evidence as well as sustained vehicle
activity to reach `DRIVING`. Replaying the three real drives recorded on 2026-09-19
against it showed why that is wrong: the 14:26 trip is the textbook signature —
`vehicle_enter`, `vehicle_exit` seven minutes later, `walking_enter` after that — and it
carries **zero location events**. Gated on movement it never leaves `DRIVING_CANDIDATE`,
and the parking is never detected.

That is not an edge case. §13 and the notes around §7 say underground car parks, tunnels
and urban canyons are this product's main setting, and those are exactly the places GPS
Doppler speed does not arrive. A rule that needs movement evidence to believe the OS is a
rule that fails where the app is most needed.

Movement evidence still matters — it is what §8 weighs and what separates a real trip from
a phone on a desk — but it belongs in the confidence bucket (§9), not in the transition.
A drive with no fixes can reach `CANDIDATE_PENDING` with lower confidence; it cannot be
made invisible.

### When a timeout fires

**Current contract (rewritten 2026-09-27; the history below explains how it got here).**

Every event — a motion edge, a fix, a tick — is processed as:

```text
ingest the evidence it carries -> judge the windows -> apply its edge -> judge the windows
```

against **the event's own timestamp**. Elapsed-time rows (`drivingCandidateWindow`,
`movementIdleWindow`, `transitionWindow`, `sessionMaximumDuration`, the 45-minute expiry, the
§11 departure lapse) are therefore judged on *every* event, not only on `timer_tick`.
`timer_tick` carries no evidence; it exists so the windows are judged when nothing else is
arriving — both platforms tick once a minute while, and only while, the bounded capture runs.

**A window row is stamped at its deadline, not at the event that noticed it** (2026-09-27).
`movementIdle` enters `PARKING_TRANSITION` at `lastMovingSample + movementIdleWindow`; the
transition lapses at `entry + transitionWindow`; `DRIVING_CANDIDATE` lapses at `entry +
drivingCandidateWindow`; the 2-hour ceiling at `sessionStart + 2h`; the candidate expires at
its `expiresAt`; the departure lapses at `lastVehicleEvidence + drivingCandidateWindow`. The
stamp is clamped to be no earlier than the current state's own entry and no later than the
event being processed. Stamped at observation, the next window started late by however long
the device happened to be quiet, so a replay with and without an unrelated tick produced
different outcomes — the non-determinism the history below was fighting. Sustained-condition
rows (`DRIVING_CANDIDATE → DRIVING`, `PARKED → DEPARTURE_CANDIDATE`) and event rows keep the
event's time.

Folding the event's evidence **before** the windows is the half that is easy to get wrong: a
fix that lands more than `movementIdleWindow` after the last one is a drive continuing, and
judging the window first would read it as a parking (`red_light_no_candidate`).

**Known iOS deviation, accepted (2026-09-27).** iOS reads Core Motion *history* in arrears:
on a wake it learns about a walk that happened minutes ago. Its coordinator therefore judges
the windows at the wake time and applies history-derived motion edges at the wake time, while
Android judges each Transition API event at its own `atMillis`. A walk timestamped inside
`transitionWindow` but first delivered after it confirms on Android and not on iOS. Fixtures
cannot see this — the iOS runner uses event time. Fixing it means replaying history samples
in time order interleaved with ticks, which is an adapter rewrite; while the capture runs, the
60-second tick keeps the two clocks within a minute of each other, which bounds the effect.

#### History: the 2026-09-20 reading, and why it was replaced

The paragraphs below are the record of how the contract above was reached. The first of
them — "fires **only on a `timer_tick` event**" — was the rule until 2026-09-21 and is no
longer; read it as history.

> Every elapsed-time row fires only on a `timer_tick` event. The alternative — evaluating
> elapsed time whenever any event happens to arrive — makes the same trace replay differently
> depending on whether something unrelated woke the engine. `subway_commute_underground` is
> the proof: it has a 303-second gap after `vehicle_enter` and a 1012-second gap during the
> ride, and under arrival-time evaluation it ends in `IDLE` instead of `CANDIDATE_PENDING`.

#### RESOLVED 2026-09-21: nothing on Android produced one, and firing them broke the subway trace

Two facts, both measured on 2026-09-20, that this section does not currently reconcile.

**1. Android never ticks in production.** `ParkingDetectionRuntime.handleTick` has test
callers only. `drivingCandidateWindow`, `movementIdleWindow` and `transitionWindow` are
therefore dead in the shipped Android app — a drive that ends underground with no
`vehicle_exit` stays in `DRIVING` for ever, which is the 14:26 real trace. iOS ticks at
`now` on every motion wake, so the two platforms produce different products from the same
engine, and no fixture can see it: fixtures replay against the engine, and the engine is
not where the difference is.

**2. Making them fire retires the subway trip.** Replaying the five committed fixtures with
a tick before every event:

| fixture | no tick | tick before each event |
|---|---|---|
| `bus_repeated_stops_no_storm` | `DRIVING`, 0 | `DRIVING`, 0 |
| `red_light_no_candidate` | `DRIVING`, 0 | `DRIVING`, 0 |
| `subway_commute_underground` | `CANDIDATE_PENDING`, 1 | **`IDLE`, 0** |
| `tunnel_no_parking` | `DRIVING`, 0 | `DRIVING`, 0 |
| `vehicle_then_walk` | `CANDIDATE_PENDING`, 1 | `CANDIDATE_PENDING`, 1 |

The path it takes, traced event by event:

```text
t=6404  vehicle_enter   IDLE               -> DRIVING_CANDIDATE
t=6707  timer_tick      DRIVING_CANDIDATE  -> PARKING_TRANSITION
t=7719  timer_tick      PARKING_TRANSITION -> IDLE
```

**It is not `drivingCandidateWindow`.** That row sits below the promotion check, exactly as
`fromDrivingCandidate`'s comment says, so the 303-second silence promotes the session
rather than retiring it. What happens at `t=6707` is that the promotion re-reads the same
tick in `DRIVING`, and there `movementIdleWindow` — 180s — has already elapsed. Then
`transitionWindow` — 300s — expires at `t=7719`, 1392 seconds before the walk at `t=9111`
that would have answered it.

**The real problem is that underground there is no movement evidence to have.**
`lastMovementEvidenceAtMillis` only advances on a location fix that clears §7's bar. In a
tunnel, a subway or an underground car park there are no fixes at all, so the moment
anything ticks, `movementIdleWindow` fires — not because the car stopped, but because the
sky is gone. §3a already states the matching rule one section up, for promotion: "Movement
evidence does not gate promotion." The idle window is the same claim in reverse and carries
no such caveat.

#### DECIDED 2026-09-20: a connected car link suppresses `movementIdleWindow`

The product owner's answer, and it is better than the three this section first offered:
**if Bluetooth is still connected to the car, the car has not been parked.** A phone attached
to the car's audio system during a 180-second gap is at a red light, in a tunnel or on a
ramp — it is not a car that has been left.

So `DRIVING → PARKING_TRANSITION` on `movementIdleWindow` does not fire while a car link is
connected. §3a already called the link "the strongest signal this product can get"; this is
that sentence applied to the one row that infers a parking from *absence*.

**Only that row**, and the boundary matters:

| row | gated? | why |
|---|---|---|
| `movementIdleWindow` | **yes** | it infers a parking from silence, and the link says the car is running |
| `drivingCandidateWindow` | no | a link with no drive is someone sitting in a parked car with the radio on — which is precisely what this row exists to retire |
| `transitionWindow` | no | it abandons a suspected parking; keeping it open costs an open session and buys nothing |
| session maximum duration (2h) | no | a bound on the session itself, not an inference about the car |
| 45-minute candidate expiry | no | a candidate only exists after a disconnect |

The second row is the counter-example that fixed the rule's scope: two existing tests
(`Connecting does not skip the 90-second promotion`, `DRIVING_CANDIDATE → IDLE when
drivingCandidateWindow passes with no promotion`) both connect a link and never drive, and
both expect the session retired. A blanket suppression broke them, correctly.

##### The latch must not outlive the link
A connect that is never followed by a disconnect would suppress timeouts for ever and kill
detection outright — worse than the bug it fixes. The engine is pure and cannot poll, so the
obligation is the adapter's: **on process start, and on every wake, the adapter re-asserts
the link's real state and feeds a disconnect if it is gone.** Android can read it
(`BluetoothProfile` connection state for `AUDIO_VIDEO_CAR_AUDIO` / `AUDIO_VIDEO_HANDSFREE`);
iOS already samples `AVAudioSession.currentRoute` at every wake and derives the edge, so on
that platform the latch is a sample by construction.

##### What this does not fix
`subway_commute_underground` has no link events, so it is unchanged by the link rule: a car
with no Bluetooth pairing, underground, is in the same position. That residual is the
narrower question this section opened — whether an absent location fix may stand in for
absent movement when there is no link to ask — and it is answered below.

#### DECIDED 2026-09-21: an absent fix is not absent movement

**No.** `movementIdleWindow` does not fire on a session that has never had a fix clearing
§7's movement bar. The row means "movement stopped", and a drive that never produced a
moving sample has no movement that could have stopped. Underground there are no fixes at
all, so seeding the anchor with the session start makes "no sky" read as "not moving" — and
that, not `drivingCandidateWindow`, is what retired the subway trip at `t=6707`.

**iOS has always read it this way.** `ParkingTransitionPolicy.isMovementIdle` returns false
for a nil `lastMovingSampleAt`, and the row is additionally gated on a *confirmed* session.
Android's `lastMovementEvidenceAtMillis` was a non-null `Long` seeded with the session
start, so the two engines disagreed about the same row while every fixture passed — because
no fixture ticked. It is now `Long?`, with `null` meaning "never moved".

**Android therefore ticks in production, and the ticks come from the events themselves.**
The engine settles the timeout rows against each event's own timestamp, after folding that
event's evidence:

```text
fold(event) -> edge transition -> settle timeouts to a fixed point
```

Folding first is the half that is easy to get wrong: a batch opened with a tick judges the
windows *before* the fix that would have advanced them, which is the 2026-09-20 measurement
above. Settling after the edge keeps every committed fixture green, `subway_commute_underground`
included, and the Android suite's three tests that asserted the old reading were rewritten
to give the session a moving fix first — they had been encoding the bug.

The timeout rows now live in exactly one place on each platform (`timeoutRow` here,
`tickOnce` on iOS); the edge table no longer carries a `timer_tick` branch.

##### The scheduled half (2026-09-21)
**Android ticks once a minute while — and only while — the location foreground service is
up.** Per-event settling covers every drive that keeps producing events; the one that stops
is the case this is for: underground, no `vehicle_exit`, no fixes because there is no sky, no
walk transition delivered. Nothing ends that session, and the capture service stays up behind
it. Even the two-hour ceiling could not fire, because it too was only reached through an
event.

**A loop inside `DrivingLocationService`, not an `AlarmManager`.** The costly state and that
service have the same lifetime, so the tick exists exactly while the silence would cost
something and a parked phone ticks never — no alarms to schedule, no exact-alarm permission
to justify, nothing to leak. Doze does not apply while a foreground service runs, which is
the other reason it belongs there. One minute against a shortest window of 180 s gives three
chances at each boundary.

iOS has the same shape: `LiveDrivingLocationCapture` ticks the coordinator every 60 s for as
long as — and only as long as — the bounded capture runs, and background location updates
keep the process alive for exactly that span (docs/04_IOS §3a, 2026-09-24). Before that the
capture could not keep the process alive from a background start, so this sentence claimed a
wake that was not happening.

##### The windows are judged before the edge too (2026-09-21)

Both engines now run **ingest → windows → edge → windows** on every event, and the ordering
is the contract rather than an implementation detail.

Android's `fold` is split the way iOS's already was: `ingestEvidence` folds what the event
*observes* — a location fix, a quality degradation — and the session lifecycle a motion or
link edge implies is folded with the edge afterwards. That split is what lets the windows be
judged with this event's evidence in hand but without its transition already applied.

The case it fixes: a `walking_enter` arriving after `transitionWindow` has closed. iOS found
`IDLE` and confirmed nothing; Android found `PARKING_TRANSITION` still standing and opened a
candidate for a stop that had been abandoned minutes earlier. Held now by a test on each
side — `A walk that arrives after the window confirms nothing` and
`a walk that arrives after the transition window confirms nothing`.

Folding the evidence **first** remains the half that is easy to get wrong, and the reason is
above: a batch opened with a tick judges the windows before the fix that would have advanced
them, which is the 2026-09-20 measurement that retired the subway trip.

#### DECIDED 2026-10-01: a blind fix is not a stop

The 2026-09-21 rule covers a drive that never moved. The field drives of 2026-09-30 and
2026-10-01 found its mid-drive twin: a car doing 60 km/h through a tunnel or an underground
road, handed speedless Core Location fixes of 300–2,300 m for ten minutes. None of them can
clear §7's noise floor, so `lastMovingSample` stood still, `movementIdleWindow` fired, the
transition lapsed to `IDLE`, and the parking at the end of the drive was never looked for —
both engines, on replay.

**A fix with no reported speed and an accuracy coarser than `blindAccuracyMeters` is blind:
it can show neither movement nor stillness.** `movementIdleWindow` is measured from the later
of the last moving sample and the last blind fix. The rule adds nothing else:

- a blind fix does not *start* the clock — a drive that has never moved stays un-anchored;
- a blind fix is noted whether or not it passes the §5 outlier check (a coarse fix that jumps
  still says the sky is gone);
- a blind fix counts only while the window is still open. One that lands after
  `movementIdleWindow` already ran out says nothing about the silence before it, which the
  2026-09-21 rule reads as idle on a drive that has moved. This is also what keeps a relaunch —
  which settles the windows before folding the fix that woke it (§14) — agreeing with a
  process that never died; without it `field_s24` diverged on the restore replay;
- when the sky returns, a real stop is judged from there, so the clock restarts rather than
  stops.

**The threshold is not §2's `poor` (35 m).** Field s16 parks under a slab and reads 30–55 m
with no speed; at 35 m that parking stopped looking still and its candidate disappeared.
80 m sits between the garage (≤ 55 m) and the tunnel (≥ 89 m).

**What it costs.** A car parked where every fix stays above 80 m — deep underground with only
cell positions — no longer ends its drive by `movementIdleWindow`. It ends by `vehicle_exit`
(Android's transition, iOS's derived exit after `vehicleEvidenceTimeout`), by a walk, or by the
two-hour ceiling. Pinned by `tunnel_blind_then_parks.json` (the replayed drive) and by the
unit tests "Blind fixes in a tunnel do not end a drive as movement idle", "A real stop after
the tunnel still ends the drive" and "A garage fix of 50 m is not blind" on both platforms.

### Leaving a pending candidate behind

`CANDIDATE_PENDING → DRIVING_CANDIDATE` on `vehicle_enter` exists because a candidate can
be ignored. Without that row, driving away ten minutes after a prompt left the engine
parked in `CANDIDATE_PENDING` for up to forty-five minutes with detection dead — and §10a
already presupposes the row by describing what happens when a *new journey* produces a
candidate while an old one is pending.

The old candidate is **not** retired at `vehicle_enter`. It stays answerable, and is
superseded only when the new session actually produces a candidate (§10a). `vehicle_enter`
is a noisy signal — a bus passing, a passenger seat, the OS guessing — and retiring a
prompt on it would delete the answer to a question the user was still holding.

**The new journey is a new travel session** (2026-09-27). Nothing of the previous trip carries
into it — not its reason codes, its start (which feeds §8's duration and the 2-hour ceiling),
its distance, nor its movement anchors. Android reused the previous trip's session here, so
field draft s03's second parking inherited `walking_after_vehicle` and `vehicle_exit_detected`
from the first and scored `high` on evidence it never had. The one thing kept is the pending
candidate itself, for §10a's supersession and §10's expiry.

**A recording can hold two travel sessions, and s03 is one (DECIDED 2026-09-27).** Field draft
s03 has a 128 s ride, `vehicle_exit` and `walking_enter` in the same second (t=1431, the exit
iOS derives from that walk), a candidate (`medium`/70), and a `vehicle_enter` five seconds
later that opens the fifty-minute drive ending in the user-confirmed parking. Both engines
make two candidates, and both are right under this table:

- At t=1431 the evidence is an exit and a walk after a confirmed drive — the §8a textbook
  parking. Nothing observable separates "the walk was Core Motion flicker" from "parked, then
  got straight into someone else's car", and the second must not be lost (a carpool pick-up,
  park and ride). A delay long enough to wait for the car to move would delay every real
  parking by as much.
- The first candidate is not the stop-only kind (it has an exit and a walk), so the car moving
  on does not retire it; the `vehicle_enter` opens a new journey and leaves it answerable. It
  expires at t=4131 (§10, whatever the state), before the second candidate at t=4482.

§12's rule is one candidate **per travel session**, and s03 has two. The iOS storm check was
stricter than §12 — "no fixture produces more than one candidate" — and is now what §12 says:
count candidates per travel session (a session opens on entering `DRIVING_CANDIDATE` and on a
confirmed departure), and do not count a candidate the session itself took back (a link
reconnect, a stop-only retirement, an expiry) — only one superseded by the next. The Android
twin used to count per fixture and failed s03 while iOS passed it — the gate itself out of
parity; contract §8 now spells the counter out step by step and both runners implement that. s03's cost is a
stale `medium` prompt while the user drives; "주차 아님" answers it, and §18 should measure how
often a walk is followed by `vehicle_enter` within a minute before any rule is built on it.

The car-link reconnect (`CANDIDATE_PENDING → DRIVING`, the fuel stop) is the opposite case:
getting back into the same car continues **the same trip**, so it resumes the drive the
candidate came from — its start and distance — with a fresh idle anchor.

### The red light

`DRIVING → PARKING_TRANSITION → DRIVING` is the path a long stop takes, and it is why
`PARKING_TRANSITION` exists as its own state rather than being folded into the candidate
(§3 of the domain contract). Entering it is silent: nothing is persisted, nothing is
notified. Fixture #2 in §17 exists to hold this.

### The `PARKING_TRANSITION` rows, exactly (DECIDED 2026-09-27)

Replaying 18 field drafts (iPhone, 2026-09) through both engines found that every iOS-only
failure — s03, s16, s33 stuck in `PARKING_TRANSITION` or lost to `IDLE` — began at the same
place: a location fix arriving while both engines were in `PARKING_TRANSITION`. Android had
the two location rows below; iOS had neither, and dropped every fix after the drive ended.
These are now the rows, identically on both platforms.

**Location stop** (→ `CANDIDATE_PENDING`). A fix that
1. the drive's §5 gate accepted — valid accuracy, not an outlier step,
2. **reported** a speed below `movingSpeedThreshold` (2.0 m/s) — a fix with no speed is not a
   stop: underground there is no Doppler, and silence is not stillness (§7),
3. is timestamped **at or after** the transition's entry — the same rule every confirming
   signal shares; a walk from before the drive ended says nothing about this parking.

It confirms whatever the entry was, `movementIdle` included. s33 is why: the user walked
away at 1.0–1.3 m/s with fixes every 15 s and Core Motion never reported a walk. A stop found
this way alone scores `low` (§8b: no exit, no walk) and posts nothing (§9) — which is also
what keeps a long red light from notifying.

**Movement returns** (→ `DRIVING`). A fix the drive accepted that cleared §7's movement bar
— speed ≥ 2.0 m/s, or the distance fallback's verdict for a speedless fix — at or after the
entry. `vehicle_enter` and a car-link reconnect are the same row on a motion or link event.
A fix cannot be both a stop and movement, so the order of the two rows is immaterial.

**The capture keeps running while the transition decides.** Two of this state's three exits
are location rows, so stopping the bounded capture at the entry — which iOS did — made both
unreachable on a device while every fixture still passed. The capture is released when the
transition leaves by `CANDIDATE_PENDING` or `IDLE`, and kept when it resumes `DRIVING`. The
cost is at most `transitionWindow` (300 s) of capture per stop; see §19. An adapter-decided
end inside the transition (lost authorization, capture failure) stops the capture and leaves
motion free to confirm; the Smart Detection opt-out drops the transition to `IDLE`.

**Resuming keeps the drive.** `PARKING_TRANSITION → DRIVING` continues the *same* travel
session: its start (so the 2-hour ceiling, §8's duration and §5's inheritance bound measure
the trip), its distance, its confirmation and its anchors. iOS used to open a fresh drive
here, resetting all of them at every long light. A resume on vehicle or link evidence
re-anchors the idle clock at the resume (`lastMovingSample := max(lastMovingSample, resume)`),
so the drive gets a full `movementIdleWindow` — Android used to fall straight back into
`PARKING_TRANSITION` on the stale anchor. A drive that has never moved stays un-anchored
("an absent fix is not absent movement"). The transition's own evidence (§8b's exit, walk,
stop flags) is discarded on resume: that stop was a red light.

**`movementIdle` is not an exit.** The vehicle *level* stays on through a `movementIdle`
transition, so a `vehicle_exit` — or, on iOS, the exit the adapter derives from a walk — that
arrives while it is open is this drive's exit and earns `vehicle_exit_detected` (§8b). It is
not a confirming signal; the level ends when the transition leaves.

**Signals seen in `DRIVING` are not carried forward** (the F19 question, answered no). A
`walking_enter` or `stationary_enter` that arrives while still `DRIVING` — before
`movementIdle` has elapsed and with no `vehicle_exit` — is dropped, as before. Holding it and
confirming the moment `movementIdle` fires was considered and rejected: at a long light the
Transition API can report STILL inside a car, so the held signal would confirm the light, and
no field draft is fixed by it (s02 never reaches a transition; s06 has no motion event after
the drive). With dense capture the location-stop row already answers the case it was for.

### A stop-only candidate can still be a long light (DECIDED 2026-09-27)

Three minutes stopped with fixes reporting < 2 m/s is `movementIdle` plus a location stop, and
the location-stop row confirms it the instant the transition opens — a long light and a jam
look exactly like s16 and s33, which are real parkings. Nothing at that instant can tell them
apart; only what happens next can. Before this rule the candidate (silent, `low`) stood for 45
minutes, the capture was released, and on Android — whose Transition API does not repeat
IN_VEHICLE ENTER — the rest of the trip, including the real parking, could go undetected.

**Which candidates.** A candidate is *stop-only* when nothing but absence ended the drive: its
transition was entered by `movementIdle`, and no exit (`vehicle_exit`, the iOS-derived exits,
a car-link disconnect) and no `walking_enter` arrived before it was created. Its confirming
signal was a location stop or `stationary_enter` (the Transition API can report STILL inside
a car at a light). The vehicle level never ended, so the car moving again is this drive
continuing. A walk is excluded: it is the strongest evidence the person left the car, and
movement after it is more likely someone else's vehicle (park and ride, a lift) — retiring a
real parking on that would lose the one thing this product exists to keep.

**The rule.** From the drive's end (the transition's entry) until `end + transitionWindow` —
the deadline the transition itself had:

1. the bounded capture keeps running (it is released when the transition leaves otherwise),
   and the candidate's drive keeps recording fixes (§5 gate, anchors, distance) — but §6's
   reliable-location selection does **not** run on them: the candidate's spot is the car, and
   the person walking away from it must not drag the point a later candidate of this trip
   would inherit;
2. the **second** accepted fix timestamped at or after the end that **reports** a speed ≥
   `movingSpeedThreshold` (2.0 m/s) — §7's "one event alone never confirms"
   (`minimumMovingSamples`, 2), because a single Doppler spike under a slab is ordinary — or a
   `vehicle_enter`, retires the candidate and resumes
   the same drive: `CANDIDATE_PENDING → DRIVING` in one step, the candidate withdrawn as §10
   withdraws an expired one (no record, no report), the travel session kept (start, distance,
   confirmation, anchors) exactly as "Resuming keeps the drive" keeps it, §12's allowance
   restored so the trip can still produce the real parking. On `vehicle_enter` the idle clock
   is re-anchored at the resume; a moving fix anchors it itself;
3. at the deadline the capture is released and nothing else changes — the candidate stands;
4. the window closes early, releasing the capture, on a `vehicle_exit`, a `walking_enter`
   (round 3, 2026-09-27), or any adapter-decided end (derived exit, lost authorization or
   capture, the opt-out); a confirm, reject or `user_saved` closes it with the state. The
   candidate stands in every case. A walk closes it for the same reason a walk *before* the
   candidate makes it not stop-only: the person has left the car, so a `vehicle_enter` after
   it is a bus or a lift — `CANDIDATE_PENDING → DRIVING_CANDIDATE`, a new journey that leaves
   the candidate answerable — and not the jam moving on. On an iPhone the adapter already
   closed the window through the exit it derives from the walk; the engine now states it
   itself, so Android (no derived exit) and every fixture read the same rule.
   `stationary_enter` does **not** close it: the Transition API reports STILL inside a car
   at a light.

**Exactly what the resume emits** (both engines, one `handle` call, in this order):

1. `withdrawCandidate(id)` / `RetireCandidate` for the pending candidate — never followed
   immediately by a `createCandidate`, which is how contract §8's storm counter tells this
   from a supersession;
2. one `persistCheckpoint` with `state = DRIVING` and no `candidateId`;
3. no `startBoundedLocationCapture`: an open window always holds its capture (below), so
   the resumed drive already has one.

No `drivingConfirmed` and no `sessionEnded`: the drive never ended as far as the product is
concerned, and it is already confirmed. The session kept is the candidate's own (start,
distance, confirmation, anchors, vehicle level on); §12's "already produced" flag is cleared.
On `vehicle_enter` the idle clock is re-anchored at `max(lastMovingSample, now)`; on the
second moving fix nothing is re-anchored — that fix, already folded into the session, is the
newest moving sample. The reported-moving count is the window's own (it starts at 0 when the
candidate is created and counts only fixes timestamped at or after the drive's end), not
§7's `movingSampleCount`.

**Only a reported speed.** The §7 distance fallback does not count here, although it does in
the transition's "movement returns" row. Around a parked car the sky is worst: after s03's
real parking the speedless fixes of the walk away (accuracy 8–100 m) put the fix at t=4673
332 m from the fallback's anchor in 165 s on the replay's straight line — 2.01 m/s, "travel" —
inside the window, and with the fallback counted s03 would end in `DRIVING` instead of with
its parking. In the transition a false resume only delays a decision; here it would withdraw a
real parking.

**The window lives exactly as long as its capture (round 4, 2026-09-27; R4-B1 closed
2026-09-27).** It is never opened without the capture: a transition that lost its capture
(lost authorization, capture failure) and is then confirmed by a stop-only signal produces
its candidate with **no** window, so a `vehicle_enter` after it is "Leaving a pending
candidate behind" — `CANDIDATE_PENDING → DRIVING_CANDIDATE`, candidate kept — on both
platforms. iOS applies this when the candidate is created (`resumable` requires
`transition.isCapturing`); Android's engine records the window and its runtime closes it
before the next batch because the capture is not running — the same product outcome. Pinned
by iOS `ParkingTransitionEvidenceTests` "A stop-only candidate whose transition lost its
capture opens no resume window"; its Android twin, same name and events, is
`ParkingDetectionRuntimeTest` "a stop-only candidate whose transition lost its capture opens no
resume window", which pins the sequence at the runtime level. The window is part of the
engine state both platforms persist (§14), and **across a process death it is carried by the
capture the relaunch reopens** (DECIDED 2026-09-29, replaces round 4's "a relaunch closes it"):
a relaunch inside the window restores it verbatim and reopens the bounded capture the restored
state wants, so the window resumes and withdraws exactly as it would have in the process that
opened it — restore equals uninterrupted (§14). Rule 4's lost capture is a capture that
*fails*, not one that ended with its process and is reopened at once.

- **iOS** — the Core Location session dies with the process, and `restore` reopens it
  (`startBoundedLocationCapture`, §14 "The capture did not survive", step 3). A window whose
  deadline passed while the process was dead is settled by the restore (step 2): closed, the
  candidate kept, no capture reopened.
- **Android** — the Fused Location request is registered with a `PendingIntent` and survives
  a process death, so nothing happens at the relaunch. After a reboot, package replace or
  force-stop the same rule applies as on iOS: the capture the stored state wants is reopened
  and carries the window; a window whose capture cannot be reopened (a revoked permission,
  "only while using" on Android 10+ — `FusedLocationSessionController.isCaptureRunning`) is
  rule 4's lost capture and closes, the candidate kept.

Neither difference is visible to a fixture (fixtures have no process death). Both are bounded
by the same deadline: nothing resumes after `end + transitionWindow`, and the capture is
released there. Making Android drop the window on every process start was rejected: its
process routinely dies between two location broadcasts, so the rule would almost never fire on
Android, and the long-light case it exists for would come back on the platform whose
Transition API does not repeat IN_VEHICLE ENTER. Battery: at most `transitionWindow` of
capture per stop, the same bound the transition already had (§19).

**Measured on the field drafts (iOS, 2026-09-27):** no parked draft changes. The three
stop-only candidates (s03's second, s16, s33) see no fix reporting ≥ 2 m/s and no
`vehicle_enter` inside their windows. The committed negative fixture for the shape is §17
`long_stop_in_traffic`.

**Residual.** Someone hurrying away from a stop-only parking at ≥ 2 m/s with Doppler speed
(a jog, 7.2 km/h) inside the window retires it; the resumed drive re-enters the transition
180 s after they slow down and a stop confirms it again — at wherever they then are. That is
the price of the rule, and the reason walk-confirmed candidates are excluded from it. A stop
longer than `movementIdleWindow + transitionWindow` (8 minutes) with no
movement still leaves a silent candidate behind, and a tunnel longer than that with no fix and
no car link ends in `IDLE` — §13's "tunnel must remain DRIVING" holds only while a link is
connected. Both are for §18 field tuning, not for a rule change on one trace.

### A stop-only candidate takes the exit that follows it (DECIDED 2026-10-01)

A location stop can confirm a `movementIdle` transition seconds before the exit and the walk
that say the same thing more strongly. On 2026-09-30 20:52 and 2026-10-01 10:36 the walk came
1–30 s after the stop, the candidate was scored without it, stayed `low` (45), and §9 posted
nothing: two real parkings the user never heard about.

**Until `transitionWindow` has run from the drive's end, a `vehicle_exit` or a `walking_enter`
re-scores a stop-only candidate.** The added code joins its reasons; if the bucket rises the
candidate is **upgraded in place** — `upgradeCandidate` / `UpgradeCandidate`, the same id,
detection time, expiry and location. It is not a new candidate: nothing is withdrawn, the
history gains no `응답 없음` row, `parking_candidate_created` is not reported twice, and the
notification is posted only the first time the candidate qualifies — so `low → medium → high`
on one parking buzzes once. A code that does not move the bucket is kept, so an exit (+15)
followed a second later by a walk (+30) is credited with both. That is why this is its own
record (`CandidateRescore` / `StopOnlyRescore`) and not part of the resume window: the exit
closes the window, and the walk after it must still count.

**After the window it is not used.** The car may have driven on and parked somewhere else; an
exit there upgrading a candidate pinned to the old spot would notify the wrong place.

The storm counter ignores an upgrade (contract §8 rule 5); its outcome-trace label is
`upgrade <bucket> <codes>`. Pinned by
`stop_candidate_takes_following_exit.json` and by the unit tests "A walk before the drive's
window closes re-scores a stop-only candidate", "An exit and then a walk both count towards a
stop-only candidate" and the late-walk test on both platforms.

**Residual.** The 2026-09-30 08:06 drive met a long light at 08:37 between two tunnels: the
stop confirmed a silent candidate there, no second ≥ 2 m/s fix came inside the window, and
the parking at 08:51 was not found. Left for §18 tuning.

### Turning Smart Detection off (DECIDED 2026-09-28)

The opt-out is the one boundary the user controls, and it must stop **every** location
capture and close **every** window, on both platforms, the moment it is switched off — CLAUDE.md
"no continuous location" and §19. It is one engine event with one outcome per state: iOS
`endDrivingSession(reason: .smartDetectionDisabled)`, Android `DetectionEvent.SmartDetectionDisabled`,
answered before any evidence is folded or any window judged (like `user_saved`):

| state at the opt-out | result |
|---|---|
| `IDLE` | nothing |
| `DRIVING_CANDIDATE`, `DRIVING`, `PARKING_TRANSITION` | the drive is dropped: `IDLE`, no candidate (iOS reports `sessionEnded(smart_detection_disabled)`) |
| `CANDIDATE_PENDING` | a stop-only resume window closes; state and candidate stay (§10's 45 min still run) |
| `PARKED` | a get-in session is dropped; `PARKED` |
| `DEPARTURE_CANDIDATE` | `PARKED`, ending nothing, even with §7's guard met — turning detection off never closes a parking |

In every state the result wants no capture, the vehicle-activity level ends, a candidate left
behind by a new journey stays answerable, and **the car-link latch is cleared**: no link edge
reaches the engine while detection is off (below), so a kept latch could outlive its link and
hold `movementIdleWindow` off the next drive for good. The smallest rule that meets the goal:
dropping only the capture (adapter-side) would leave a `DRIVING` whose window rows still run on
the next event, and a new rule set per platform would be two rules to keep equal.

**While detection is off, nothing reaches the engine that could open a capture.** No motion
evidence, car-link edge or location fix is handed to it, and a relaunch while opted out ends
whatever a process that died mid-opt-out left behind — without opening its capture first. The
hand save (§11c) and the user's answer to a pending candidate still reach it: they open nothing.
iOS: `BackgroundCoordinator.setSmartDetectionEnabled` (set before `rehydrate` on every launch)
gates `rehydrate`'s motion replay, `handleSignificantChange`, `handleCarLink` and
`handleDrivingFix`, and forgets the observed links so the first route sample after switching back
on is a fresh edge. Android: one gate in `ParkingDetectionRuntime.handle(sensor = true)` drops
every sensor batch — motion transitions, location batches, car links and ticks — while
detection is off, and `TransitionEventIngestor.ingest` returns before touching the capture (the
receivers themselves hold no gate; the reboot path already refuses to reopen a capture the user
switched off). `DetectionRegistrationCoordinator.setDetectionEnabled(false)` feeds the opt-out
event and stops the location session, releasing the foreground service, under the same lock as
the toggle and only while detection is still off, so switching back on in between ends nothing
(`optInRacingAnOptOut_endsNothing`). Android
has no route to sample, so a link still connected when detection comes back on is heard at its
next edge — an OS difference, not an engine one.

**The relaunch sweep, per platform (DECIDED 2026-09-28).** The opt-out is two writes — the flag,
then the engine's `.smartDetectionDisabled` end — and a process can die between them (or the
second can fail). Whatever the engine state then holds is ended by the next launch that finds the
flag off, before anything else reaches the engine: iOS `rehydrate` restores the checkpoint without
opening its capture and applies the same end. Android does the same on app start and on
reboot/package-replace recovery: when `readDesiredEnabledOnce()` is false and the stored engine
state wants a capture or holds a session or window, the runtime's `handleSmartDetectionDisabled`
runs (the same call the Settings toggle makes), and a failure of that end is caught and logged so
it can never crash the Settings toggle — the next launch retries it. A stored state that wants
nothing and holds nothing is left alone (no write). Without this, a stale `DRIVING` or stop-only
window survived every gated batch and was judged against its old windows the day detection came
back on — a different product outcome from iOS. Twins: iOS `DrivingSessionLifecycleTests` / Android
`ParkingDetectionRuntimeTest` "A relaunch while Smart Detection is off ends the session a dead
process left open" (flag off with `DRIVING` stored, fresh runtime → `IDLE`, no capture).

Pinned on iOS by `ParkingTransitionEvidenceTests` "Opting out during DRIVING ends the session in
IDLE and stops the capture" and "Opting out inside a stop-only window closes it and keeps the
candidate" (then `vehicle_enter` → `DRIVING_CANDIDATE`, candidate not withdrawn),
`DepartureTests` "The opt-out inside a departure returns to PARKED and ends nothing", and
`DrivingSessionLifecycleTests` "While Smart Detection is off a car link connect opens no
capture", "A relaunch while Smart Detection is off replays no motion into the engine", "A
relaunch while Smart Detection is off ends the session a dead process left open" and "Turning
Smart Detection back on hears a link that is already connected". Android's twins carry the
engine-row names above (`ParkingDetectionEngineTest` `opting out during DRIVING ends the session
in IDLE and stops the capture`, `opting out inside a stop-only window closes it and keeps the
candidate`, `the opt-out inside a departure returns to PARKED and ends nothing`), and its
entry-point gates are pinned by `ParkingDetectionRuntimeTest` `a car link while opted out opens
no capture` and `a location batch while Smart Detection is off feeds nothing and releases the
capture`.

### One candidate per travel session

§12 requires it. Concretely: leaving `CANDIDATE_PENDING` by rejection or expiry returns to
`IDLE`, and a `DRIVING` session that has already produced a candidate cannot produce a
second one — the trip must pass through `IDLE` first. This is what stops a bus with
repeated stops from becoming a notification storm (fixture #5).

### Reason codes

Codes accumulate as evidence arrives and travel with the candidate; they are never
recomputed at the end from the final state. "Near end" evidence (§8b) is not an exception: it is judged
from the *timestamps* of evidence already recorded, against the moment the drive ended — not
from the state the device happens to be in when the candidate is created. The §4 list in the domain contract is closed —
an engine that needs a code that is not on it has found a contract gap, and the answer is
to raise it, not to add a string.

## 4. Platform Signal Mapping

### iOS
- Core Motion automotive/walking/stationary
- Core Location samples/significant changes
- optional CarPlay connection evidence

### Android
- Activity Recognition Transition API: IN_VEHICLE/WALKING/STILL
- Fused Location Provider samples
- optional CarConnection projection evidence

All mapped to events in `05_CROSS_PLATFORM_DOMAIN_CONTRACT.md`.

## 5. Location Sample Validation
Common fields:
- timestamp
- coordinates(local only)
- horizontalAccuracy
- speed optional
- source/platform metadata internal only

Rules:
- negative accuracy invalid
- stale sample excluded from live evidence
- impossible speed/distance outliers rejected
- poor samples must not overwrite lastReliableLocation

### Cached-fix replay — 양 플랫폼 필수 가드
OS는 위치 모니터링을 시작하는 순간 **캐시된 마지막 fix를 즉시 한 번 전달**한다.
이 fix는 임의로 오래됐을 수 있다. iOS 실기기 M0A-1 관측에서 앱 설치(12:12)보다
**3시간 20분 이른 08:51 fix**가 전달됐고, 정확도가 8m로 양호했기 때문에
`negative accuracy` 검사를 그대로 통과해 live evidence로 기록됐다.

정확도만으로는 잡을 수 없다. 캐시된 fix는 대체로 *좋은* fix이고, 단지 현재가 아닐 뿐이다.
따라서 **타임스탬프 기반 freshness 가드를 반드시 둔다.**

### The fix a candidate inherits must belong to the drive that just ended

Measured on Android on 2026-09-20: a candidate created at 17:32 carried a fix captured at
**12:00** — five and a half hours and an unknown number of kilometres earlier. It was the
last fix good enough to be admitted all day, and nothing aged it out.

The guards above bound **admission**. Nothing bounded **use**. `lastReliableLocation` is a
running value on the state, and `openCandidateOrEndSession` attached whatever it held.

A candidate with a wrong coordinate is worse than a candidate with none. The confirmation
screen draws that coordinate on a map and prints its accuracy beside it (docs/10 §7a), so a
stale fix is not a blank — it is a confident lie, and `위치 없음` is a state the screen
already renders properly.

**Two conditions, both required, or the candidate is created with no location:**

| condition | what it catches |
|---|---|
| `capturedAt >= session.vehicleActivityStartedAt` | a fix from a *previous* trip, or from the origin before this one began. The origin is not the destination |
| `now - capturedAt <= staleLocationWindow` | a long drive whose only good fix came near the start. Being on the motorway at minute two says nothing about where the car stopped at minute ninety |

`staleLocationWindow` = **600s**, **unvalidated**, in the same spirit as §3a's other
constants. The budget it has to cover is: the descent into a garage where the sky is lost
(0–5 min), the stop and the walk that confirms it (1–3 min), and the platform's own
transition delivery delay — 17s on the Android device that produced this trace. Tune it from
field data; the cost of it being too tight is `위치 없음` on a parking that had a usable fix,
and the cost of it being too loose is the 17:32 candidate above.

A candidate that loses its fix this way keeps everything else. It is still a candidate, it
still notifies, and it still becomes a record — one saved without a location, which FR-001
already calls an ordinary outcome.

- 기본값: 수신 시점 기준 **300초** 초과 시 live evidence에서 제외
- 시계 오차 허용: 미래 방향 5초까지
- §6의 20초 기준은 **bounded driving session 전용**이며 이 경로에 재사용하지 않는다.
  실제 significant change는 앱이 suspend된 탓에 수 분 늦게 도달할 수 있고, 그건
  fix의 결함이 아니다
- 제외한 샘플은 **조용히 버리지 말고 카운트**한다. fresh 샘플이 없는데 제외 카운트만
  올라가면 임계값이 잘못 잡힌 것이다
- 300초는 §8의 가중치와 같은 성격의 **필드 튜닝용 출발점**이지 스펙이 유도한 값이 아니다

Android도 Fused Location 도입 시(M0B-2) 동일 의미론을 구현한다.

## 6. Reliable Location
Initial default:
- horizontalAccuracy <= 35m
- freshness <= 20s during active session

Selection favors newer + accurate sample.
Threshold may differ per platform only through safe-clamped config.

**Selection, exactly (2026-09-27).** Both platforms run it only on fixes the open drive's §5
gate **accepted** — never on an outlier, and never on a fix that arrives with no drive
recording. A drive records while `DRIVING_CANDIDATE`/`DRIVING` and while `PARKING_TRANSITION`
decides (§3a), so a fix from a car standing in its bay may update the spot. Among admissible
fixes (≤ 35 m, ≤ 20 s old, not > 5 s in the future) a newer one wins unless the incumbent is
still fresh (≤ 20 s) **and** more accurate — then the incumbent stays. Android used to select
before the outlier check, in every state, and to take any newer fix. The location is not a
parity field, but a candidate's `reliable_location_captured` code (§8b) depends on it.

## 7. Driving Confirmation
Initial conceptual guard:
- recent vehicle evidence
AND
- duration >=120s OR distance >=800m
AND
- movement evidence consistent with travel

One event alone never confirms full driving session.

### `movement evidence consistent with travel` — speed가 아니다

**관측 사실 (2026-09-16/17, iPhone15,3, iOS 26).** 실기기 trace 9개를 회수했다.
bounded driving session이 수신한 fix는 **87개**이고, 그중 **speed를 가진 것은 0개**였다.
`liveUpdates(.automotiveNavigation)`는 정상 동작했고 fix는 계속 들어왔다.
같은 기간 체크포인트의 `travelDistanceEstimate`는 3178 m까지 쌓였다.

iOS 구현은 이 조항을 `speed >= threshold`로 읽었기 때문에 movement evidence가
**구조적으로 성립 불가능**했다: speed가 nil이면 카운터가 영원히 0이고
`minimumMovingSamples`를 넘을 수 없다. 거리는 이미 쌓여 있는데 판정에 쓰이지 않았다.

지하·터널·도심 협곡은 GPS 도플러 속도가 나오지 않는 환경이고, 그게 주차핀의 주 무대다
(지하주차장, 아파트 지하). §13의 underground parking 패턴 자체가 이 조건을 전제한다.

**따라서 movement evidence는 두 경로를 가진다.**

1. **speed 우선.** fix가 speed를 가지면 그것만으로 판정한다. 기존 의미론 그대로다.
2. **speed가 nil일 때만 거리 fallback.** anchor fix와 현재 fix의 변위/시간차로
   평균 속도를 유도한다. 세 관문을 모두 통과해야 인정한다.

| 관문 | 기준 | 근거 |
|---|---|---|
| 시간차 하한 | baseline >= 30s | `threshold * T >= 2·sqrt(2)·a`를 good bucket 상한 20m에 풀면 T >= 28.3s. 더 짧으면 정확도 관문이 느린 실주행을 무조건 거부한다 |
| 시간차 상한 | baseline <= 180s | 그 이상의 평균은 "주행–정차–주행"을 평탄화해 travel을 서술하지 못한다. vehicle evidence 만료 horizon과 같고, significant change 간격보다 훨씬 짧다 |
| 정확도 | 변위 >= 2·sqrt(a₁² + a₂²) | `horizontalAccuracy`는 1σ 반경이므로 두 fix **변위**의 1σ는 `sqrt(a₁²+a₂²)`다. 2σ는 "실제로 움직였다"의 약 95% 단측 진술 |
| 거리 | 변위 / baseline >= movingSpeedThreshold | speed 경로와 같은 2 m/s 임계값을 거리로 표현한 것 |

**2σ를 고른 이유는 실데이터다.** 같은 subway trace에 정확도 521 m와 47.9 m인 두 fix가
23초 만에 928 m 떨어져 기록된 구간이 있다. 액면가로는 145 km/h인데, 그 노선 최고속도는
80 km/h다 — 노이즈다. 2σ 관문은 `2·sqrt(521² + 47.9²) ≈ 1043 m`이므로 이를 **거부**한다.
1σ였다면 통과시켜 지하철에서 주행을 확정했을 것이다.

**anchor는 실패 시 유지하고 성공/상한 초과 시에만 교체한다.** 연속 두 fix만 보면 1 Hz에서
변위가 노이즈 바닥을 결코 넘지 못한다(10 m 정확도 fix 사이 50 km/h 주행은 13.9 m,
관문은 28 m). anchor를 붙들면 baseline이 길어져 판정이 가능해진다. 위 928 m 구간도
직전의 깨끗한 24.9 m fix를 anchor로 재면 58초에 928 m, 57 km/h — 그냥 열차다.

**재생 결과 (수정 전 → 후, movingSampleCount).**

| trace | 구간 | bounded fix | speed 있는 fix | 전 | 후 |
|---|---|---:|---:|---:|---:|
| `trace-1789544225798` | 지하철 퇴근 | 58 | 0 | **0** | **3** |
| `trace-1789537784682` | 사무실 도보 (음성 대조군) | 35 | 0 | **0** | **0** |

지하철 구간은 `minimumMovingSamples`(2)를 넘고, 같은 기기·같은 시간대의 도보는 넘지 않는다.

**계측.** `speedAvailableCount` / `speedMissingCount` / `derivedMovingSampleCount` /
`movementEvidenceRejectReason`(`accuracyTooCoarse` | `intervalTooLong` | `distanceTooShort`)를
diagnostics에 내보낸다. "확정이 안 됐다"와 "speed가 한 번도 안 왔다"는 밖에서 보면 같아
보이는데 실제로는 후자였고, 다음 데이터부터는 그 구분이 파일 한 줄로 끝나야 한다.

**이 절의 모든 임계값은 §8 가중치와 같은 성격의 필드 튜닝 출발점이다.** §18 참조 —
특히 **지상 자동차 주행 데이터가 아직 하나도 없다.**

### `distance >= 800m` 누적도 같은 노이즈 바닥을 쓴다

movement evidence를 통일하면서 같은 병이 바로 옆 조항에 남아 있는 것이 드러났다.
**두 구현 다 틀렸고, 방향이 반대였다.**

| | 누적 조건 | 지하에서 |
|---|---|---|
| iOS | valid fix + step 속도 <90 m/s | 정확도 1000m 지터가 그대로 누적 → **과대** |
| Android | 정확도 ≤35m fix만 | 거의 아무것도 안 쌓임 → **과소** |

iOS의 90 m/s 관문은 거친 fix에서 무력하다. 정확도 1000m인 두 fix가 60초 간격으로
900m 떨어지면 15 m/s라 통과하는데, 그건 이동이 아니라 노이즈다.
Android의 ≤35m는 §6의 기준을 또 잘못된 자리에 쓴 것이다 — §6은 *기억할 주차 지점*을
고르는 기준이지 *얼마나 이동했는지* 재는 기준이 아니다.

**통일 규칙: 누적 앵커를 들고, 변위가 쌍의 결합 오차를 넘을 때만 더한다.**

- 더하는 조건: `변위 >= 2·sqrt(a₁² + a₂²)` — movement evidence와 **같은 노이즈 바닥**
- 넘으면 더하고 앵커를 교체한다
- 못 넘으면 더하지 않고 **앵커를 유지한다.** 느린 이동도 앵커가 멀어지면 결국 넘는다
- baseline 상·하한과 속도 관문은 **여기에 적용하지 않는다.** 그 둘은 "travel다운가"를
  묻는 movement evidence의 관문이고, 거리 누적은 "얼마나 갔나"만 묻는다
- §5의 outlier 관문(속도 상한)은 그대로 유지한다. 노이즈 바닥과 다른 것을 막는다

노이즈 바닥 하나를 두 곳에서 같은 의미로 쓴다. 거친 fix를 버리지 않으면서 지터를
합산하지 않는 유일한 방법이고, 지하가 이 제품의 주 무대이므로 거친 fix를 버릴 수 없다.

#### A coarse anchor is replaced by a materially better fix (DECIDED 2026-09-27)

"못 넘으면 앵커를 유지한다"에는 구멍이 있었다. **앵커 자체가 거칠면** 그 정확도가 이후 모든
구간의 바닥을 정한다. field draft s02: 세션 첫 fix가 정확도 1000 m였고, 이후 8–9 m 정확도로
6–12 m/s 주행한 22–175 m 구간들이 전부 약 2 km 바닥에 막혀 **두 엔진 모두 거리가 끝까지 0**
이었다.

규칙: 구간이 바닥을 못 넘었을 때, 새 fix의 정확도가 앵커 정확도의
`distanceReanchorAccuracyRatio`(**0.5**, **unvalidated**)배 미만이면 **앵커를 새 fix로 교체한다.
그 구간은 더하지 않는다.** 더하지 않으므로 "지터를 합산하지 않는다"는 성질은 유지된다.

비용도 적는다. 거친 앵커와 새 fix 사이의 실제 이동은 버려진다 — 그 구간은 원래 수백 m
바닥 안이라 지터와 구분할 수 없는 구간이다. 기록된 지하철 trace의 누적 거리는 3966.49 m
(거부 50)에서 **3050.54 m(거부 48)**로 줄었다(`FieldTraceReplayTests`, Android 쌍둥이 테스트도
같은 값이어야 한다). 800 m 판정은 그대로다.

**23 % 손실을 받아들이는 이유 (round-2 review, 2026-09-27).** Android 측 반론은 "지하 실거리
916 m를 버린다"였다. 그 916 m는 정의상 **거친 앵커의 2σ 바닥 안**에서 잰 구간이다 — 규칙
없이도 그 구간은 단독으로는 더해지지 않았고, 나중에 더 긴 chord가 바닥을 넘을 때 *거친
앵커 기준으로* 한꺼번에 회수됐을 뿐이다. 그 회수분에는 앵커 자체의 수백 m 오차가 그대로
실린다. 규칙이 없으면 s02처럼 첫 fix 하나가 세션 전체의 거리를 0으로 만든다(두 엔진 모두
끝까지 0 m). 과소 계상은 §8의 +5(1600 m)와 `vehicle_distance_met`(800 m)만 늦출 뿐이고, 둘 다
duration 조항이 따로 채운다(§8b "duration ≥ 120 s **or** distance ≥ 800 m"). 지터를 거리로
세는 쪽의 비용 — 지하철·버스 음성 케이스의 거리 과대 — 이 더 크다. 따라서 **채택을 유지하고
양 플랫폼이 3050.54 m / 48을 pin한다.** 지상 주행 데이터로 ratio 0.5를 재검증하는 것은 §18
항목이다.

### 양 플랫폼 통일 (2026-09-18 결정)

두 구현이 같은 조항을 다르게 읽고 있었다. **아래가 단일 정의이고 양쪽이 이것을 구현한다.**

| 항목 | 통일값 | 통일 전 iOS | 통일 전 Android |
|---|---|---|---|
| speed 임계값 | **2.0 m/s** | 2.0 | 8.0 |
| 판정 단위 | **fix 쌍마다 판정, moving 샘플 수를 센다** | 쌍 단위 카운트 | 세션 누적 거리 |
| 노이즈 차단 | **변위 >= 2·sqrt(a₁²+a₂²)** | 동일 | 정확도 ≤35m fix만 누적 |
| 최소 moving 샘플 | **2** | 2 | 2 (reliable 샘플 기준) |
| vehicle evidence 유효기간 | **300s** | 180 | 300 |

**speed 2.0 m/s를 고른 이유.** 8 m/s(29 km/h)는 주차장에서 자리를 찾아 기어가는 차를
거부한다 — 그런데 그게 주차 직전의 바로 그 순간이고 이 앱이 잡아야 하는 장면이다.
정체 구간과 지하철 저속 구간도 같이 탈락한다. "차량인가"는 이 관문의 질문이 아니다.
그건 recent vehicle evidence가 이미 따로 요구한다. 여기서 묻는 것은 "정말 움직였는가"
뿐이므로 기준은 "걷기보다 빠름"이 맞다. 같은 기기·같은 시간대의 도보 대조군이
0 moving 샘플로 남은 것이 과검출이 아님을 보인다.

**누적 거리를 쓰지 않는 이유.** 합은 "150 m를 꾸준히 이동"과 "GPS 지터가 합쳐서 150 m"를
구분하지 못한다. 쌍 단위 판정은 각 구간이 자기 오차를 스스로 넘기를 요구하므로 지터가
누적되지 않는다.

**정확도 ≤35m 관문을 movement evidence에 쓰지 않는 이유 — 이것이 가장 중요하다.**
§6의 35 m는 *기억할 만한 주차 지점*을 고르는 기준이지 *움직였는지* 판단하는 기준이
아니다. 실측 지하 구간의 정확도는 100 m에서 2620 m였다. 그 관문을 movement evidence에
걸면 지하에서 사실상 아무 fix도 자격을 얻지 못하고, **주행 확정이 지하에서 구조적으로
불가능해진다** — iOS가 speed-only로 막혔던 것과 결론이 같고 경로만 다르다.
거친 fix라도 변위가 자기 오차를 명백히 넘으면 그것은 이동의 증거다. 2σ 관문이 그 판단을
한다.

**vehicle evidence 유효기간 300s.** 실측 지하철에서 Activity 전환 간격이 길었고
(`walking_enter` 수신 지연만 8.772초, Core Motion 엣지 간격은 분 단위), 180초는 빠듯하다.
더 관대한 쪽이 놓치는 비용(전체 여정 손실)이 잘못 여는 비용(한 번의 timeout 창)보다 크다.

**검증 상태.** 위 표의 모든 값은 필드 튜닝 출발점이다. 이 결정은 "어느 구현이 실측에서
이겼다"가 아니라 **"어느 쪽이 관측된 실패 모드를 설명하느냐"**에 근거한다.
두 구현 모두 **지상 자동차 주행 데이터로 검증된 적이 없다.** §18 참조.

## 8. Parking Evidence Weights — Starting Point
Positive:
| Evidence | Weight |
|---|---:|
| meaningful recent vehicle session | +25 |
| vehicle exit/end | +15 |
| walking shortly after vehicle | +30 |
| stationary after driving | +10 |
| location movement stopped | +10 |
| GPS quality degraded near end | +5 |
| trusted/projection disconnect | +20 |
| route duration/distance comfortably over minimum | +5 |

Negative:
| Evidence | Weight |
|---|---:|
| vehicle resumes quickly | -40 |
| movement continues | -30 |
| short stop pattern | -25 |
| trip below minimum | -15 |

These are defaults for field tuning, not guaranteed truth.

### 8a. Measured 2026-09-21: the underground parking that scored 55

The first real candidate this project produced scored **low** and therefore notified nobody
(§9). The arithmetic, from a 100-minute drive that ended underground:

```text
recent vehicle session       +25
vehicle exit                 +15
stationary after driving     +10    ← walking would have been +30
route comfortably over min    +5
                            ────
                              55    (medium starts at 60)
```

Two of §8's positives are structurally unavailable underground, and both of them are ones
above-ground parking collects for free:

- **`walking shortly after vehicle` (+30) is usually `stationary after driving` (+10).** You
  park, get out, and stand at the lift. The Activity Transition API reports STILL before it
  reports WALKING, and often instead of it.
- **`location movement stopped` (+10) cannot fire at all.** It needs a fix, and there are no
  fixes under a slab.

So the app's main setting carries a 30-point structural penalty against the weights.

**Raising `stationary after driving` is the obvious fix and it is the wrong one.** A long bus
ride that ends with the rider standing at a stop produces *exactly* the same evidence — the
engine cannot tell them apart from motion, which is what §12 already says. Every point added
there buys one parking notification and one bus notification.

The one signal that separates them is the car link, and it is already weighted +20. A
Bluetooth disconnect also adds `vehicle exit` through the same fold, so the same drive with a
paired car scores:

```text
recent vehicle session       +25
vehicle exit                 +15
car link disconnected        +20
                            ────
                              60    → medium → notifies
```

A bus has no car link and stays below the bar. That is the discrimination §8 was built to
make, and it had never once fired: `BLUETOOTH_CONNECT` was declared in the manifest and
requested by nothing until 2026-09-21, so the +20 was unreachable. No link event appears in
any trace recorded before that date, which is the same fact from the other side.

**No weight is being changed on the strength of one candidate.** The next drive with the
permission actually granted is the measurement that decides whether anything here needs
tuning, and the number to watch is how often a real parking still lands under 60 with a car
link present.

### 8b. Evidence definitions — one reading for both engines (DECIDED 2026-09-27)

§8 names the evidence; it never said when each item is true, and the two engines guessed
differently. The field-draft replay showed the cost: on s04 Android scored `high`/90 and iOS
`medium`/75, on s17 Android `medium`/70 (notifies) and iOS `low`/55 (silent) — same fixture,
both "passing", because no fixture pinned the bucket. These are the definitions. Every one is
judged **when the candidate is created**, from timestamped evidence, against the drive's
**end** — the moment `PARKING_TRANSITION` was entered (for a car-link disconnect in
`DRIVING`, the disconnect). The transition that produced the candidate is the scope: if an
earlier transition resumed `DRIVING`, its flags went with it.

| code / §8 item | weight | true when |
|---|---:|---|
| `recent_vehicle_activity` — meaningful recent vehicle session | +25 | the drive reached `DRIVING`. Every transition that can create a candidate satisfies it; it is §6's first clause |
| `vehicle_exit_detected` — vehicle exit/end | +15 | an **explicit** exit ended this drive: the transition was entered by `vehicle_exit` (on iOS also the exits its adapter derives — from a walk, or from Core Motion going silent), or by a car-link disconnect; **or** such an exit / disconnect arrived while the transition was open. A `movementIdle` entry alone does **not** earn it — it is an inference from absence, and that inference is what `location_stopped` weighs; crediting both would count one inference twice |
| `walking_after_vehicle` | +30 | `walking_enter` at or after the transition's entry, within `transitionWindow` — whatever the entry was (Android used to require an explicit exit first, so a walk that confirmed a `movementIdle` transition earned nothing) |
| `stationary_after_vehicle` | +10 | the same, for `stationary_enter` |
| `location_stopped` — location movement stopped | +10 | the transition was entered by `movementIdle`, **or** the newest fix that *reported* a speed < 2.0 m/s is later than the drive's last moving sample and no earlier than `nearEndHorizon` (300 s) before the end. A speedless fix never counts (§7), and neither does a rejected one (§5). "The last moving sample" is the **re-anchored** value: a resume on `vehicle_enter` or a link reconnect sets it to `max(lastMovingSample, resume)` (§3a "Resuming keeps the drive"), so a stop at the light the drive then resumed from is never this drive's `location_stopped` — the resume is the drive moving on. Both engines compare against that one value. Android used to add it for any stopped fix anywhere in the session — the first red light of the trip |
| `location_quality_degraded` — GPS quality degraded near end | +5 | the fix quality fell **into `poor`** no earlier than `nearEndHorizon` before the end (or during the transition): a `location_quality_degraded` event whose `toBucket` is `poor` or absent, or an accepted fix in `poor` whose predecessor was `good`/`fair`. good→fair is not a degradation. iOS used to drop the event while `DRIVING`; Android counted any drop anywhere, good→fair included |
| `car_projection_disconnected` | +20 | a car-link disconnect ended the drive or arrived in the transition |
| `vehicle_duration_met` / `vehicle_distance_met` | — | duration = end − session start ≥ 120 s; distance = §7's accumulated distance at the end ≥ 800 m. Both frozen at the end: a walk to the lift adds neither, and a code is never earned by the clock running on after the drive (Android used to evaluate the duration code at every fold, so a 100 s drive "met" 120 s thirty seconds after it ended). "Session start" is the first vehicle evidence of the travel session and is **not** reset by a red-light resume |
| comfortably over minimum | +5 | duration ≥ 240 s or distance ≥ 1600 m, same basis |
| trip below minimum | −15 | neither `vehicle_duration_met` nor `vehicle_distance_met`, when the duration is known (a transition migrated from a pre-schema-3 checkpoint has none — unknown is not short). iOS used to omit it |
| `reliable_location_captured` | — | the candidate **carries** a location, i.e. §5's inheritance rule found a fix from this drive. iOS used to emit it whenever any reliable fix existed on the device, which after the first drive is always |

§6's third clause — "at least one confirmation signal" — counts **observed** signals only:
`walking_enter`, `stationary_enter`, a location stop observed in the transition, a car-link
disconnect. The `location_stopped` *weight* is not one, or a `movementIdle` entry would
satisfy the rule the instant it happened. Both engines evaluate §6 before creating a candidate.

**Measured on the field drafts (iOS, 2026-09-27).** s04 `high`/90 (the stop at t=4849 and the
good→poor event at 4884 are both inside 300 s of the exit at 4907); s17 `low`/55 (its stop and
its degradations are 360–460 s before the exit — Android's 70 came from those); s16 and s33
`low`/45 (movementIdle + a location stop, no exit, no walk). s16/s33/s17 are confirmed real
parkings that now create a candidate silently. That is the honest reading of their evidence
under §8's weights, and it is recorded here as the next thing §18 has to measure — not tuned
away on three traces.

## 9. Confidence Buckets
- high: >=80
- medium: 60...79
- low: <60

MVP:
- high/medium -> candidate
- low -> no notification

Do not auto-confirm from high score until field precision meets acceptance threshold.

## 10. Candidate Lifetime
Default expiry: 45 minutes — **whatever the state** (2026-09-27). A candidate left behind by
`CANDIDATE_PENDING → DRIVING_CANDIDATE` (§3a keeps it answerable) used to live for ever if
the new drive produced no candidate of its own, because only `CANDIDATE_PENDING` looked at
the clock. Both engines now withdraw it at its `expiresAt` from any state, without moving the
state.
After expiry:
- do not silently create parking
- clear/supersede on new trip according to product flow

## 10a. Candidate Notification and Confirmation (v1 contract)

Sections 9, 10 and 12 fix when a candidate exists and how long it lives. These fix what
the user sees, because that is the part two platforms would otherwise each invent.

### Identity and deduplication
A candidate carries a `candidateId`. The notification is posted with that id as its own
identifier, so re-posting the same candidate **replaces** the notification rather than
stacking a second one. Section 12 already allows one candidate per travel session; this
is what makes that visible — a session can never show two notifications.

If a new travel session produces a candidate while an older one is still pending, the
older candidate expires immediately and its notification is withdrawn. A stale prompt
about a previous trip is worse than no prompt.

### Posting
Posted on entry to `CANDIDATE_PENDING`, never earlier: `PARKING_TRANSITION` is the state
that is still deciding, and a notification there would fire on every red light.

`low` confidence posts nothing (§9). The candidate is still recorded so the app can show
it when opened, and so the trace keeps the evidence.

Notification permission is not required for correctness. Denied, the candidate is saved
and surfaces in the app on next launch; nothing is lost and nothing is retried.

### What the notification says
Copy is fixed in `docs/02_PRODUCT_SCOPE_AND_FLOWS.md` §5 and must not be reworded:

```text
주차한 것 같아요
마지막으로 확인된 위치와 시간을 저장해뒀어요.
```

It never states a floor, an address or a coordinate — the engine does not know the floor,
and §9 of docs/09 keeps location out of notifications.

### What a tap does
Opens the confirmation screen for that `candidateId`. If the candidate has since expired
or been handled, the screen opens on the record it became, or on home when there is
nothing left to show. A tap never silently creates parking (§10).

### Confirmation screen
Shape and copy are fixed in `docs/10_DESIGN_UX_SPEC.md` §7a.

Confirming writes a parking record with `source = detected`, the candidate's
`lastReliableLocation`, and the chosen floor. Rejecting discards the candidate and is
recorded as evidence for tuning — it is the strongest signal the detector has.

### Expiry
At 45 minutes the candidate expires, its notification is withdrawn, and no record is
created. A user who opens an expired notification lands on home; the app does not
apologise for it in a dialog.

### History

Resolving a candidate — confirmed, rejected or expired — appends it to a local history of
the last 30, which is what the bell opens (docs/10 §7b). The live candidate slot still
holds at most one; history is a separate append-only list, because the two answer
different questions and giving the slot a second job is how it would end up holding two
live candidates by accident.

An entry keeps the raised-at time, the outcome, and for a confirmed one the record id.
Not the location: §10a keeps coordinates out of this surface and history is the same
surface a day later.

### Analytics
`parking_candidate_created`, `parking_candidate_confirmed`, `parking_candidate_rejected`
(docs/17 §2), each carrying `confidenceBucket` and the §4 reason codes and nothing else.
Rejection is the event that pays for the whole feature, so it is never dropped.

## 11. Departure
While PARKED:
- new sustained vehicle evidence
- movement/distance threshold

Initial:
- vehicle >=90s
- movement >=500m

**The lapse boundary (2026-09-27).** `DEPARTURE_CANDIDATE → PARKED` fires strictly **after**
`lastVehicleEvidence + drivingCandidateWindow`; §7's guard counts evidence exactly
`vehicleEvidenceMaxAge` old as recent. At the shared instant the departure can still confirm
— Android's settle used to lapse first, iOS checked confirmation first. §7's guard also
refuses vehicle evidence stamped after `now` on both platforms. On Android, a `vehicle_exit`
while `PARKED` drops the departure's session, as iOS always did, so §11's 500 m and §7's
duration are measured per get-in, not accumulated across them.

**Departure rows are edges (DECIDED 2026-09-27).** `PARKED → DEPARTURE_CANDIDATE` (§11's two
bars, or §11b's link) and `DEPARTURE_CANDIDATE → DRIVING` (§7's guard) are asked at an event's
**edge**, once, against the state that event found — never in "judge the windows" (§3a "When a
timeout fires"). The event that opens a departure therefore never also confirms it: §7's guard
is first asked by the *next* event — a fix, a motion edge or a `timer_tick`, which both
platforms send once a minute while the capture runs. The lapse is the one departure row a
window drives; it is judged before the edge, strictly past its deadline, as above. A
`vehicle_exit` in `DEPARTURE_CANDIDATE` asks the guard first: met, the departure confirms (and
the exit is then read in `DRIVING`, below); not met, the machine returns to `PARKED`.
`proposeParkingEnd` carries the `DEPARTURE_CANDIDATE` entry time (§11a) in every case.

Why this and not the reverse: Android always worked this way. iOS asked both rows from its
window cascade, so an event that cleared §11's bars with §7's guard already met went
`PARKED → DEPARTURE_CANDIDATE → DRIVING` in one pass, and the `DEPARTURE_CANDIDATE` row and the
`proposeParkingEnd` landed on a different event on each platform. Making Android confirm in the
same pass would add a confirming row to its settle — a second place §7's guard is asked —
while moving iOS's two rows to the edge is a local change. The cost is a confirmation at most
one event later; the end time is unaffected. Pinned by `platform-tests/manual_save_then_departure.json`
(its golden trace: `DEPARTURE_CANDIDATE` at event 3, `DRIVING` with `proposeParkingEnd` at
event 4) and by the engine twins "A departure is confirmed on a later event than the one that
opened it" (iOS `DepartureTests`) / `a departure is confirmed on a later event than the one
that opened it` (Android `ParkingDetectionEngineTest`).

**An event that confirms a departure is also read in `DRIVING` (DECIDED 2026-09-28).** After
`DEPARTURE_CANDIDATE → DRIVING`, the same event is handed to `DRIVING`'s own rows — the chaining
`DRIVING_CANDIDATE` already does for the exit that promotes it. Only two events have such a
row (§3a): a `vehicle_exit` ends the drive (`PARKING_TRANSITION`, `vehicle_exit_detected`), and
a link disconnect opens the candidate outright (§3a's link table). Every other confirming event
— a fix, a tick, `walking_enter`, `stationary_enter`, a connect — confirms and nothing else.
`proposeParkingEnd` is emitted first and still carries the `DEPARTURE_CANDIDATE` entry.

Why: the short hop into an underground garage. The fixes stop on the ramp, §7's guard comes
true by elapsed time alone, and the `vehicle_exit` is the first event to ask it. Swallowed, the
exit confirmed the departure and left the machine in `DRIVING`; the walk that followed has no
`DRIVING` row, and `movementIdleWindow` later opened a transition stamped after that walk, which
lapsed to `IDLE`. The old parking was ended and the new one lost. Declaring the swallow
intended was the alternative, and it buys nothing: the exit is unambiguous evidence the drive it
just confirmed is over. iOS had this outcome before the rows became edges (its window cascade
confirmed ahead of the exit's row), so this is also the restoration of that behaviour.

Pinned by `platform-tests/manual_save_then_short_departure.json` (golden: `DEPARTURE_CANDIDATE`
at event 3, `PARKING_TRANSITION` with `proposeParkingEnd` at event 4, a `medium` candidate at the
walk — 25 + 15 exit + 30 walk = 70, no stop fix, and 140 s / 600 m is not "comfortably over"
§7) and by the engine twins "A vehicle_exit that confirms a departure also ends the drive",
"A car link disconnect that confirms a departure opens the candidate outright" and "A vehicle_exit
that does not meet the guard still returns to PARKED" (iOS `DepartureTests`) / the same names
in Android's `ParkingDetectionEngineTest`, and "A walk that confirms a departure only confirms it"
(iOS `DepartureTests`) / `a walk that confirms a departure only confirms it` (Android).

**An adapter-decided end never leaves the parking behind (DECIDED 2026-09-28).** iOS has no
`vehicle_exit` edge on a device: `BackgroundCoordinator` derives the exit from a walk
(`endDrivingSession(.walkingDetected)`), bounds Core Motion silence the same way
(`.vehicleEvidenceExpired`), and ends the session on a lost authorization or capture, or the
opt-out. A derived exit or the silence bound in `PARKED` or `DEPARTURE_CANDIDATE` is read as the
`vehicle_exit` it stands for, never as a drive ending in `IDLE`; a lost capture is not an exit
at all (the next paragraph):

- `PARKED` with a get-in open: the get-in and its capture are dropped, and the state stays
  `PARKED` — Android's `fromParked` `VehicleExit` row.
- `DEPARTURE_CANDIDATE`: §7's guard met at the end → the departure is confirmed
  (`proposeParkingEnd` at the `DEPARTURE_CANDIDATE` entry) and the same end then ends that drive
  as it would any `DRIVING` session (→ `PARKING_TRANSITION`). Guard unmet → `PARKED`, ending
  nothing — Android's `fromDepartureCandidate` `VehicleExit` rows.
- The opt-out (`smartDetectionDisabled`, `fieldTestStopped`) decides nothing: `PARKED`, ending
  nothing, even when the guard is met. Turning detection off must not close a parking.

Why: these ends fell through to the drive path, where an unconfirmed departure session goes to
`IDLE`. The parking's §11 watch was lost with no record ended, and the derived exit of
`manual_save_then_short_departure.json` — `PARKING_TRANSITION` and a `medium` candidate on
Android — produced nothing on an iPhone. Fixtures carry an explicit `vehicle_exit`, so they
could not see it. Android's exit is a real `VehicleExit`, so these rows need no Android change.
Pinned by iOS `DepartureTests` "A derived exit after getting back in keeps the parking and drops
the get-in", "A derived exit that confirms a departure also ends the drive" (Android twin `a
vehicle_exit that confirms a departure also ends the drive`), "A derived exit that does not meet
the guard returns to PARKED" (`.walkingDetected`, `.vehicleEvidenceExpired`; twin `a
vehicle_exit that does not meet the guard still returns to PARKED`), "The opt-out inside a
departure returns to PARKED and ends nothing" (§3a "Turning Smart Detection off") and "A short departure restored before
its derived exit still becomes the next parking" (twin `a short departure restored before its
exit still becomes the next parking`).

**A lost capture decides nothing (DECIDED 2026-09-28).** A lost authorization or a failed
capture (`.authorizationLost`, `.captureFailed`) in `PARKED` with a get-in open, in
`DEPARTURE_CANDIDATE`, **and in `DRIVING_CANDIDATE` and `DRIVING`** (extended 2026-09-28), only
stops the capture: iOS emits `stopLocationCapture` (and the
checkpoint write that records the loss) and keeps the state, the session and the
vehicle-activity level. The next walk, derived exit, edge or tick decides — §7's guard, §11's
bars, the §11 lapse — exactly as it would have with the capture running and no fix arriving.
This is the smallest rule both platforms can share: on Android a lost capture never reaches the
engine (a revoked permission takes the Fused Location request and moves nothing), and iOS
already reads it this way in `PARKING_TRANSITION` (`endInsideTransition`: the capture stops,
motion stays free to confirm). The earlier iOS reading — a lost capture as a `vehicle_exit` —
decided the departure on the spot and diverged whenever §7's duration clause came true after the
loss while the vehicle evidence was still ≤ 300 s old: `shortDepartureBeforeTheExit` with the
capture lost at +710 s went to `PARKED` on iOS, while Android's exit at +740 s met the guard by
elapsed time, ended the parking at +700 s and raised the `medium` candidate.
The same held for a drive: iOS used to end `DRIVING_CANDIDATE`/`DRIVING` in `IDLE` on a lost
capture (no transition, no candidate), while on Android the drive stayed and the next
`vehicle_exit` and walk raised the candidate — one real sequence, two final states. A drive that
loses its capture now keeps its state, session and vehicle level on both platforms: a
`DRIVING_CANDIDATE` still promotes on §3a's 90 s bar or lapses on its window, and a `DRIVING`
still ends on its exit, walk, `movementIdle` or the adapter's silence bound.

**A lost capture stays lost for its session (DECIDED 2026-09-28, N1).** Once a session —
a get-in, a departure, a drive, and the `PARKING_TRANSITION` that drive ends in, including a
drive that transition resumes — has no capture running, **nothing opens one for it again**
until it ends, on either platform, whatever comes back in between: the permission granted
again, a `vehicle_enter`, a `vehicle_exit`, a car-link connect, a process death. Only a session
the engine opens afterwards starts with its own capture: a `vehicle_enter` in `IDLE`, in
`PARKED` with no get-in open, or in `CANDIDATE_PENDING` with no stop-only window (§3a "Leaving a
pending candidate behind"), and the fuel-stop reconnect in `CANDIDATE_PENDING` — on Android
exactly the events that take the engine's want (`modeWantedBy`) from null to non-null. The one
exception is §19's system reset on Android: a reboot or package replace dropped a capture the
session still had, it did not lose one.

What each platform does for it. iOS never reopens a capture on an authorization change — its
capture starts only on the engine's `startBoundedLocationCapture` — and records the loss on
the session (`isDrivingCaptureLost`); a transition built from that session has
`isCapturing: false`, a transition that loses its own capture records it the same way, both are
persisted with the rest of the engine state (§14), and a restore of either reopens none. A drive resumed from a
transition with no capture keeps the loss (`resumeDrivingFromTransition` no longer emits
`startBoundedLocationCapture`). Android's follow never reopens a capture the engine already
wanted (§19), and its **motion policy must not either**: an `ENTERED_VEHICLE` or
`EXITED_VEHICLE` (or `STARTED_WALKING`) while no capture is registered and the engine's want
*before* the event was already non-null opens nothing — the same test the follow applies
(`wantedBefore != null && current == IDLE && !droppedBySystem` → stay `IDLE`). Before this,
`LocationCaptureModePolicy.modeFor` opened `DRIVING_CANDIDATE` on the entry and
`PARKING_TRANSITION` on the exit over a capture a revoked permission had stopped, so a regrant
mid-drive gave Android kerb fixes — a moving one resumed `DRIVING`, the red light, no
candidate — while iOS got none and confirmed the candidate; and iOS in turn rebuilt a restored
transition with `isCapturing: true` and reopened its capture, and reopened one on a resume, so
a later stop-only candidate got a resume window Android never opened. The alternative — both
platforms reopening at the same edges once the permission is back — was rejected: iOS would
have to feed authorization into the engine and define "the same edges" for every row, and a
capture regained mid-session re-earns only part of the evidence the anchors needed, so the
outcome would still depend on when the grant landed. Keeping the loss is one bit both engines
already have.

So a departure confirmed after the loss drives on with no
capture, the transition that drive ends in has none (`isCapturing: false`), and a stop-only
candidate there opens **no** resume window (§3a "The window lives exactly as long as its
capture"): a `vehicle_enter` inside what would have been the window is "Leaving a pending
candidate behind" — `DRIVING_CANDIDATE`, candidate kept — on both platforms. iOS used to build
that transition with `isCapturing: true`, so the same `vehicle_enter` resumed `DRIVING` and
withdrew the candidate on iOS only. A relaunch keeps the loss: a restored get-in, departure
or drive that had lost its capture reopens none, and is decided as before by the next event:
both platforms reload the whole engine state, drive and loss included (§14), and neither
reopens a capture the engine already wanted (§19). iOS used to reopen the capture here, which
gave the next stop-only candidate a resume window and let a `vehicle_enter` inside it withdraw
the parking that Android kept. A session
opened afterwards (a new `vehicle_enter` in `IDLE` or `PARKED`, a fuel-stop reconnect) starts
with its own capture.

Pinned by twins with one name and one event sequence — `shortDepartureBeforeTheExit` (hand
save, `vehicle_enter` +600 s, fixes +610 s / 0 m and +700 s / 600 m), capture lost at +710 s —
iOS `DepartureTests` (each for `.authorizationLost` and `.captureFailed`) / Android
`ParkingDetectionRuntimeTest` (the loss being a revoked permission, no engine event):
"Capture lost at +710 then exit at +740 still proposes the end at +700 and raises the medium
candidate"; "Capture lost at +710 with no further edge lapses to PARKED stamped +900" (next
event a tick at +901 s); "A departure that lost its capture opens no resume window after it
confirms" (tick +740 s → `DRIVING`, parking ended at +700 s; `stationary_enter` +890 s → stop-only
candidate after `movementIdle` at +880 s; `vehicle_enter` +950 s → `DRIVING_CANDIDATE`, candidate
kept); "A departure that lost its capture reopens none after a process death" and "A get-in that
lost its capture reopens none after a process death"; "A departure that lost its capture reopens
none after it confirms and the process dies" (loss +710 s, tick +740 s → `DRIVING`, process death,
`stationary_enter` +890 s → stop-only candidate, `vehicle_enter` +950 s → `DRIVING_CANDIDATE`,
candidate kept, nothing withdrawn). For N1, iOS `ParkingTransitionEvidenceTests` / Android
`ParkingDetectionRuntimeTest` through the real `TransitionEventIngestor` (the loss a revoked
permission, the regrant `foregroundGranted = true`): "A drive whose permission returns before
the exit decides as it would without a capture" (`vehicle_enter` 0 s, tick +150 s → `DRIVING`,
loss +200 s, regrant +300 s, `vehicle_exit` +600 s opens no capture, `walking_enter` +630 s →
one candidate, `CANDIDATE_PENDING`); "A transition that lost its capture reopens none after a
process death" (`DRIVING` at +90 s, moving fix +100 s, loss +120 s, tick +280 s →
`PARKING_TRANSITION` by `movementIdle`, death, regrant, restore +285 s opens no capture,
`stationary_enter` +300 s → candidate, `vehicle_enter` +330 s → `DRIVING_CANDIDATE`, candidate
kept); "A transition that lost its capture resumes its drive without one" (loss +120 s in the
drive, or +285 s inside the transition; `vehicle_enter` +300 s → `DRIVING` with no capture
opened, and a relaunch reopens none). For a plain drive, iOS `ParkingTransitionEvidenceTests` /
Android `ParkingDetectionRuntimeTest`: "A drive that loses its capture still parks on the next
exit" (`vehicle_enter` 0 s, loss at +30 s in `DRIVING_CANDIDATE` or +120 s in `DRIVING`, tick
+150 s, `vehicle_exit` +600 s, `walking_enter` +630 s → one candidate, `CANDIDATE_PENDING`) and "A
drive that lost its capture reopens none after a process death" (`DRIVING` at +90 s, moving fix
+100 s, loss +120 s, death, `stationary_enter` +290 s → candidate after `movementIdle` at +280 s).
iOS alone: "A capture lost after getting back in keeps the get-in" (Android's get-in is untouched
by construction).

If uncertain -> suggestion, not destructive silent end.

### 11a. What a confirmed departure actually does

**DECIDED BY THE USER 2026-09-29: a confirmed departure asks; it does not end the parking.**
Until then `DEPARTURE_CANDIDATE → DRIVING` emitted `EndActiveParking` and the adapter closed the
record on its own. A departure is still an inference (§2's fundamental limit: a ride in
someone else's car, a valet, a car moved by a friend all clear §11's bars), and closing the
record the user relies on to find the car is the one mistake this product cannot undo for
them. So the engine now proposes, and the user decides.

**The engine: `ProposeParkingEnd(departedAt)`.** Where it emitted `EndActiveParking`, it emits
`ProposeParkingEnd` — same transition, same position in the effect list, same `departedAt`
stamp — and still moves to `DRIVING`, so the drive away can end in the next parking and that
parking is detected as before. Every rule elsewhere in §11 that says "`endActiveParking` is
emitted first / carries the `DEPARTURE_CANDIDATE` entry" now reads `proposeParkingEnd`.

**`departedAt` is when the car pulled away, not when the engine was sure.** The transition is
guarded by §7's guard in full, which is minutes of driving after the fact, so the effect carries
`DEPARTURE_CANDIDATE`'s own entry time — the moment §11's two bars (or §11b's link) were first
cleared. Stamping "now" would record the parking as ending somewhere down the road.

**The record stays active.** Nothing is closed until the user answers. The proposal is the
adapter's state, not the engine's: it is persisted beside the checkpoint (it survives process
death), there is **at most one**, and a later departure from the same parking replaces it.

**What the user sees, identically on both platforms:**
- A notification, posted only when there is an active record to ask about. Title
  **`출발한 것 같아요`**; body **`<place> 주차를 종료할까요?`**, where `<place>` follows the rule
  below (no place at all → `주차를 종료할까요?`). Actions **`주차 종료`** and **`아직 주차 중`**. The copy never
  states the departure as fact. It uses the platform's existing detection-notification
  conventions (iOS: its own `UNNotificationCategory` beside the candidate's, one request
  identifier so a new proposal replaces the old; Android: the candidate channel, one
  notification id). Notification permission is not required for correctness: denied, the
  in-app prompt below is the only surface and nothing is lost.
- In the app, the home active-parking card carries a compact prompt row with the same text and
  the same two actions for as long as the proposal is pending — including a proposal written
  while the app is already on screen: the card updates when the proposal is written, not at the
  next activation (iOS: the coordinator's store posts `parkingEndProposalDidChange` and
  `ParkingModel` re-reads; Android: the home screen observes the DataStore flow). **`주차 종료` is the screen's one
  primary action** while it shows (docs/10's one-primary-CTA rule): it ends the record at
  `departedAt`, which is the right answer, where the screen's ordinary 주차 종료 would end it now.
  `아직 주차 중` is secondary.

**`<place>`, character for character the same on both platforms (review 2026-09-29).** It is
the home hero's place text after the floor:
- floor first (its display text, e.g. `B3`), then the zone/spot text, joined by ` · `;
- zone/spot text: both → `zone · spot`; zone alone → `zone`; spot alone → the spot with `번`
  appended, **unless it already ends in `번`** (the user copying a wall writes `01번` as often
  as `01`); neither → nothing;
- a missing part is omitted with its separator. Zone and spot are stored trimmed, and blank
  text is stored as absent, so a blank field never produces an empty part.

| floor | zone | spot | body |
|---|---|---|---|
| B3 | A구역 | 142 | `B3 · A구역 · 142 주차를 종료할까요?` |
| B3 | A구역 | — | `B3 · A구역 주차를 종료할까요?` |
| B3 | — | 142 | `B3 · 142번 주차를 종료할까요?` |
| — | — | 142 | `142번 주차를 종료할까요?` |
| B3 | — | 01번 | `B3 · 01번 주차를 종료할까요?` |
| — | A구역 | 142 | `A구역 · 142 주차를 종료할까요?` |
| B3 | — | — | `B3 주차를 종료할까요?` |
| — | — | — | `주차를 종료할까요?` |

Pinned by the same table on both platforms: iOS `ParkingEndProposalCopyParityTests`, Android's
proposal copy test.

**The three outcomes:**
- **`주차 종료`** → the record is ended at `departedAt` (never now, never before its start),
  `parking_auto_end` (docs/17) is reported, the notification is withdrawn, the proposal cleared.
- **`아직 주차 중`** → the record is kept, the proposal cleared, the notification withdrawn, and the
  engine is told with the contract event **`user_kept_parking`** (§3a `*any* → PARKED`, the same
  row as `user_saved` — §11c — but with no new record): whatever it was inferring about the
  drive is dropped silently, a pending candidate is withdrawn, and the next `vehicle_enter`
  opens a departure's evidence from scratch.
- **Ignored** → the record stays active and the proposal stays pending. The engine is already
  in `DRIVING`, so the next parking is detected as usual. If the next parking is **saved by
  hand, or confirmed from a candidate, while a proposal is pending**, the previous record is
  ended at the pending `departedAt` — not at the save time and not at the candidate's
  `detectedAt` — and the proposal is withdrawn. **Only once the new record is actually
  written**: the end of the old record and the insert of the new one are one store write, and
  a save that writes nothing — a failed write, or a candidate that expired or was superseded
  while the form was open — ends nothing and leaves the proposal pending (review 2026-09-29).
  If the user ends the parking by hand (the
  ordinary 주차 종료, from home or from the detail screen), the proposal is withdrawn and the
  record ends when they said. **Deleting the active record** withdraws the proposal and its
  notification at once, on the same write — the user is in the app, and the shade must not go
  on asking about a record that no longer exists.

**A candidate confirmed while a parking is open and nothing is pending** — for example right
after `아직 주차 중`, when the drive that followed ends in a new candidate the user confirms —
closes the open record at **`max(startedAt, candidate.detectedAt)`** and inserts the new one, in
one store write (DECIDED 2026-09-29; the user just said they parked somewhere else, and one
parking can be active — FR-004). A write that fails ends nothing. Not an acceptance of any
proposal: `parking_auto_end` is not reported. A **hand** save with a parking open and nothing
pending is still refused (FR-004); only a pending proposal lets a hand save end the old record.
Held on both platforms by "With nothing pending, a confirmed candidate ends the open record at
detectedAt in one write" and its clamp twin (iOS `ParkingEndProposalModelTests`).

**A proposal always names the record it is about.** The record id is not optional. A proposal
about a record that is no longer the active one (it was ended or deleted meanwhile) is stale: it
is dropped and its notification withdrawn the next time anything looks. A stored proposal with
no record id — a legacy or damaged file — is stale by definition and dropped, never applied to
whichever record happens to be active.

`parking_auto_end` is therefore reported when the user accepts the proposal, and only when a
record was actually closed by that acceptance.

**Manual parking keeps working with every permission denied**, as everywhere: the proposal
needs no permission, the save flow needs none, and a denied notification only removes one
surface.

Fixed by the goldens of `platform-tests/manual_save_then_departure.json` and
`manual_save_then_short_departure.json` (`proposeParkingEnd` where they had `endActiveParking`),
by `departure_proposal_kept.json` (`user_kept_parking` after a proposal returns the engine to
`PARKED`) and `departure_proposal_then_next_saved.json` (a hand save in the next car park while
the proposal is pending), and by per-platform model tests for the three outcomes.

#### History: 2026-09-21 to 2026-09-28, the silent end

Android's engine had `PARKED → DEPARTURE_CANDIDATE → DRIVING` before it had any effect, so the
record stayed open for ever. On 2026-09-21 both platforms made the transition emit
`EndActiveParking` and close the record through the manual button's use case. "If uncertain →
suggestion" was honoured only by the state below it: reaching `DEPARTURE_CANDIDATE` and never
confirming ended nothing. Field use showed the confirmed case is not certain either, and the
user asked to be asked (2026-09-29, above).

**iOS landed the same day (2026-09-21)** and the two platforms now agree. iOS's shape
differs only where the engines differ: the session is opened by `vehicle_enter` while
`PARKED`. The bars and the guard are asked at the event's edge on
both platforms (§11 "Departure rows are edges"; iOS asked them in `tickOnce` until 2026-09-27).

One thing the iOS build had to fix on the way, and it is worth knowing about:
`DrivingEvidence.isConfirmed` is a **latch**, set by `promoteToDriving` on §3a's 90-second
bar. It is not §7's guard, and departure needs §7's guard. Reading the latch meant a real
departure never confirmed — caught by the test written to prove it did.
`meetsDrivingConfirmation(now:)` now evaluates §7 directly, and is what the departure row
asks. Android never had this hazard because its `DrivingConfirmationGuard.evaluate` was
already a function of the evidence rather than a flag on it.

**The latch had a second hazard, found on 2026-09-26.** `DEPARTURE_CANDIDATE → DRIVING` never
set it, so when that drive ended `endDrivingSession` read it as unconfirmed and went straight
to `IDLE`: the parking at the end of a drive that began at a parking was never detected.
Field data, iPhone: a parking auto-ended at 20:35, the car parked again at 21:01, no
candidate. The departure now marks the drive confirmed and reports `drivingConfirmed`. It has
just met §7's guard in full, which is the stronger bar. Held on both platforms by
`The drive a departure opened can end in the next parking`. Android passed it unchanged, for
the reason above.

### 11b. The car link opens a departure (2026-09-21)

**`PARKED` + `car_link_connected` → `DEPARTURE_CANDIDATE`.** §3a already calls the link the
strongest signal this product can get, and the departure side was ignoring it: reconnecting
to the car's Bluetooth or to Android Auto / CarPlay did nothing at all while parked, so a
drive away still had to be re-derived from 90 s of motion and 500 m of GPS — the two bars
§11 was built on before there was a link to ask.

It is the mirror of the row §3a already has on the other side: a disconnect while `DRIVING`
skips `PARKING_TRANSITION` and opens the candidate outright, because the phone leaving the
car is the parking. A connect while `PARKED` is the same statement in reverse.

**It opens the candidate; it does not end the parking.** The distinction is the whole of
§11a: `DEPARTURE_CANDIDATE` shows nothing and ends nothing, and only `DrivingConfirmationGuard`
— §7's guard in full — proposes the end (§11a). Ending outright on the connect would delete the
one thing the app is for whenever someone sits in a parked car with the radio on, which is
exactly the false positive §3a's gating table already had to be narrowed for.

**The end time is still right.** The proposed end is `DEPARTURE_CANDIDATE`'s entry time
(§11a), which is now the moment the phone reconnected to the car — a better answer than the
old one, which was whenever 90 s of vehicle motion and 500 m happened to be reached.

**Sitting in the car and not driving costs nothing.** Vehicle evidence goes stale
`recentVehicleWindow` after the connect, the lapse row returns the machine to `PARKED`, and
the record was never touched.

**The link edge is vehicle evidence for the departure's lapse, on both platforms (DECIDED
2026-09-28).** In `PARKED` and `DEPARTURE_CANDIDATE`, a connect **and** a disconnect set the
departure's last vehicle evidence to the edge's time (never earlier than it was), so §11's
lapse is measured from the latest link edge — including when an earlier `vehicle_enter` had
already opened the departure's session. A disconnect also ends the vehicle activity §11's 90 s
bar measures, as a `vehicle_exit` does. Android's fold always did both (`openOrExtendSession`,
`endVehicleActivity`); iOS reused the session's older `vehicle_enter` time, so a connect more
than `recentVehicleWindow` after the get-in opened the departure and lapsed it on the same
event, and a connect inside `DEPARTURE_CANDIDATE` did not postpone the lapse.

No fixture covers this: §3a forbids a fixture that depends on a link event being present, so
it is held by per-platform engine tests on both sides — "A link connect after an earlier
vehicle_enter holds the departure from the connect" and "A link connect inside
DEPARTURE_CANDIDATE postpones the lapse" (iOS `DepartureTests`, Android
`ParkingDetectionEngineTest`, same names), and "A link disconnect inside DEPARTURE_CANDIDATE
postpones the lapse" (same name on both).

### 11c. A parking the user saved arms the departure too (2026-09-24)

**`user_saved` moves every state to `PARKED`.** §11 watches for a departure only from
`PARKED`, and the one road into `PARKED` was answering a candidate. A parking saved from the
home screen never told the engine anything, so the engine did not know a car was parked and
a drive away from it was just a drive. Field report, iPhone, 2026-09-24: 4F saved by hand
at 11:42, `vehicle_enter` at 12:33, a confirmed drive from 12:45 — and the record stayed
open until the next save closed it. Most records on the device are `manual`, so in practice
§11a almost never had anything to end.

What the row does, identically on both platforms:
- **From any state, to `PARKED`**, entered at the event's time. The user has said where the
  car is; nothing the engine was inferring outranks that.
- **Whatever was being inferred is dropped, silently.** An open driving session, a parking
  transition, a departure's evidence: gone, location capture stopped, no candidate and no
  `sessionEnded` report. The user just answered the question those were building toward.
- **A pending candidate is retired**, as a rejection would retire it (§10) but without
  counting as one — the user did not say "not parked", they said "parked, here".
- **It ends nothing and proposes nothing.** The app's save flow closes the previous record
  itself — at a pending proposal's `departedAt` when there is one (§11a); the engine emitting
  anything about ending here would be about the record just written.
- **Vehicle activity is over.** The next `vehicle_enter` opens a departure's evidence, which
  §11's two bars and §7's guard then have to earn as before.

**Order (2026-09-27): `user_saved` is answered before any evidence is folded or any window
judged, on both platforms** — iOS used to judge the windows first, so a transition lapsing
at that instant reported `candidateRuleUnmet` on the way to `PARKED`.

**A candidate left behind by a new journey is withdrawn too.** A candidate left behind by
`CANDIDATE_PENDING → DRIVING_CANDIDATE` (§3a keeps it so the user can still answer it) is
withdrawn on `user_saved` on both platforms, as the pending one is. Android used to leave it
to its 45-minute expiry (§10), because its engine kept the last candidate's snapshot after it
was answered; an answered candidate no longer leaves one, so any candidate still held is live.
Pinned by the twins "A hand save withdraws a candidate left behind by a new journey" (iOS
`UserSavedParkingTests`) and `a hand save withdraws a candidate left behind by a new journey`
(Android `ParkingDetectionEngineTest`).

**The cost, accepted on 2026-09-24:** a parking saved by hand can now be ended by a ride in
someone else's vehicle — a bus or taxi that clears §11's bars and §7's guard. Detected
parkings already carried that risk (§2's fundamental limit); this extends it to every
parking. No undo notification was added with it. *Superseded 2026-09-29:* a departure now
only proposes the end (§11a), so that ride costs the user one question, not the record.

`user_saved` is a contract event (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §2), fixed by
`platform-tests/manual_save_parks.json` and by per-platform engine tests for the departure
that follows.

## 12. Taxi/Bus Mitigation
- short trip guards
- one candidate per travel session
- immediate `주차 아님`
- optional trusted car projection increases confidence
- do not attempt invasive device fingerprinting
- no speculative cloud ML MVP

## 13. Tunnel / Underground
`GPS quality degradation` is supporting evidence only.
Tunnel pattern with continued vehicle movement must remain DRIVING.
Underground parking pattern:
- reliable point captured
- accuracy worsens/disappears
- vehicle ends
- walking begins
=> candidate with last reliable point.

## 14. Persistence Checkpoints
The engine state is written, atomically, after every event (Android: every batch) that changed
any of it — a state change, a fix folded into a drive, a link edge, a flag a transition
accumulated — and after an adapter-decided end (iOS `endDrivingSession`). The §14 checkpoint
fields (`state`, `stateEnteredAt`, `lastAutomotiveAt`, `lastReliableLocation`, `lastLocationAt`,
`travelDistanceEstimate`, `candidateId`, `revision`) are a projection of it.

Checkpoint contains no backend upload behavior.

**Both platforms persist and reload their whole engine state; the only OS-level difference is
whether the capture itself survives the process** (DECIDED 2026-09-29, replaces the per-state
restore rules "A transition rebuilt from a checkpoint keeps the drive's distance", 2026-09-27,
and "A restored departure keeps its evidence", 2026-09-28). Android writes
`DetectionEngineState` and reads it back before every batch; iOS writes `DetectionCheckpoint`
schema 3 — the fields above plus `engine`, the whole `DetectionEngineRecord` the engine keeps
its state in (the open session with its start, distance, anchors and moving samples; the
parking transition with its entry reason, frozen duration and distance, accumulated evidence and
whether it holds a capture; the pending candidate's drive and stop-only window; the vehicle
level and `vehicleActiveSince`; the car-link latch; §12's one-candidate flag; whether the
session lost its capture) — to `Library/Application Support/Detection/checkpoint.json`, and
`restore` reads it back verbatim. Nothing is rebuilt, so no state has a restore rule of its own,
and a process death between two events loses nothing either changed. What a relaunch does, on
both platforms:

- **The capture survived** (an Android process death: the Fused Location request is registered
  with a `PendingIntent`): nothing runs at the relaunch. The next event settles §3a's windows
  against its own timestamp, as every event does.
- **The capture did not survive** (every iOS relaunch — Core Location standard updates end with
  the process — and an Android reboot, package replace or force-stop). One rule, applied at the
  relaunch time to the stored state, on both platforms:
  1. **A stale open session is dropped.** A session the session timeout already ends is not
     resumed: `DRIVING_CANDIDATE` / `DRIVING` past the 2-hour ceiling, or with neither vehicle
     evidence nor an accepted fix for `vehicleEvidenceTimeout` (600 s), ends in `IDLE` —
     `sessionEnded`, no candidate, no capture; a `PARKED` get-in past the ceiling or 600 s of
     vehicle silence is dropped and the parking kept (§11 "An adapter-decided end never leaves
     the parking behind"). *Rationale:* nothing observed the drive while the process was dead.
     Resuming it would let the relaunch tick promote a latched `DRIVING_CANDIDATE` or end the
     drive into a `PARKING_TRANSITION` at the relaunch time. The capture would then reopen
     wherever the phone now is, and its first stop fix would raise "주차한 것 같아요" at the
     wrong place. The drive's fixes count as evidence alongside its vehicle edges because
     Android's `IN_VEHICLE` is an edge, not iOS's continuous Core Motion stream: a drive whose
     fixes kept arriving until the death was a drive in progress. The cost is that a drive that
     went silent for 10 minutes (a long tunnel with no link) and whose process died there is
     not resumed. The next vehicle evidence starts a new trip, which still produces the
     parking at its end.
  2. **Windows that lapsed while the process was dead are settled**, stamped at their
     deadlines (§3a): a departure past §11's lapse returns to `PARKED` ending nothing, a
     transition past its window goes to `IDLE`, a stop-only window past its deadline closes
     with the candidate kept.
  3. **The capture the settled state still wants is reopened**, a stop-only window's
     included (§3a "The window lives exactly as long as its capture"), unless its session had
     lost it (§11 "A lost capture decides nothing"). It reopens in the engine's mode and under
     the planner's own deadlines, never as a permanent foreground service.

  No edge is asked at the relaunch. The next event asks them, as any event does. iOS:
  `ParkingDetectionEngine.restore` (`settleRelaunch`), then the wake's `timer_tick` in
  `BackgroundCoordinator.rehydrate`. Android: `RegistrationRecoveryReceiver` →
  `FusedLocationSessionController.reconcileAfterSystemReset` →
  `ParkingDetectionRuntime.resumeAfterSystemReset` (`dropsStaleSession`, a `TimerTick`, the
  follow).
- **Restore equals uninterrupted.** A replay of any committed fixture or draft with a process
  death injected after any event reaches the uninterrupted replay's outcome trace (and the
  golden), with no exception. Pinned by the twins "A process death after any event of any
  fixture or draft changes nothing": iOS `ParityRestoreTests`, Android
  `ParkingDetectionRuntimeRestoreTest`. Each platform also runs a non-zero-gap variant. iOS
  "A process death that lasts until the next event changes nothing" relaunches on the next
  event's wake with every §3a window judged across the gap. There, step 1 is the only rule a
  gap can add, and where it fires the replay must match up to the death and then show exactly
  its drop to `IDLE`. Android's "a process death relaunched halfway to the next event changes
  nothing" keeps its capture, so no step applies. The twins "A stale driving candidate restored
  after a relaunch reopens no capture and ends in IDLE", "A stale drive restored after a
  relaunch reopens no capture and ends in IDLE", "A drive restored inside its evidence window
  still reopens its capture" and "A reboot mid-drive whose fixes kept arriving keeps the drive"
  pin step 1 on a schema-3 checkpoint (iOS `StaleDriveRelaunchTests`, Android
  `ParkingDetectionRuntimeTest`).
- **A checkpoint from before schema 3** (iOS only, a one-time migration with no Android
  counterpart): schema 1 and 2 files still decode (schema 2's `departure` record is read into
  `legacyDeparture`), and `restore` migrates them once, the way the old reconstruction read them
  — a drive from `stateEnteredAt` and `lastAutomotiveAt` (schema 2's record if it had lost its
  capture), ended in `IDLE` if the session timeout already ends it; a transition with the stored
  distance, an unknown duration and no exit credited; a `DEPARTURE_CANDIDATE` without schema 2's
  record back to `PARKED`, ending nothing — then applies the relaunch rule above and writes the
  result back as schema 3. A corrupt file or an unknown schema version is a failed load, as
  before: reported in diagnostics, not seeded over, the engine starting from `IDLE`.
- **Privacy.** The file may hold coordinates — the drives' anchor fixes, like
  `lastReliableLocation` — and stays on the device. It is never logged, and the diagnostics
  export projects the checkpoint field by field; pinned on the encoded bytes by
  `DiagnosticsReportTests` "No engine-state anchor survives into the encoded report" and, for the
  one checkpoint string that is logged (a failed load's `diagnosticDescription`), "A corrupt
  engine-state checkpoint is reported without its coordinates".
- **Cost.** A capture writes the file on every accepted fix (a few kB, `Data.write(.atomic)`), at
  most ~1 Hz and only while §19 allows a capture; Android writes its DataStore once per batch.

Pinned by twins with one name and one event sequence — a hand-saved parking, `vehicle_enter`
at +600 s, fixes at +610 s (0 m) and +700 s (600 m), a process death:
iOS `DepartureTests` / Android `ParkingDetectionRuntimeTest`
"A departure restored after a process death can still end the parking" (relaunch, fix at +760 s,
1 300 m → `DRIVING`, parking ended at +700 s), "A short departure restored before its exit still
becomes the next parking" (`vehicle_exit` +740 s, `walking_enter` +760 s → the parking ended at
+700 s, then the fixture's `medium` candidate), "A departure restored with stale evidence returns
to PARKED and ends nothing" (first event after the relaunch at +901 s), "A departure a link
connect opened keeps its postponed lapse across a process death" (hand save, `vehicle_enter`
+600 s, link connect +950 s, process death, ticks at +1 200 s → still `DEPARTURE_CANDIDATE`,
+1 300 s → `PARKED` stamped +1 250 s), "A link connect inside a departure still postpones its
lapse after a process death", "A get-in restored before the departure bars can still open the
departure" and "A get-in restored with stale evidence reopens no capture and keeps the parking".
iOS alone pins the migration ("A departure checkpoint without its evidence returns to PARKED and
ends nothing", `DetectionCheckpointTests` "A schema 2 departure checkpoint restores its evidence
and is written back as schema 3") and the schema itself (`DetectionCheckpointTests`). Step 3 for
a stop-only window is pinned by twins of one name — iOS `ParkingTransitionEvidenceTests` (the
relaunch), Android `ParkingDetectionRuntimeTest` (`resumeAfterSystemReset`): "A relaunch inside a
stop-only window reopens its capture and keeps the resume", "A relaunch after a stop-only window
lapsed reopens no capture and keeps the candidate" and "A relaunch inside a stop-only window
whose capture cannot reopen closes it and keeps the candidate" (iOS: Core Location refuses the
reopened capture; Android: background location revoked before the reboot). Android alone pins
the rest of the system reset: "a reboot inside a departure reopens its bounded capture", "a reboot
after a departure lapsed opens no capture" and "a get-in restored inside its evidence window
still reopens its capture".

## 15. Engine Effects
Platform-independent conceptual effects:
- startBoundedLocationCapture
- stopLocationCapture
- persistCheckpoint
- createCandidate
- upgradeCandidate — §3a "A stop-only candidate takes the exit that follows it"; the pending candidate re-scored in place, same id
- issueCandidateNotification
- markParkingActive
- proposeParkingEnd(departedAt) — §11a; the engine never ends a parking itself
- requestOptionalSignalRefresh

SDK calls live in adapters.

## 16. Platform Engine APIs
### iOS conceptual
```swift
actor ParkingDetectionEngine {
    func restore(_ checkpoint: DetectionCheckpoint?)
    func handle(_ event: DetectionEvent) async -> [DetectionEffect]
}
```

### Android conceptual
```kotlin
interface ParkingDetectionEngine {
    suspend fun restore(checkpoint: DetectionCheckpoint?)
    suspend fun handle(event: DetectionEvent): List<DetectionEffect>
}
```
Implementation should serialize mutation with actor-like isolation: single coroutine scope + Mutex/channel/reducer pattern.

## 17. Deterministic Fixtures
Mandatory:
1. vehicle -> underground -> walk -> candidate
2. long red light -> drive continues -> no candidate
3. gas station -> short walk -> vehicle resumes
4. taxi -> walk -> possible candidate/known limitation
5. bus repeated stops -> no notification storm
6. tunnel GPS loss -> no candidate
7. process death/restart -> no duplicate candidate
8. permission revoked mid-trip
9. low power/battery saver degraded behavior
10. widget edit while app updates record

Both platforms run common JSON fixtures.

**`long_stop_in_traffic` — fixture #2's long form (specified 2026-09-27, committed to
`platform-tests/` once both engines implemented §3a "A stop-only candidate can still be a long
light").** `red_light_no_candidate` stops for 135 s and never reaches `movementIdleWindow`, so
nothing committed held the jam shape. Events (relative `t`, accuracy 8 m throughout):

```text
0    vehicle_enter
30   location speed 9   distanceFromPreviousM —
60   location speed 9   270
100  location speed 9   360
160  location speed 0   20
220  location speed 0   0
280  location speed 0   0        <- movementIdle deadline (100 + 180): PT and a stop-only low candidate
310  location speed 0   0
340  location speed 8   150      <- the queue moves (first reported-moving fix)
370  location speed 9   270      <- second: candidate withdrawn, the same drive resumes
```

Expected: `{"candidate": true, "finalState": "DRIVING"}` — `candidate` is the runner's "a
candidate was created" (contract §8), and this one was, silently, for 90 s. The per-session
storm count (contract §8) is `[0]`: the session took its own candidate back.

**Status (round 3, 2026-09-27).** Committed as `platform-tests/long_stop_in_traffic.json`
and replayed by both runners' "every fixture" loops, in the same change that brought Android
§3a's stop-only row; before that Android ended it in `CANDIDATE_PENDING`, a parity break. iOS
keeps one named test on the file ("long_stop_in_traffic: a jam that moves on retires its
silent candidate") for what the loop does not check: the candidate was `low` and silent, it
was withdrawn, and the storm count is `[0]`.

## 18. Field Tuning
Before public launch target at least:
- 100+ combined real parking sessions
- minimum 40 sessions/platform
- underground + outdoor
- taxi/bus negative cases
- Samsung/Pixel + multiple iPhone generations

### 재검증 대기 항목

**§7 movement evidence 거리 fallback — 지상 주행 데이터로 재검증 필요.**
현재 §7의 fallback 임계값(`2σ` 노이즈 바닥, 30s 하한, 180s 상한)은 2026-09-16/17
iPhone15,3 trace 2개에 대해서만 확인됐고, **둘 다 지하철이다. 지상 자동차 주행 trace는
아직 하나도 없다.** 지상에서는 Core Location이 speed를 정상 보고할 가능성이 높고,
그렇다면 fallback은 거의 실행되지 않는 경로가 된다. 재검증 시 최소한 다음을 확인한다.

- 지상 주행에서 `speedAvailableCount`가 실제로 채워지는가 (그러면 결함은 "지하 한정"이다)
- 지상 주행에서 fallback이 실행될 때 `derivedMovingSampleCount`가 오르는가
- 도심 협곡/터널 진입·진출 경계에서 `movementEvidenceRejectReason` 분포
- 버스/택시 음성 케이스에서 fallback이 과확정하지 않는가 (§12)

이 네 가지가 채워지기 전까지 위 임계값은 **가설 위에 선 출발점**으로 취급한다.

**Field drafts that no conformant engine can pass (2026-09-27).** Of the ten drafts labelled
"parked", three end without a candidate on both engines, identically at every event, and the
reason is the recording, not the engine. They are parity inputs, not accuracy targets. "At
every event" is committed, not measured by hand: contract §8's outcome traces
(`platform-tests/goldens/outcome-traces.golden.json`) hold each draft's per-event trace and both
runners assert it:

| draft | outcome on both | why |
|---|---|---|
| s02 | `IDLE` | old sparse capture. The session opened at `vehicle_enter` t=1908 and hit the 2-hour ceiling at 9108; the real final drive (8696–9068) and its stop have no vehicle evidence of their own, and §3a opens a session only on `vehicle_enter` or a link |
| s06 | `IDLE` | the drive goes underground (no speed after 2737, accuracy 429→1414 m); `movementIdle` opens the transition and nothing — no walk, no stillness, no exit, no speed-bearing fix — arrives before it lapses. GPS degradation alone cannot confirm (§6) |
| s31 | `PARKING_TRANSITION` | the last fix reporting ≥ 2.0 m/s is t=826, so `movementIdle` is due at 826 + 180 = 1006 and the transition is entered, stamped at that deadline, by the first event after it (the fix at t=1016). The recording ends at t=1039 — 33 s into the 300 s window — with no confirming signal. The car was probably still creeping: the reported speeds after 826 are 0–1.7 m/s, and the speedless fixes from 885 to 1039 (accuracy 18–51 m) cover ~15–40 m per 17 s. Replayed on the straight line those pairs clear the 2σ floor but average only 1.2–2.0 m/s, under §7's 2.0 m/s — and the straight line is the **largest** displacement the recorded legs admit, so this is not a replay artefact: a device measuring real chords would register no movement either. A car circling a car park at walking pace is below §7's travel bar by design, which is why the transition opened so late. Whether the source trace was split (contract §9 `splitFrom`) is unverified; check it before re-converting |

The old-capture drafts (s02–s17) have sparse, km-grade fixes: the §7 fallback rejects pairs
more than 180 s apart and displacements inside 2σ, so movement is rarely registered without
speed and those outcomes test the capture more than the engine. Do not tune `maximumBaseline`
or the 2σ floor to them; the s22+ dense-capture drafts are the evidence.

Production analytics uploads only coarse outcomes.

## 19. Battery Gate
Measure baseline vs feature-enabled:
- idle 8h
- mixed 16h day
- 1h continuous drive
- underground arrival

Tools:
- iOS: Instruments/Xcode Energy diagnostics
- Android: Battery Historian/Perfetto/system battery stats where appropriate

Do not invent fixed percentage gate before P0 baseline. Define threshold from reference devices and repeatable test protocol.

**The bounded capture runs through `PARKING_TRANSITION` (2026-09-27).** It used to stop at
the transition's entry; it now stops when the transition decides (§3a "The
`PARKING_TRANSITION` rows, exactly"). That adds at most `transitionWindow` — 300 s — of
capture per stop, and a red light that resumes costs nothing extra because the capture would
have been restarted anyway. Measure it in the "underground arrival" protocol above.

A stop-only candidate keeps the same capture until that same deadline (§3a "A stop-only
candidate can still be a long light"), so the bound per stop is unchanged: capture ends
`transitionWindow` after the drive's end at the latest, whichever of the two states holds it.

**The capture runs exactly while the engine wants it (round 3, 2026-09-27).** Each engine
exposes one answer — iOS `snapshot().isLocationCaptureWanted`, Android
`LocationCaptureModePolicy.modeWantedBy(state)` — and it is true in `DRIVING_CANDIDATE`,
`DRIVING`, `DEPARTURE_CANDIDATE`, `PARKING_TRANSITION` while its capture is held, `PARKED`
while a `vehicle_enter` has opened a departure's evidence, and `CANDIDATE_PENDING` **only**
while a stop-only window is open and still holds the capture. Nowhere else. Two consequences
bind the adapters:

- **A motion event the engine did not act on opens nothing.** An IN_VEHICLE EXIT that arrives
  in `IDLE`, `CANDIDATE_PENDING` (a candidate the car link or a walk already produced), after a
  `DRIVING_CANDIDATE` lapsed (every bus or subway ride the Transition API calls IN_VEHICLE),
  or in `PARKED`, must not start a kerb capture: the engine folds no fix outside a session
  and would drop every one of them. iOS never opened one — its capture starts only on the
  engine's `startBoundedLocationCapture` effect — and Android must not either.
- **After every event batch the adapter releases any capture the engine no longer wants**
  (iOS `releaseCaptureIfIdle`), not only when the want changed. That is what closes a capture
  whose owner went away with no state change: the stop-only window's deadline, a `walking_enter`
  or `vehicle_exit` that closed it.

**How exactly (round 4, 2026-09-27).** The two bullets above stay normative for both
adapters; an adapter that follows only want *edges*, or that opens a capture and releases it
one batch later, does not meet them — the hard limits of a capture profile bound a defect's
cost, they do not make it part of the design. Four precise rules:

1. **The want is read from a settled state.** Any question "does the engine want a capture
   now?" is asked of the state the engine would hold at that instant — after the windows due
   by `now` have fired (a `timer_tick(now)` folded first) — never of the state as it was last
   stored. iOS gets this for free: `handle` ticks before and after the edge, and the
   coordinator reads `isLocationCaptureWanted` only after a `handle`. Android's pre-batch
   question (`TransitionEventIngestor.engineWantsCapture`) must settle the stored state the
   same way: a stored `DRIVING_CANDIDATE` whose `drivingCandidateWindow` ran out, or a stop-only
   window whose deadline passed with no tick, wants nothing.
2. **"Still holds the capture" is the window's existence.** On both platforms an open
   stop-only window always holds its capture (§3a "The window lives exactly as long as its
   capture"), so neither keeps a separate bit for the window. iOS never opens a window for a
   transition that lost its capture — or whose drive had lost it before it ended (§11 "A lost
   capture decides nothing") — closes it on any capture loss, and rebuilds none on relaunch; Android
   represents it by `StopOnlyResumeWindow` being non-null, and its runtime removes a window
   whose capture is not running before the next batch. `CANDIDATE_PENDING` without a window
   wants nothing.
3. **Release after every batch, whatever the edge.** After each batch, if the settled want is
   none and a capture the *engine* opened is running, the adapter releases it. A capture the
   diagnostics screen forced on is not the engine's and is not released by this rule.
4. **Open only on a settled want.** A capture is started only when the batch's settled want is
   non-null (iOS: only on the engine's `startBoundedLocationCapture`). A motion event the
   engine did not act on leaves the want null and therefore opens nothing — not even for one
   batch.
5. **Open only for a new session (2026-09-28, N1).** A settled want is necessary, not
   sufficient: a capture is opened only when the want was null before the batch — a session
   the engine just opened — or a system reset dropped it. A session that already wanted one
   and has none running (a revoked permission, a failed request) gets none back, from the
   motion policy or the follow (§11 "A lost capture stays lost for its session").

Pinned by tests on each side: iOS `DrivingSessionLifecycleTests` "A fix after a movementIdle
entry…" (the deadline releases with no state change) and `ParkingTransitionEvidenceTests`
"A relaunch after a stop-only window lapsed reopens no capture…" (a window that lapsed
while the process was dead wants nothing); Android `TransitionEventIngestorTest` `vehicleExit_afterADrivingCandidateLapsed_opensNoCapture`
(IN_VEHICLE EXIT over a stored lapsed `DRIVING_CANDIDATE`) and `ParkingDetectionRuntimeTest`
"an exit inside a stop-only window whose capture ended opens no kerb capture" (the same exit
over a stop-only window whose capture already ended), each registering no capture at all.

**A system reset reopens what the engine still wants (2026-09-28).** On Android a reboot or a
package replace drops the Fused Location registration; the adapter then asks the settled
stored state (rule 1) and reopens the capture it wants, in its mode, under the planner's
deadlines (§14 "The capture did not survive"). Rules 3 and 4 are unchanged: a state
that wants nothing after the reset opens nothing.

**What one stop costs, per platform.** iOS keeps its single Core Location session
(`kCLLocationAccuracyBestForNavigation`, no distance filter, ~1 Hz) running through the
transition and any stop-only window: at most 300 s past the drive's end. Android's
`PARKING_TRANSITION` profile is HIGH accuracy every 5 s (fastest 3 s) under the location
foreground service, bounded by `durationMillis = transitionWindow` (300 s) **and**
`maxUpdates = 60` — up to 60 high-accuracy fixes per stop, five times the old 60 s / 5-fix
profile. That price is accepted only for a stop the engine is actually deciding; a kerb
capture the engine does not want (above) is a defect, not part of this budget. Both numbers
go into the "underground arrival" and "mixed 16h day" protocols.

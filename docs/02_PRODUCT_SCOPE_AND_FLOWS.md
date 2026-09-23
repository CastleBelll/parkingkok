# 02. Product Scope & User Flows — Cross-platform

## 1. Navigation
No mandatory bottom tab bar. Home-centered.

### 1a. When the app does get a bottom bar (2026-09-20)

Raised by the product owner: 가족 공유 is coming, and having no navigation at all feels
thin. The answer today is still no, and the condition for changing it is written here so
that it is a decision rather than an omission.

**Today there is one destination.** Home is what the app is opened for. History, the
notification log and Settings are places you visit occasionally and leave, and all three are
one tap from Home — the `전체보기` link, the bell and the gear. A bar with three tabs would
carry two nobody presses, and it would push the hero floor down on every screen, which is
the one thing docs/10's harness says must not happen.

**가족 공유 does not change that, and the reason is stronger than it first looked.** This
section was first written against the wrong reading — "see where each family member's car
is", which would have been a new screen and a peer of Home. The product owner corrected it:
it is **one car, several people.** Everyone sees the same parking.

That is not a new destination. It is the existing Home screen with a shared record behind
it: 우리 차 어디 있지, asked by three people instead of one. Nothing moves in the navigation
tree — `docs/10` keeps the invite and the membership controls as a Settings section, which
is where account-shaped things belong.

**The trigger, restated.** The bar goes in when a second screen becomes a *peer of Home* —
opened as often as Home and returned to, rather than visited and left. Nothing on the
roadmap is that today, family sharing included. If something ever is, the count becomes
three and the bar is right.

**Where the difficulty actually lives** is §1b, not here.

**There is no structural debt in waiting.** Both platforms navigate a single rooted stack
(`NavBackStack` on Android, `path: [AppRoute]` on iOS). Adding a bar means giving each tab
its own stack and wrapping the existing shell; no screen changes. Building it early would
cost the hero now and save nothing later.

### 1b. Shared parking was considered and dropped (2026-09-20)

Sharing one car between several people would not have added a destination either — the
shared thing is the Home screen itself. It was planned and withdrawn the same day; the
reasons are in `docs/01 §5a`, and none of them are navigation.

## 2. Onboarding Principle
Do not fire every OS permission at once.
Explain value immediately before each prompt.
Manual mode remains usable if permissions denied.

### Common sequence
1. Welcome
2. local privacy promise
3. smart detection explanation
4. motion/activity permission context
5. foreground location context
6. notification context
7. Home
8. user explicitly enables Smart Detection -> background location education/request

### iOS nuance
When In Use first; request Always only in context of Smart Detection.

### Android nuance
Foreground location first. Background location request is a separate step and may require navigating OS settings depending on version. Do not repeatedly nag after denial.

## 3. Permission Degradation
### No motion/activity
- auto detection unavailable
- manual parking works

### Foreground location only
- manual current location works
- background detection limited

### No notification
- local candidate can remain pending
- surface on next app open

### Background denied
- show `자동 감지 제한됨`
- never block manual save/history

## 4. Smart Detection Flow
Common product semantics:
```text
IDLE
 -> DRIVING_CANDIDATE
 -> DRIVING
 -> PARKING_TRANSITION
 -> CANDIDATE_PENDING
 -> USER CONFIRM / REJECT / EXPIRE
 -> PARKED
```

OS trigger details live in platform implementation docs.

## 5. Candidate Notification
Copy:
> 주차한 것 같아요
> 마지막으로 확인된 위치와 시간을 저장해뒀어요.

Actions always include:
- confirm/open floor entry
- `주차 아님`

Inline floor text entry is platform-dependent enhancement, not required for correctness.

## 6. Floor Parsing
Accept examples:
- b3 / B3
- 지하3 / 지하 3층
- 3층 / 3F
- P3 if user chooses custom

Normalize only when unambiguous; preserve raw text.

## 6a. Reading the Pillar

Car parks write the answer on the wall. A photo of the pillar already carries the floor,
the zone and often the bay number, so the user should not retype what the camera can read.

### Where it runs
On the photo the user takes anyway — 사진 추가 on home and detail, and the camera path from
the confirmation screen. Recognition is **on-device**: Vision on iOS, ML Kit's on-device
Korean text model on Android. The photo never leaves the phone (docs/06 §1, docs/09), and
neither does the text read from it.

The Android model must be the **bundled** artifact, not the Play-services one that fetches
on first use. An underground car park is where this feature is worth the most and where
there is no network, and a model that downloads on demand would be missing at exactly the
moment it is needed. The APK cost is the price of the feature working where it matters.

The most valuable place to offer it is the confirmation screen: the user is standing at
the pillar when the prompt arrives. `사진으로 입력` there opens the camera and lands in the
same manual entry `직접 입력` opens, prefilled (docs/10 §7a). The photo is **kept and
attached to the record**, not read and thrown away — it is the pillar photo the user would
otherwise have to take again from the detail screen.

Home and detail are different: they have no editable floor or zone field for a suggestion
to land in, and building one is not this feature. There, after a photo is attached, a
single suggestion row appears **only for fields the record leaves empty**, and applies only
when tapped. Nothing changes before the tap. A record that already says `B3` is not
second-guessed by a photo.

### What it produces
A *suggestion*, never a saved value. Recognised text is parsed with the existing rules —
floor by docs/02 §6, which already accepts `B3`, `지하 3층`, `3F` — and the result is
pre-filled into the fields the user was going to fill anyway, focused and editable.

**The grammar for the other two fields lives here**, because it exists nowhere else and the
platforms drifted on exactly that: Android read the zone and the bay from the day the
feature landed, iOS read only the floor for a fortnight, and the iOS comment explaining why
said the contract had no grammar to follow. It does now.

| field | accepted | rejected |
|---|---|---|
| zone | `A구역`, `A 구역`, `가구역` — up to six characters before the literal 구역; failing that, the pillar's own number (below) | a token with neither |
| pillar number | `B17`, `C13`, `가12` — one or two letters then one to three digits, **exactly one** in the photo | several distinct ones, or the floor badge |
| bay | `142`, `142번` — one to four digits, stored as digits | anything the floor already used (below) |

The pillar number fills the zone because that is what a person writes down: in a garage
whose pillars are labelled, "B17" *is* where the car is. It is offered **only when the photo
settles which pillar is meant** — the badge repeats on every pillar and is excluded, and a
wide shot catching B14 through B17 says nothing at all, because a confident wrong pillar
sends the user to the wrong end of the floor. A photo of the pillar in front of them leaves
one label, and one is answerable.

#### Two rules the wall forced (2026-09-23)

Both come from one photo of a B2 garage whose pillars are numbered B14–B17, and both are
mirrored on the two platforms.

**A pillar number is not a floor.** `B17` parses perfectly as 지하 17층. Korean garages
bottom out around B7 and a handful reach B10, so a suggestion is offered only within
**B10 / 20F**; past that a `B`-number is a pillar id. A deeper garage gets no suggestion,
which is what a failed read already does — and offering a wrong floor is worse than
offering none, because the user has to notice it and undo it.

**A badge whose `B` was read as an `8` is still the floor, when it repeats.** That photo
never yielded `B2` on the phone; it yielded `82`, four times, once per pillar in frame.
`B`→`8` is the ordinary confusion and correcting it blindly would invent a floor out of a
bay number, so a digit run is re-read as a floor **only when it appears more than once** —
the badge is identical on every pillar while bay and pillar numbers all differ. The digits
that correction consumes are then not offered as the bay as well.

The user always sees what was read before anything is stored. Nothing is auto-saved from a
photo: a misread `B3` as `83` that silently became the record would be worse than typing.

### When it fails
Most pillars are not photographed straight, in good light, from two metres. Recognition
failing is the normal case, not an error:

- no text found, or nothing parses → the form opens exactly as it does today, empty. No
  message, no spinner left behind, no "인식 실패" dialog
- partial read → fill what parsed, leave the rest blank
- the model is unavailable or takes too long → same as no text found

A feature that apologises every time it cannot read a wall is worse than one that quietly
helps when it can.

### Not analytics
Recognised strings are floor, zone and bay — docs/17 §3 puts those on the forbidden list
and docs/09 keeps them local.

Counting whether a suggestion was offered and kept would be how the feature earns its
place, but **v1 adds no event for it**. docs/17 §2 is a closed list of fourteen, and
widening it for a feature with no field data yet is the wrong order: ship it, watch it,
then decide what is worth measuring.

## 7. Active Home Hierarchy
1. floor
2. zone/spot
3. elapsed
4. last location action
5. photo
6. end parking

No network dependency.

## 8. Empty Home
```text
현재 저장된 주차 위치가 없어요.
주차하면 주차핀이 알려드릴게요.

[직접 저장]
```

If Smart Detection off:
`자동 감지가 꺼져 있어요 [켜기]`

## 9. Manual Save
- floor stepper/free text
- zone
- spot/memo
- photo
- current location if authorized

Local save completes before any backend work.

## 10. End Parking
Manual:
- endedAt
- commit completed history
- clear active
- widget refresh

Auto end:
- only after strong departure evidence
- notification/recoverable undo preferred during beta

## 11. Subscription Flow
Paywall uses platform store product metadata.
Show:
- localized price
- billing interval
- auto renew
- terms/privacy
- restore/manage

No deceptive preselection.

## 12. Referral
### Inviter
1. code shown
2. share
3. invitee links code before first paid conversion
4. backend verifies paid conversion
5. +7 day reward ledger
6. reward applied through store-compliant mechanism when eligible

### Invitee
Code entry alone does not unlock Plus.

## 13. Store-specific Reward UX
### iOS
`7일 혜택 사용 가능` -> eligible StoreKit promotional offer redemption.

### Android
For eligible active subscription, backend can defer next billing date through Google Play Developer API. UI shows resulting benefit after server confirmation.

## 14. Downgrade
- ads resume after entitlement refresh
- interactive widget becomes read-only/basic
- history remains local; latest 5 visible
- no deletion

## 15. Delete Local Parking Data
- confirmation
- delete local DB records
- delete photos
- clear active
- refresh widget

Do not silently delete backend referral/subscription audit data with local parking deletion.

## 16. Cross-platform Account Expectation
MVP does not require user login. Therefore:
- local parking history does not sync across iOS/Android
- paid entitlement restoration follows each store/account and backend reconciliation
- free referral balance recovery across uninstall/platform switch is not guaranteed until optional account linking is introduced

This limitation must not be hidden if user asks about transfer.

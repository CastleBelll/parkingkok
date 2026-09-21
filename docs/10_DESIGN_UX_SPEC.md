# 10. Design & UX Specification — iOS + Android

## 1. Brand
Name: **주차핀** (user-facing only — every internal identifier stays `parkingkok`; see CLAUDE.md).

Personality:
- calm
- quick
- trustworthy
- everyday utility

The mark is a car inside a location pin, matching the name. Nothing more elaborate, and no
lettering inside the app icon.

### 1a. The one goal
주차핀 should look like **a small team built it carefully**, not like **an AI generated it**.
Every rule below exists to serve that sentence, and when a rule seems to cost something, that
sentence is the tiebreaker.

The reference feeling is Apple's own apps for composure and Toss for hierarchy: a utility that
is understood at a glance. Not a map app's density, not an automotive black-and-red, not a
dashboard.

**A well-ordered app beats a pretty one.**

### 1b. Avoid — the specific tells
These are not stylistic preferences. They are the patterns that make an app read as
machine-generated, and they are forbidden:

- purple or violet in any form, including inside gradients, tints and icons
- purple→blue gradients, neon, large gradient CTAs
- heavy or layered drop shadows on cards
- decorative background blobs, glows and washes
- glassmorphism beyond a native OS material
- a card around every row — the AI dashboard
- pill-shaping everything
- decorative icons and illustrations that carry no meaning
- AI-SaaS landing-page styling
- many pastels mixed together; a different colour per button or card
- paywalls with crowns, sparkles, fake discounts or "BEST VALUE" badges
- cute mascot-driven design

### 1c. Depth without shadow
Layers are separated by **background difference, border and spacing**. Shadow is the last
resort, and when unavoidable it must be barely perceptible. This reverses the earlier craft
direction, which used two-pass shadows and gradient blooms; those are now a defect.

The mocks in `design-references/` were drawn with that older treatment. They remain binding for
information hierarchy, content and grouping, and are no longer binding for surface finish.

## 2. Shared Semantic Color Tokens
Light:
- background `#F7F8FA`
- surface `#FFFFFF`
- textPrimary `#111827`
- textSecondary `#6B7280`
- primary `#2563EB`
- accent `#14B8A6`
- divider `#E5E7EB`
- danger `#EF4444`

Dark:
- background `#0F172A`
- surface `#172033`
- textPrimary `#F8FAFC`
- textSecondary `#94A3B8`
- primary `#3B82F6`
- accent `#2DD4BF`

Implement as semantic assets/theme tokens, never scatter hex values in feature code.

### 2a. The one question the app asks on its own (2026-09-21)

**First launch asks whether to record parking automatically, once.** 자동 기록 켜기 requests
the permissions detection needs and turns the opt-in on; 나중에 records that it was asked and
leaves it off, with Settings as the ordinary way in afterwards.

It exists because the product was unreachable. Automatic detection is the whole app, and the
only way to it was a switch in Settings that nobody goes looking for: install, drive, park,
nothing happens, uninstall. `OnboardingCompleted` was an analytics event with no screen
behind it.

**It is not a default-on switch, and that distinction is the design.** A stored preference
authorizes nothing: the app would claim to be detecting while the transition registration
failed for want of `ACTIVITY_RECOGNITION`, which is a state this project shipped once and
fixed. Play's policy points the same way — background location follows an explicit user
action, never a pre-checked box. So the default a *user* experiences is on; the default in
the store stays off until they say so.

**Asked once, either way.** "Said no" and "has not been asked" are different states, so the
flag is its own key rather than an inference from the switch.

A platform dialog on both sides — `AlertDialog` and `.alert` — rather than a designed
onboarding screen: the harness asks for platform-native dialogs, and there is no mock for a
screen here to follow. If onboarding ever earns more than one question, this is where it
grows.

## 3. Platform Adaptation
Design language is shared, controls feel native.

### iOS
- SF/system typography
- SwiftUI navigation/sheets/alerts
- iOS spacing/gestures
- WidgetKit system tint behavior

### Android
- system/Roboto typography through Material 3 baseline
- Compose/Material navigation/dialog semantics
- Android edge-to-edge/insets
- dynamic color is **not** allowed to replace core 주차핀 brand colors automatically; it may be used only if product explicitly approves a dynamic-theme mode later

Do not force iOS-styled switches/navigation onto Android or vice versa.

## 4. Typography
Shared hierarchy semantics:
- current floor hero: visually 56–64sp/pt equivalent, scalable
- screen title
- primary row semibold
- supporting text

Respect:
- iOS Dynamic Type
- Android font scale

Hero value may scale down within safe minimum but must remain readable.

## 5. Shapes and Surfaces
Radius bands:
- major card 18–22
- button 12–16
- small control 10–14

Android/iOS actual shape implementation may vary slightly. Not everything is a pill.

**Shadow is not how this app shows depth.** Separate layers with background difference, border
and spacing. A shadow, if genuinely needed, is barely perceptible. This rule already existed and
was violated once by a craft pass that added two-pass shadows and gradient blooms; §1c states
the reversal.

### Card usage
A card groups information that belongs together. It is not the default wrapper for a row.
A screen that is a stack of five cards is the AI dashboard the brand is avoiding — use dividers
and spacing instead. One large card for the screen's core subject is fine; wrapping every row
is not.

## 6. Home Hierarchy
1. current floor
2. zone/spot
3. elapsed
4. +/- floor
5. location/photo
6. end parking
7. history preview

No ad between current parking and primary actions.

## 7. Detection Confirmation
Copy must communicate uncertainty:
> 주차한 것 같아요

Never show `주차 완료` before confirmation unless future trusted signal policy explicitly allows auto-confirm.

Primary: 저장/확인
Secondary: 주차 아님

## 7a. Confirmation Screen

What §7 fixes in copy, this fixes in shape, because two platforms building from a tone of
voice would each invent a layout.

```text
주차한 것 같아요

오후 8:14
마지막 위치를 저장했어요.

[ B1 ]  [ B2 ]  [ B3 ]  [ 직접 입력 ]

주차 아님
```

### Hierarchy
The uncertainty comes first and is the largest thing on the screen. Then when it happened,
then **where**, then the two ways in, then the way out.

An earlier draft of this section said "no map, no coordinate, no address" and cited
docs/09 §9. That citation was wrong — §9 is about Google RTDN and says nothing about
location — and the rule it invented made the screen claim "마지막 위치를 저장했어요" while
refusing to say which. FR-008 is the section that actually governs: a map is allowed, its
label is **`마지막으로 확인된 위치`**, and what is forbidden is wording that asserts the
exact car position when underground confidence is poor.

So the screen shows the same thumbnail the home hero does, with the accuracy circle, under
that label. With no reliable fix it says `위치 없음` in the same place rather than hiding
the row — "we saved a location" and "we have no location" are both answers, and silence is
not.

### The two ways in
Two choices, side by side, and nothing else:

```text
[ 사진으로 입력 ]   [ 직접 입력 ]
```

`직접 입력` opens the existing manual entry, empty, and saving there confirms.
`사진으로 입력` opens the camera, reads the pillar (docs/02 §6a) and opens that same manual
entry with what it read already filled in. Photo first, because it is the faster path and
typing less is the point.

Neither adds a field to this screen. §7a's shape is deliberate, and an inline text box
would put a keyboard on a screen whose job is to ask one question.

#### The quick picks, and why they are gone
This section used to open with three one-tap floor buttons, read from the floors the user
had saved before — `[B3] [B2] [B4]` — and "choosing a floor confirms in one tap" was the
whole point of the screen. The product owner removed them on 2026-09-20.

What they bought was the shortest possible answer: one tap, no form, no keyboard, which is
the behaviour docs/02 is built around ("최대한 사람이 직접 하는 걸 줄이고 싶다"). What they
cost was a row of three blue blocks that had to be scanned and understood before the two
real choices below them, on a screen the user meets while standing at a car, and a guess —
B3 is offered because you parked there last, not because the app knows where you are now.

The consequence is written down because it is not free: **every confirmation now costs at
least two screens.** `사진으로 입력` keeps the tap count low when the pillar is readable, and
that path is now doing the work the picks used to do. If field data shows confirmations
dropping, this is the first thing to reconsider.

Removing them also removed the only confirmation this screen could perform, so
`ManualParkingViewModel` / `ManualParkingSheet` is now the single place a candidate becomes
a record, and the single place the §3a machine is told about it.

### 주차 아님
Directly under the choices, separated from them by a divider, and **not pinned to the
bottom of the screen** — a control floating alone in empty space does not read as a
control at all. Full width, reachable without a scroll on the smallest supported screen,
never behind a menu or an X in a corner.

It is legible as a button while staying quieter than the picks: an outlined control, not a
filled or tinted one. Its **label** is the danger colour; its border and background are not.

An earlier draft of this paragraph said "the same tonal treatment". That was wrong twice
over. A tonal fill is what the picks wear, so it would have made 주차 아님 a fourth peer of
the three it is supposed to sit under; and a tonal fill in a neutral colour — the only way
to keep it quieter — reads as a disabled button rather than a subdued one. An outline says
"this is a control" without spending either the screen's accent or its emphasis, which
leaves the ranking picks → escapes → 주차 아님 carried by fill, then border, then content
colour.

### Why the label is red, when this section used to forbid it
This paragraph also used to say "not a colour that reads as danger", and the reasoning was
sound: 주차 아님 is the honest answer to a guess, not a destructive act, and dressing it as
a warning nudges people towards confirming a parking that did not happen — the one outcome
the detector learns nothing from.

The product owner overruled it on 2026-09-20, and the reason it is written down rather than
quietly applied is that the trade is real and may need revisiting with field data. What red
buys is that 주차 아님 cannot be missed. The harness rule "**주차 아님** must be easy to find,
not buried" is the constraint this screen has failed twice already — first pinned to the
bottom of an empty screen, then in a grey that let the eye skip it — and a colour the eye
cannot skip is the bluntest fix for that.

The compromise is that only the label carries it. A red fill or a red border would make the
quietest answer on the screen the loudest thing on it; a red word in an otherwise neutral
control is findable without being an alarm. If confirmation rates for candidates the user
did not actually park turn out to climb, this is the first thing to put back.

Rejecting returns to where the user was. It never asks why.

### Not a modal
A screen, pushed, with a normal back. A dialog that cannot be dismissed is how apps trap
people, and this one is a guess the user may simply not want to answer right now. Backing
out leaves the candidate pending until it expires (docs/05 §10a).

## 7b. Notification History

The bell in the header opens the notifications the app has raised. It used to open the
notification *settings*, which answered a question nobody had — the question people
actually have is "something buzzed while I was driving, what was it?"

```text
알림

주차한 것 같아요            오후 8:14
B3 · A구역 142 로 저장됨

주차한 것 같아요            어제 오후 6:24
주차 아님

주차한 것 같아요            9월 17일
응답 없음
```

### What is in it
Every candidate the app raised, newest first, with what became of it. Three outcomes and
no others: **저장됨** with the floor it became, **주차 아님**, and **응답 없음** for one
that expired unanswered (docs/05 §10a).

Nothing else is a notification. Trace label prompts are a diagnostics tool and do not
appear here.

### The pending row
A candidate still waiting for an answer sits at the top with **확인이 필요해요** on its
second line, and is the only row with a chevron. That is how "rows that do nothing must
not look tappable" reads from the other side: the one row that does something says so.

### Tapping a row
A candidate still pending opens the confirmation screen. One that became a record opens
that record. One that was rejected or expired does nothing — it is history, and there is
nothing left to act on. A row that does nothing must not look tappable.

### The dot
The bell carries a small dot while a candidate is unanswered, and only then. This is the
whole reason the screen exists: a notification swiped away in the car is currently lost
until it expires, and the dot is how the user finds it again.

It is a dot, not a count. There is at most one pending candidate (§12).

### Retention
The last 30 entries, local only, alongside the rest of the detection state. Older ones
fall off — a user looking further back wants the parking history, which is a different
screen and already keeps everything.

No coordinate, address or map appears here, the same rule the notification itself follows.

### Where settings went
Notification settings stay reachable in 설정 → 알림, which is where the rest of the
switches live. The bell no longer needs to be a second door to them.

## 8. Permission Education
One permission purpose per step.
Explain value before OS prompt.

Android background location screen should explain that OS may require a separate Settings step.
iOS Always screen should explain background detection directly.

## 9. Subscription
- normal screen, not trap modal
- close visible
- restore/manage visible
- one product MVP, no fake “추천/Best” badge
- price comes from store

Forbidden on the paywall: purple gradients, sparkling or animated backgrounds, oversized crown
icons, fake discount badges, "BEST VALUE" labels, heavy animation. The shape is a title, one
plain sentence, a checklist of what Plus gives, the store's price, and one button:

```text
주차핀 Plus

광고 없이
조금 더 편하게.

✓ 광고 제거
✓ 무제한 기록
✓ 고급 위젯
✓ 가족 공유

월 ₩X,XXX

[ Plus 시작하기 ]
```

## 10. Ads
- banner visually separated
- no fake close
- do not mimic history cards
- no active parking confirmation/detail obstruction

## 11. Widgets
Shared information priority:
- floor
- zone/spot
- elapsed

### iOS
WidgetKit layout/system tint.

### Android
Glance responsive sizes; launcher variations tested.

No mini map in smallest widget.

## 11a. Detail, History and Settings

### Parking detail
Map, floor, zone/number, parked-at, elapsed, photo, directions, end parking. The map and the
text must not compete for attention — one of them leads and the other supports.

### History
A list, not a dashboard. No charts, no graphs, no summary tiles.

```text
B3 · A구역 142
오늘 오후 8:14

B2 · C구역 38
어제 오후 6:24
```

A detected record carries a small `자동` badge and nothing else distinguishes it — not a
different icon, not a different colour (docs/01 §8 forbids colour-only state).

### Settings
Ordinary OS-style grouped sections, the way the platform's own Settings looks. Each setting is
a row, not its own large card.

Sections: 자동 감지 · 알림 · 권한 · 주차핀 Plus · 가족 공유 · 데이터 · 개인정보

## 12. Accessibility
### iOS VoiceOver
`현재 주차 위치, 지하 3층`

### Android TalkBack
Equivalent semantic content description; avoid duplicate icon + text announcements.

Touch targets follow each platform minimum guidance.
State is never color-only.

## 13. Loading
Local parking renders immediately.
Subscription/referral/backend state loads progressively.
Do not block home with global spinner.

## 14. Haptics
Use platform-native subtle feedback for:
- save parking
- floor change
- destructive confirmation

No haptic on passive sensor updates.


## 15. Shipping Visual Reference Set
The following generated mockups are part of the design source package and should be used as visual grounding when implementing screens or creating additional mockups.

- `design-references/01-home-main.png`
- `design-references/02-auto-detection-confirmation.png`
- `design-references/03-parking-detail.png`
- `design-references/04-history-list.png`
- `design-references/05-settings.png`

These references define:
- information hierarchy
- card density
- use of blue/mint accents
- calm utility tone
- lightweight illustration usage

Do not copy them rigidly at the expense of platform conventions or accessibility.
See also `docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md`.

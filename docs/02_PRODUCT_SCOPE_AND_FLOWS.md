# 02. Product Scope & User Flows — Cross-platform

## 1. Navigation
No mandatory bottom tab bar. Home-centered.

```text
App
├ Onboarding
├ Home
│ ├ Manual Save
│ ├ Active Parking Detail
│ │ ├ Edit Floor/Zone
│ │ ├ Location/Map
│ │ └ Photo
│ ├ History
│ ├ Plus
│ ├ Referral
│ └ Settings
```

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

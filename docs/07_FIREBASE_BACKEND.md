# 07. Firebase Backend Design — Cross-platform

## 1. Purpose
Firebase exists for trusted subscription/referral/account/config operations. Parking location/history/photo remain local.

## 2. Services
- Firebase Authentication anonymous
- Firestore
- Cloud Functions 2nd gen
- App Check
  - iOS App Attest
  - Android Play Integrity
- Remote Config
- Crashlytics optional/privacy scrubbed
- **Firebase Analytics** — `docs/17`의 이벤트 전송 수단 (2026-09-18 결정)
- no Firebase Storage

Additional Google Play integration:
- Cloud Pub/Sub for RTDN
- Google Play Developer API service credentials

### Analytics 전송 수단 (2026-09-18 결정)
`docs/17`이 이벤트 14종과 허용 속성을 정의했지만 **무엇으로 보내는지는 어디에도 없었다.**
Firebase Analytics로 정한다 — 이미 Firebase 스택 안에 있고, 배칭·오프라인 큐·재시도를
직접 만들 이유가 없다. Cloud Functions로 이벤트를 직접 받는 대안은 그 전부를 재발명하면서
호출 비용까지 든다.

**대신 SDK를 피처 코드에서 직접 부르지 않는다.** `docs/17` §2의 이벤트 이름과 §3의 허용
속성만 받는 타입 계층을 두고, Firebase는 그 뒤의 어댑터다. 이유는 §3의 금지 목록
(lat/lon, route, address, floor/spot/memo)이 **호출자의 주의가 아니라 타입으로** 막혀야
하기 때문이다. 임의 키/값을 받는 `logEvent(name, params)`를 열어두면 언젠가 층이 들어간다.

### 동의 (consent)
**동의 전에는 이벤트를 하나도 보내지 않는다.** 버퍼링도 하지 않는다 — 나중에 동의하면
그때부터 보내고, 거절하면 보낼 것이 애초에 쌓여 있지 않아야 한다.

- 기본값은 **꺼짐**. 사용자가 명시적으로 켜야 한다
- 동의 상태는 로컬에 영속. 설정에서 언제든 끌 수 있다
- 끄면 즉시 중단한다
- **절대 문구 금지.** `docs/09` §13이 명시한다 — ad/Firebase SDK 메타데이터는 기기를
  떠나므로 "아무 데이터도 나가지 않는다"고 말하면 거짓이 된다.
  쓸 수 있는 문구는 §13이 준 것뿐이다:
  > 주차 위치 좌표와 주차 사진은 주차핀 서버에 저장하지 않습니다.

## 3. Runtime
- TypeScript strict
- Node.js 22
- one intentional primary region
- Secret Manager for Apple/Google server credentials

## 4. Identity
Firebase uid != durable business account id.
Create server `accountId` UUID.

```text
accounts/{accountId}
  currentAuthUid
  createdAt
  referralCode
  rewardAvailableDays
  rewardReservedDays
  plusSummary
```

Clients store accountId securely:
- iOS Keychain
- Android Encrypted/shared secure storage strategy; avoid relying on plain preferences for durable identifiers if threat model requires stronger protection

## 5. Store Entitlements
Do not overload one `subscriptions/{account}` doc with platform-specific fields.

```text
storeEntitlements/{accountId}/platforms/apple
  productId
  originalTransactionId
  latestTransactionId
  expiresAt
  status
  environment

storeEntitlements/{accountId}/platforms/google
  productId
  basePlanId?
  latestPurchaseToken
  expiryTime
  status
  linkedPurchaseToken?
```

Backend computes account-level Plus summary from verified store state + valid promo/reward application state.

## 6. Collections
### referralCodes/{code}
server generated mapping

### referrals/{referralId}
- inviterAccountId
- inviteeAccountId
- code
- status pending|converted|reversed
- platformOfConversion
- createdAt/convertedAt
- qualifyingStoreTransactionKey

### rewardLedger/{rewardId}
- accountId
- referralId
- days=7
- state available|reserved|applied|reversed
- applicationPlatform?
- createdAt/appliedAt

### webhookEvents/{eventId}
idempotency/audit

## 7. Callable Functions
- ensureAccount
- redeemReferralCode
- getReferralDashboard
- syncStorePurchaseHint
- getEntitlementSummary
- requestIosPromotionalOffer
- requestAndroidRewardApplication (server decides eligibility)

Clients never directly set entitlement/reward/subscription docs.

## 8. Apple Event Source
App Store Server Notifications V2.
Verify signed JWS, bundle/environment expectations, then update Apple entitlement.

## 9. Google Event Source
Google Play RTDN via Pub/Sub.
RTDN is a change signal, not full entitlement truth.
After RTDN:
- call Google Play Developer API
- fetch current purchase/subscription state
- update Google entitlement

## 10. First Paid Conversion
Referral conversion occurs exactly once after backend confirms first qualifying paid transaction according to product rule.
Trials alone do not qualify unless product explicitly changes rule.

Cross-platform duplication guard:
- invitee account can convert only one referral
- unique qualifying transaction key
- converted referral immutable

## 11. Reward Application
### iOS
reward ledger -> eligible StoreKit promotional offer flow.

### Android
eligible active subscription -> server `purchases.subscriptionsv2.defer` by 7 days or accumulated supported duration.
Never use deprecated legacy defer endpoint for new code.

If store state cannot consume reward now, leave ledger `available`.

## 12. App Check
### iOS
App Attest production.
### Android
Play Integrity production.

Rollout monitor -> enforce.
Debug providers only in non-prod builds.

## 13. Anonymous Auth / Recovery
The UI remains 회원가입 없음.
Technical anonymous identity is created when backend features are needed.

Paid recovery:
- Apple transaction mapping
- Google purchase token/order mapping

Free referral credit after uninstall/cross-platform device change cannot be guaranteed without optional future account-linking. Do not imply guaranteed recovery in UI.

### 13a. Sign-in is a **link**, never a second sign-in (2026-09-21)

The UI stays 회원가입 없음 by default. Signing in is something a user reaches for, not
something the app demands, and when they do it must cost them nothing.

**The rule: `linkWithCredential` on the existing anonymous user. Never `signInWithCredential`.**

The difference is the whole feature. Linking keeps the same Firebase uid and attaches a
provider to it, so everything keyed to that uid — `accounts/{accountId}.currentAuthUid`, the
referral ledger, the entitlement — stays pointed at the same person. Signing in mints a
*new* uid and abandons the anonymous one, silently, with whatever was keyed to it. Today
there is no server state to lose, which is exactly why the rule has to be written before
there is: the failure is invisible until the moment it is expensive.

**What actually carries over, stated plainly so the UI does not overpromise:**

| | carried |
|---|---|
| parking records, photos, floor/zone/memo | **yes, trivially** — they are local files and a Room/SwiftData store, keyed to the device and not to any account (docs/00) |
| OS permissions, detection settings, consent | **yes** — nothing about them is account-shaped |
| referral / entitlement / `accountId` | **yes, and only because of `linkWithCredential`** |
| the same data on a *second* device | **no.** There is no sync. Signing in on a new phone gives that phone the same account and an empty history |

That last row is the one a user will assume the other way round. Sign-in copy must not say
백업 or 복원.

### 13b. The credential already belongs to someone else

**There are three collisions, not one (2026-09-21).** They were all reported to the user as
"이미 다른 기기에서 사용 중", and that sentence is wrong for two of them:

| iOS code | Android `errorCode` | what actually happened | what the user is told |
|---|---|---|---|
| 17025 | `ERROR_CREDENTIAL_ALREADY_IN_USE` | this Apple/Google account is attached to another Firebase user | 다른 기기에서 이미 사용 중 |
| 17012 / 17007 | `ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL`, `ERROR_EMAIL_ALREADY_IN_USE` | the **email** behind it belongs to an account with a different sign-in method | 이 이메일은 다른 로그인 방식으로 이미 쓰고 있어요 |
| 17015 | `ERROR_PROVIDER_ALREADY_LINKED` | it is already on *this* account | nothing — the screen was behind |

The middle row is not about the other device at all. A project with **one account per email
address** answers it whenever the same person signs in with Apple on the iPhone and Google
on the Android phone — which §13c says is exactly what this product produces — and sending
that user to look for another phone is a dead end. The last row was worse in a quieter way:
it reported a conflict for a screen that only needed refreshing.

`linkWithCredential` fails with the first of them when the Google/Apple account is already
attached to another Firebase user — most often the same person, on a phone they signed in on
before. Two ways out:

- **sign in to the existing account**, abandoning this device's anonymous uid and anything
  keyed to it, or
- **refuse the link** and say why.

**v1 refuses.** Abandoning a uid is unrecoverable and silent, and the app has no server state
yet that would make the trade worth it. The copy says the account is already in use on
another device and that the parking records on this phone are untouched — which is true,
because they were never in the account.

Revisit when there is something on the server worth merging, and revisit it as a merge, not
as a switch.

#### How to verify a link, and the tool that will lie to you (2026-09-21)

**`firebase auth:export` does not report `apple.com` providers.** Measured: the same uid, at
the same moment, read two ways —

```text
device   uid 8ksG8nZRP7RY7jj94Gco6i4MQv82  isAnonymous false  providers ["apple.com"]
export   uid 8ksG8nZRP7RY7jj94Gco6i4MQv82  providerUserInfo []
```

— while the Google user beside it exports its provider, email, display name and photo in
full. The export is not lagging; it reads the same both before and long after.

An hour went into that gap. The export said a link had never happened, so the screen that
said it had was treated as the broken thing; the real state was that the link *had*
succeeded, on an account that a second bug then orphaned, and every later attempt was
refused with 17025 by a credential nobody could see.

**So: never confirm an Apple link with `auth:export`.** Use the app's own view of
`Auth.currentUser.providerData` (the DEV diagnostics file writes it into the App Group
container, where `devicectl device copy from` can read it) or the Firebase console. The
export remains fine for Google and for counting accounts.

### 13c. One provider per platform, and the gap that leaves (2026-09-21)

| platform | provider offered | why |
|---|---|---|
| Android | Google | Credential Manager, already on every device, no extra SDK |
| iOS | **Apple only** | Guideline 4.8 makes Sign in with Apple mandatory the moment any third-party sign-in is offered, and it needs no SDK beyond `AuthenticationServices`. Google on iOS would mean adding the GoogleSignIn package, a reversed-client-id URL scheme, and a second button |

**The consequence, written down rather than discovered later: an account created on the
iPhone cannot be reached from the Android phone, and the reverse.** A user who signs in with
Apple on iOS and then installs the Android app gets a different anonymous uid there, with no
way to attach it to the same account.

This costs nothing today — §13a's table shows records never move between devices anyway, so
the account carries only a referral ledger and an entitlement that do not exist yet. It
stops being free the moment an entitlement does. The fix at that point is **Google on iOS**
(the GoogleSignIn package), not Apple on Android: Apple's Android flow is a web redirect
through `startActivityForSignInWithProvider`, which is more surface for the same result.

Until then, restore-purchase flows must not be described as "다른 기기에서 복원" across
platforms, because across platforms they cannot be.

## 14. Remote Config
Common values + platform overrides.
Server values always clamped by client.

Allowed:
- detector thresholds
- feature kill switches
- free history limit
- reward days <= compiled max

Forbidden:
- price text
- unsafe continuous GPS enable
- bypass purchase verification

## 15. Cost Controls
- no parking telemetry streaming
- no location/photo data
- bounded callable usage
- dashboard caches
- RTDN/JWS event docs with retention policy

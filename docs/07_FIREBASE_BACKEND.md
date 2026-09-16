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
- no Firebase Storage

Additional Google Play integration:
- Cloud Pub/Sub for RTDN
- Google Play Developer API service credentials

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

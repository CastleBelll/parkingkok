# 15. Backend API Contracts & Event Semantics — Cross-platform

## 1. Rules
- client mutation via Firebase Callable Functions unless store webhook/RTDN ingress
- Auth required
- reward-sensitive calls require App Check
- stable machine-readable error codes
- trusted timestamps server-generated
- never return raw Apple signed payloads or Google service credentials

## 2. ensureAccount
Request:
```json
{
  "clientAccountId":"UUID",
  "platform":"ios|android"
}
```

Server:
1. validate auth/app context
2. validate UUID
3. existing uid mapping -> return
4. reclaim requires trusted store proof, never blind attach
5. create account + referral code transactionally if new

Response:
```json
{
  "accountId":"...",
  "referralCode":"PK7K2M",
  "plusState":"free"
}
```

## 3. redeemReferralCode
Request:
```json
{"code":"PK7K2M"}
```

Checks:
- App Check
- code active
- inviter != invitee
- invitee no existing inviter
- invitee not already converted/paid according to rule

Response:
```json
{"status":"pending","inviterMasked":"PK****"}
```

Errors:
- INVALID_CODE
- SELF_REFERRAL
- ALREADY_REFERRED
- ALREADY_SUBSCRIBED
- REFERRAL_DISABLED
- RATE_LIMITED

## 4. getReferralDashboard
```json
{
  "code":"PK7K2M",
  "pendingCount":2,
  "convertedCount":4,
  "availableRewardDays":14,
  "rewardApplyState":"available|reserved|none"
}
```
No invitee PII.

## 5. getEntitlementSummary
```json
{
  "accountPlusState":"active|free|billingIssue",
  "platformEntitlements":{
    "apple":{"state":"active|none|expired","expiresAt":"..."},
    "google":{"state":"active|none|expired","expiresAt":"..."}
  },
  "rewardDaysAvailable":7,
  "lastVerifiedAt":"..."
}
```
Do not imply cross-platform portability solely from this summary; client follows product policy.

## 6. syncStorePurchaseHint
Client sends non-authoritative hint after purchase for responsiveness.
```json
{
  "platform":"ios|android",
  "productId":"...",
  "transactionHint":"opaque-nonsecret-identifier"
}
```
Server independently verifies with store.

## 7. requestIosPromotionalOffer
Checks:
- reward available
- eligible Apple subscription history
- no active reward reservation
- rate limit

Create short-lived reward reservation.
Return only StoreKit-required signed offer fields.

## 8. requestAndroidRewardApplication
Request:
```json
{"requestedDays":7}
```
Server:
1. validate reward balance
2. load verified Google subscription
3. ensure active/eligible
4. reserve ledger credit
5. call `purchases.subscriptionsv2.defer`
6. refetch purchase state
7. mark applied or release reservation on safe failure

Response:
```json
{
  "status":"applied|not_eligible|pending",
  "daysApplied":7,
  "newExpiryTime":"..."
}
```

## 9. Apple Notification Endpoint
`POST /apple/app-store-notifications/v2`
- verify signed payload
- validate app/environment
- idempotency
- update Apple entitlement
- first paid conversion check
- reward application reconciliation

## 10. Google RTDN Ingress
Prefer Pub/Sub-triggered Cloud Function rather than public client endpoint.
Processing:
1. parse RTDN
2. idempotency
3. call Google Play Developer API for full state
4. update Google entitlement
5. first paid conversion check
6. reward/defer reconciliation

RTDN payload alone is not entitlement truth.

## 11. Domain Audit Events
- subscription.started
- subscription.renewed
- subscription.expired
- subscription.refunded
- subscription.deferred
- referral.linked
- referral.converted
- reward.created
- reward.reserved
- reward.applied
- reward.reversed

Every event includes platform when store-specific.

## 12. Idempotency
Apple: notification UUID + transaction identity.
Google: Pub/Sub message id plus purchase state identity where applicable.
Referral conversion: invitee account + immutable referral id.
Reward doc id: stable `referral_{referralId}_7d` style.

## 13. Error Philosophy
Client localizes stable code.
Server debug text never shown verbatim to end user.

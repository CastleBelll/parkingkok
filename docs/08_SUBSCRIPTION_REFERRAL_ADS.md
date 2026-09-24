# 08. Subscription, Referral & Ads — iOS + Android

## 1. Business Model
Free:
- smart parking candidate detection
- manual save
- current parking/map
- one photo/record
- latest 5 records visible
- basic widget
- ads

Plus:
- ads removed
- unlimited history view
- interactive widget +/-
- auto departure/end
- advanced detector controls
- export
- future ongoing convenience features

Target consumer price: **about ₩1,500/month**.
Actual localized price is configured independently in App Store Connect and Play Console and always displayed from store product metadata.

## 1a. v1 ships free (2026-09-20)
**Neither ads nor Plus are in the first release.** The product owner's decision: launch free,
watch what people actually do, and add monetisation against evidence rather than against a
plan. Everything below from §2 onward stays the contract for when that happens — it is not
cancelled, it is deferred.

What exists today is the one plug-in point, and nothing else:

| | iOS | Android |
|---|---|---|
| the states | `PlusEntitlement` | `PlusEntitlement` |
| the source | `PlusEntitlementSource.current(environment:)` | `plusEntitlement(debuggable:)` |

Both answer `plusActive` on DEV/STAGING and `free` on the shipped build, so Plus-gated paths
stay buildable and testable while being invisible in production. M6 replaces those two
function bodies with a verified store transaction and touches nothing else. Features ask
`allowsPlusFeatures` and never read the state directly — `grace` and `billingIssue` both mean
"still paying", and a feature that checked `== plusActive` would lock out a subscriber whose
card is being retried.

**What was deliberately not built.** No AdMob SDK, no banner view, no UMP, no paywall, no
StoreKit or Play Billing. A banner slot with no SDK behind it and no caller is dead code, and
§12's placement rules are enforceable when there is something to place — not before. The
`Plus 구독` rows in Settings on both platforms are placeholders that say so plainly.

## 2. Product IDs
### iOS
`com.sjstudioz.parkingpin.plus.monthly`
StoreKit auto-renewable subscription.

### Android
`parkingpin_plus_monthly` or store naming equivalent.
Use one subscription with one monthly base plan at MVP.

Keep only one tier to avoid upgrade/downgrade complexity.

## 3. Entitlement Model
Common app state:
- unknown
- free
- plusActive
- grace
- billingIssue
- expired

UI can optimistically remove ads after locally verified store purchase while backend reconciles, but server-owned referral operations require backend verified entitlement.

## 4. Purchase Verification
### iOS
- StoreKit 2 verified transactions
- App Store Server Notifications V2

### Android
- Play Billing client purchase token
- backend Google Play Developer API verification
- RTDN for lifecycle changes
- acknowledge according to Play requirements

Client success callback alone is never authoritative for referral reward.

## 5. Restore / Recovery
### iOS
current entitlements + appAccountToken/originalTransactionId mapping.
### Android
`queryPurchasesAsync`/current purchases + backend purchase token verification.

Settings provides `구매 복원/구독 상태 확인` wording appropriate per platform.

## 6. Referral Eligibility
- invitee enters code before first qualifying paid Plus transaction
- binding immutable
- self-referral prohibited
- one invitee one reward conversion
- first backend-verified paid conversion only
- inviter receives 7-day reward ledger entry

추천 코드 입력만으로 paid feature를 즉시 unlock하지 않는다.

## 7. Reward Ledger
Every reward is a separate immutable ledger record.
No `rewardDays += 7` client write.
States:
- available
- reserved
- applied
- reversed

## 8. iOS 7-day Application
Do not edit App Store renewal date in Firebase.
For eligible current/previous subscribers, configure StoreKit Promotional Offer such as one-week free.
Server signs offer when required.

Multiple credits remain in backend and are consumed through eligible offer applications according to StoreKit constraints.
Never use App Store renewal-extension support mechanism intended for service issues as a general referral engine.

## 9. Android 7-day Application
For eligible active Google Play subscriptions, backend calls:
`purchases.subscriptionsv2.defer`

Result:
- Plus continues
- next billing date deferred
- user is not charged for deferred period

Use V2 API; legacy `subscriptions.defer` is deprecated in 2026.
After deferral, re-fetch subscription and update entitlement.

## 10. Reward When Not Currently Eligible
If user has reward but no currently defer/offer-eligible store state:
- keep reward `available`
- show `사용 가능한 Plus 보상 7일`
- apply after they enter an eligible subscription state

Do not implement arbitrary hidden client timer that impersonates a store subscription.

## 11. Cross-platform Purchase Policy
No automatic assumption that Apple subscription == Google subscription.
Anonymous users lack a universal login identity.

MVP policy recommendation:
- entitlement is restored on the store/platform where purchased
- backend account can know both platform entitlements if same internal account can be recovered
- cross-platform paid portability is not marketed until optional account-linking exists and store-policy review is complete

## 12. Ads
**Not in v1 — see §1a.** The rules below apply from the release that first requests an ad.

AdMob on both platforms.
Free only.

Allowed surfaces:
- history bottom adaptive banner
- settings bottom adaptive banner
- empty/non-critical screen bottom if UX-safe

Forbidden MVP:
- app-open
- interstitial
- rewarded
- candidate confirmation overlay
- active parking location obstruction

Plus: do not request ad at all.

## 13. Consent
- UMP integration per platform
- ATT only on iOS if tracking request is actually used
- app must work when tracking/consent options deny personalized tracking
- consent state refresh before ad requests where required

## 14. Price / Paywall Copy
Never hardcode `₩1,500` in runtime paywall as authoritative.
Show localized `Product.displayPrice` / Play ProductDetails formatted price.
Marketing docs may say 목표 월 1,500원.

Paywall must include:
- billing interval
- localized price
- auto-renew disclosure
- manage/cancel route
- promo terms when relevant

## 15. Fraud Controls
Referral reward only after server verification.
Rate-limit code redemption.
App Check required on reward callables.
No invasive hardware fingerprinting.
Store transaction uniqueness + account binding + App Check are primary controls.

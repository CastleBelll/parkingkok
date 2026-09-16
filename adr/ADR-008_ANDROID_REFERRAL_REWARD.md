# ADR-008 — Android Referral Reward via Subscription Deferral

Status: Accepted

## Context
추천 1건당 Plus 7일 보상을 구독 중 사용자에게 실제 billing cycle에 반영하고 싶다.

## Decision
Android에서 eligible active subscription은 Google Play Developer API `purchases.subscriptionsv2.defer`를 서버에서 사용한다.

- reward ledger is source of truth
- only backend invokes defer
- legacy `subscriptions.defer` 사용 금지
- RTDN + Developer API로 resulting entitlement 재동기화

## Fallback
구독 상태/상품 유형 때문에 defer 불가능한 경우 reward를 `available`로 보관하고 다음 eligible state에 적용한다. 클라이언트가 임의 Plus 기간을 조작하지 않는다.

# ADR-004 — Referral Rewards Stay Inside Store Billing Rules

Status: Accepted

## Context
A referral code must not become an untrusted custom license switch that bypasses platform billing for digital Plus features.

## Decision
- code linking creates no Plus access by itself
- first verified paid invitee conversion creates 7-day backend reward ledger
- iOS: apply through eligible StoreKit Promotional Offer strategy
- Android: apply to eligible active subscription through Google Play Developer API `purchases.subscriptionsv2.defer`
- clients never mutate reward or expiry authority directly
- if reward cannot currently be applied, retain `available`

## Consequence
Reward UX differs by store under the hood but product meaning remains “추천 성공 1건 = Plus 7일 보상 크레딧”.

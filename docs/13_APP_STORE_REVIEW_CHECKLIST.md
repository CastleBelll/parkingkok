# 13. App Store Review Checklist — 2026

## 1. Build Requirement

As of 2026-04-28 Apple requires App Store Connect uploads built with Xcode 26+ and iOS 26 SDK+.
Use current stable Xcode in release pipeline.

## 2. Minimum Functionality 4.2

App must demonstrate real utility beyond a single B3 counter.
Reviewer should see:
- smart detection architecture
- local notifications
- parking history
- map
- photo
- widget
- subscription/referral

## 3. Subscription 3.1.2

Explain ongoing Plus value.
Ensure subscription available through StoreKit and recoverable across devices via purchase restore/entitlement.

Paywall must clearly show:
- price
- billing period
- auto-renew
- restore
- terms/privacy

## 4. Referral / IAP 3.1.1

Do not implement referral code as custom premium license.
Reward redemption must use StoreKit-supported mechanism for paid digital feature access.

## 5. Background Location

Review Notes draft:

```text
주차콕은 사용자가 차량 이동을 마치고 걸음을 시작한 상황을 감지하여
주차 후보를 생성하는 기능이 핵심 기능입니다.

앱은 유휴 상태에서 고정밀 GPS를 계속 사용하지 않고 significant-change
location monitoring을 사용합니다. 자동차 이동이 확인된 제한된 구간에서만
더 상세한 위치 업데이트를 사용하며, 주차 후보 생성 후 종료합니다.

정확한 주차 좌표와 주차 사진은 개발자 서버/Firebase에 업로드되지 않고
사용자 기기에만 저장됩니다.
```

English review note should be included as App Review uses global reviewers.

## 6. Motion Permission

Explain motion is used to distinguish automotive/walking transition.
App works manually if denied.

## 7. AdMob

- UMP implemented
- ATT only if used
- tracking denial not gate
- ad placement does not interfere with core action

## 8. Reviewer Testability

Automatic driving flow is hard for reviewer to reproduce.
Provide:
- clear manual save path
- screenshots/video attachment explaining smart detection
- optional hidden review-safe demo mode only if it does not misrepresent production and is documented; avoid shipping secret bypass that unlocks paid features

## 9. CarPlay

Do not claim CarPlay support before entitlement approval.

## 10. Privacy

App privacy answers match SDK reality.
Privacy policy mentions:
- Firebase Auth/referral/subscription
- AdMob
- diagnostics if any
- local-only parking location/photo

## 11. Age Rating

2026 updated age rating questionnaire completed in App Store Connect.

## 12. Metadata

Avoid claims:
- `100% 자동 주차 감지`
- `정확한 차량 위치를 항상 찾음`

Prefer:
- `스마트 주차 감지`
- `마지막으로 확인된 위치`

## 13. Before Submit

- subscription products submitted/approved with app as needed
- sandbox notification endpoint test successful
- restore purchases tested
- no placeholder URLs
- backend live
- support contact works

# 12. Release & Operations Runbook — iOS + Android

## 1. Repositories
Recommended monorepo for coordination:
```text
/apps/ios
/apps/android
/backend/functions
/contracts
/platform-tests
/docs
```
Independent repos are possible, but monorepo simplifies shared fixture/contract updates for a small team.

## 2. Branching
Trunk-based:
- protected main
- short feature branches
- release tags

## 3. CI Matrix
### Common
- contract/schema checks
- backend typecheck/tests
- detector fixture parity report

### iOS
- pin Xcode
- build
- Swift tests
- lint/format
- archive staging/TestFlight

### Android
- pin JDK/Gradle/AGP/Kotlin versions
- `./gradlew test lint`
- Compose/UI tests selected
- assemble/bundle staging
- Play internal testing artifact

## 4. Environments
Firebase:
- dev
- staging
- prod

iOS:
- local StoreKit / Sandbox / Production

Android:
- Play Billing test/internal track / Production

Never point debug build at prod reward mutation endpoints.

## 5. Secrets
CI/Secret Manager:
- Apple signing/App Store Connect keys
- Apple promo signing
- Google Play service account/API credentials
- Firebase deploy identity

Never commit JSON service-account private keys or `.env` secrets.

## 6. Store Setup Early
### App Store
- bundle id
- subscription group/product
- promo offer
- server notifications V2
- privacy/terms

### Play Console
- application id
- app signing
- subscription/base plan
- RTDN Pub/Sub
- Developer API access
- Data Safety
- background location declaration when required
- Play Integrity linked project

## 7. App Check Rollout
- dev debug provider
- staging real provider validation
- prod monitor
- enforcement after metrics healthy

## 8. Webhook/RTDN Operations
Apple:
- verify JWS
- reconcile App Store state

Google:
- Pub/Sub RTDN
- call Developer API for complete state

Both:
- idempotent
- observable non-2xx/errors
- replay-safe

## 9. Feature Kill Switches
Remote Config:
- smart_detection_enabled
- auto_end_enabled
- referral_enabled
- ads_enabled
- android_bounded_location_enabled
- ios_bounded_location_enabled

Client safety bounds always override remote value.

## 10. Incident Priority
SEV-1:
- charged users lose Plus broadly
- referral exploit
- location/photo unexpectedly transmitted

SEV-2:
- detector broken on one major platform/OEM
- widget stale broadly
- Plus sees ads

## 11. Rollout
Do not launch 100% immediately.
Suggested:
- iOS phased release
- Google Play staged rollout
- detection feature remote flag can ramp separately

## 12. Support Diagnostics
Copyable diagnostics:
- app version/build
- platform/OS/device model coarse
- permission states
- detection state
- entitlement coarse state
- anonymous support id
- transition registration status Android

Never include coordinates/photo/memo/store signed payload.

---

# Automated Store Delivery

App Store / Play Store delivery는 `docs/18_CI_CD_AUTOMATED_RELEASE.md`를 authoritative 문서로 사용한다.

핵심 운영 모델:

```text
PR -> CI
main -> TestFlight Internal + Play Internal
vX.Y.Z -> App Review / Play Production 자동 제출
approval -> phased/staged release
```

모바일 바이너리는 즉시 rollback이 어렵기 때문에 production에는 점진 배포와 Remote Config kill switch를 반드시 사용한다.

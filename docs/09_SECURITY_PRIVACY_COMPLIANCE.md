# 09. Security, Privacy & Compliance — iOS + Android

## 1. Assets
- store subscription entitlement
- referral credits
- anonymous/internal account mapping
- local parking coordinates/photos/notes

## 2. Threats
- patched client claims Plus
- referral farming
- Apple/Google webhook replay
- Firestore direct mutation
- stolen store server credentials
- sensitive location leakage in telemetry
- malicious/invalid Firebase client
- ad privacy misconfiguration

## 3. Trust Boundaries
Client is untrusted for financial/reward authority.
Server requires:
- Firebase Auth where used
- App Check
- Apple signed transaction/JWS verification or Google Developer API verification

## 4. App Check
- iOS: App Attest
- Android: Play Integrity

Do staged enforcement after monitoring valid production/test traffic.

## 5. Local Sensitive Data
Parking coordinates/photos remain in OS app sandbox.
Do not add custom crypto unless a specific threat justifies secure key lifecycle complexity.

**Shared parking is that specific threat (2026-09-20, `docs/20_SHARED_PARKING.md`).** One
open parking per car leaves the sandbox so other members can see it. It leaves encrypted:
AES-GCM-256 with a per-car key that is never written to Firestore, wrapped to each member's
X25519 public key. Everything else — history, photos, traces — stays local.

The justification the sentence above asks for: what leaves is not where someone went last
week, it is where this family's car is right now, and the same Firestore rules mistake
against plaintext exposes present whereabouts while against ciphertext it exposes a blob.
The key lifecycle is affordable only because the document is ephemeral — a lost key costs
the current parking and nothing else. Do not reuse this reasoning for durable data.

Encryption buys no compliance relief. The app still collects and transmits location, the
Data Safety form and the privacy label still say so, and **§20 §4's 위치정보법 question is
open and must be answered before this ships.**

## 6. Secure Identifiers
- iOS: Keychain for durable account link hints
- Android: use platform-secure storage strategy for durable sensitive identifiers; avoid placing secrets in plain DataStore/SharedPreferences
- store server private keys never ship in clients

## 7. Backend Rules
Default deny.
Client cannot enumerate referral codes/accounts.
All reward/entitlement mutations via trusted functions/admin credentials.

## 8. Apple Notification Security
Verify signed JWS and expected app/environment identifiers.
Idempotency before side effects.

## 9. Google RTDN Security
RTDN Pub/Sub delivery is not itself final purchase truth.
After event, query Google Play Developer API and recompute current state.
Deduplicate processing.

## 10. Referral Abuse
- inviter != invitee
- immutable bind
- first paid conversion
- unique transaction/purchase key
- one reward per invitee
- callable rate limits
- App Check

No invasive device fingerprinting.

## 11. Logging
Prohibited:
- lat/lng
- route
- place name derived from exact location
- zone/spot/memo
- photo path/content
- full signed purchase payload

Allowed:
- state name
- confidence bucket
- accuracy bucket
- duration bucket
- platform/OS version

## 12. Crashlytics
Optional. No parking object attachment/custom keys containing sensitive data.
Review SDK breadcrumbs.

## 13. Ads Privacy
UMP per platform.
Accurate App Store privacy labels / Google Play Data Safety.
Do not make absolute “no data leaves device” claim because ad/Firebase SDK metadata may leave device.

Safe claim:
> 주차 위치 좌표와 주차 사진은 주차핀 서버에 저장하지 않습니다.

## 14. Background Location Disclosure
Both stores require necessity/clarity.
Product education explains:
- automatic parking needs motion + location around trips
- high-accuracy location not continuously used
- manual mode works without background detection

## 15. iOS Review
Explain Always/background purpose, bounded strategy, local-only sensitive data.

## 16. Google Play Review
Background location must be core functionality and prominently disclosed before permission.
Prepare Play Console declaration/video/screens as required by current policy.

## 17. Local Backup Disclosure
Review iOS backup / Android Auto Backup behavior; do not claim data never enters cloud backup if OS backup actually includes app-private records.
If necessary, exclude sensitive local files from backup or update disclosure.

## 18. Account Deletion
Anonymous backend data still requires deletion/support policy where applicable.
Provide in-app/support path for backend referral/account deletion, subject to legally necessary transaction/fraud record retention.

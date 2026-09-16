# 주차콕 Backend — Firebase Cloud Functions (2nd gen)

`docs/07_FIREBASE_BACKEND.md` / `docs/15_API_CONTRACTS_AND_EVENTS.md` 계약을 구현하는 서버 코드.
현재는 **툴체인 스캐폴드 단계**(Phase -1 / T-1.4)이며 비즈니스 로직은 M5~M7에서 추가한다.

> ⚠️ **아직 배포하지 않는다.** Firebase 프로젝트는 M5에서 생성한다.
> 그래서 `.firebaserc`(project alias)는 이 저장소에 없다 — 루트 `.gitignore`가 이미 제외하고 있고,
> CI가 주입한다(`docs/18_CI_CD_AUTOMATED_RELEASE.md`).

---

## 1. 요구 사항

| 항목 | 버전 | 강제 방법 |
|---|---|---|
| Node.js | **22.x** | 저장소 루트 `.nvmrc`, `package.json` `engines.node` |
| npm | 10.x | Node 22에 동봉 |

호스트 기본 Node가 24이므로 **backend 작업 전 반드시 22로 전환**한다.

```bash
# 저장소 루트에서 (.nvmrc 를 읽는다)
nvm install   # 최초 1회
nvm use
node -v       # v22.x 확인
```

## 2. 로컬 실행

```bash
cd backend
npm ci          # package-lock.json 기준 결정적 설치
npm run build   # tsc → lib/
npm run lint    # ESLint (flat config)
npm test        # build 후 node:test 실행
```

| 스크립트 | 설명 |
|---|---|
| `npm run build` | `tsc` → `lib/`. 배포 산출물. |
| `npm run typecheck` | emit 없이 타입만 검사 (CI 빠른 게이트용) |
| `npm run lint` / `lint:fix` | ESLint type-aware 룰셋 |
| `npm test` | `build` 후 `node --test "lib/**/*.test.js"` |
| `npm run clean` | `lib/` 삭제 |
| `npm run serve` | build 후 Functions 에뮬레이터 (`firebase-tools` 별도 설치 필요) |

### 에뮬레이터

`firebase-tools`는 프로젝트 의존성에 넣지 않았다(전역/CI 툴). 필요할 때:

```bash
npm install -g firebase-tools
cd backend && npm run serve   # http://localhost:5001/<project>/asia-northeast3/healthcheck
```

프로젝트 alias가 없으므로 `--project demo-parkingkok` 같은 더미 id로 실행한다.

## 3. 디렉토리 구조

```text
backend/
  firebase.json          # functions.source = "." (이 디렉토리가 곧 functions root)
  tsconfig.json
  eslint.config.mjs
  src/
    index.ts             # setGlobalOptions + 함수 export 만. 로직 금지.
    config/
      runtime.ts         # region / maxInstances 등 배포 런타임 상수
    core/
      errors.ts          # stable error code ↔ HttpsError 매핑 (docs/15 §13)
      validation.ts      # zod payload 검증 헬퍼 (docs/16 §9)
    functions/
      healthcheck.ts     # 배포 스모크 테스트용 최소 함수
  lib/                   # tsc 산출물 (git ignore)
```

M5~M7에서 추가될 위치:

| 기능 | 위치 |
|---|---|
| callable (`ensureAccount`, `redeemReferralCode`, …) | `src/functions/<name>.ts` + `src/index.ts` export |
| 요청/응답 스키마 | 각 함수 파일 또는 `src/schemas/` |
| Firestore 접근 | `src/repositories/` (Controller → Service → Repository) |
| Apple ASSN v2 / Google RTDN ingress | `src/functions/webhooks/` |

`src/index.ts`는 export 목록만 유지한다 — Cloud Functions는 배포 시 이 파일을 로드해
함수를 열거하므로, 여기에 무거운 초기화를 넣으면 전 함수의 콜드스타트가 느려진다.

## 4. 런타임 결정 사항

| 결정 | 값 | 근거 |
|---|---|---|
| 리전 | `asia-northeast3` (서울) | `docs/07` §3 "one intentional primary region". 한국 대상 제품. |
| `maxInstances` | 10 | `docs/07` §15 비용 통제. 파킹 텔레메트리 스트리밍이 없어 호출량이 본질적으로 적다. |
| 모듈 시스템 | CommonJS | firebase-functions 2nd gen 표준 구성. 컴파일 산출물을 `node --test`가 그대로 실행 가능. |
| TypeScript | `~5.9` (7.x 아님) | `typescript-eslint` peer 범위가 `>=4.8.4 <6.1.0`. TS 7로 올리면 type-aware 린트가 죽는다. |
| 테스트 러너 | `node:test` (내장) | 의존성 0개. jest/vitest 선택은 실제 테스트 요구가 생기는 M5로 미룬다. |

## 5. Payload schema validation — **zod** 채택

`docs/16_CODING_STANDARDS.md` §9는 "payload schema validation" + "no `any` in business paths"를
동시에 요구한다. 이 둘을 한 번에 만족시키는지가 선정 기준이었다.

| 후보 | 판단 |
|---|---|
| **zod** ✅ | 스키마가 곧 TS 타입(`z.infer`)이라 런타임 검증과 정적 타입의 소스가 하나다. `safeParse`가 `issues[]`를 구조화해 돌려주므로 `docs/15` §13의 stable error code로 손실 없이 매핑된다. 런타임 의존성 0개, MIT. |
| valibot | 번들이 더 작지만 Cloud Functions 콜드스타트에서 체감 차이가 유의하지 않고, 레퍼런스/생태계가 얕다. |
| ajv | JSON Schema 표준이라 타입을 별도 선언해야 한다 → 스키마와 타입이 이중 소스가 되고 `any` 유입 경로가 생긴다. §9 위반. |
| TypeBox | 추론은 되지만 보일러플레이트가 많다. Firestore 문서 계약 수준에는 과한 도구. |

**결론: zod v4.** 검증은 반드시 `src/core/validation.ts`의 `parsePayload()`를 통과시킨다 —
실패 시 값이 아니라 **필드 경로만** 로깅하므로 좌표/사진 경로가 로그에 새지 않는다
(`docs/09_SECURITY_PRIVACY_COMPLIANCE.md`).

```ts
const requestSchema = z.object({
  clientAccountId: z.uuid(),
  platform: z.enum(['ios', 'android']),
});

export const ensureAccount = onCall((request) => {
  const payload = parsePayload(requestSchema, request.data); // 타입 확정 + 미지 키 제거
  // ...
});
```

## 6. 코드 규칙

- **TypeScript strict** + `noUncheckedIndexedAccess` / `exactOptionalPropertyTypes` 등 추가 게이트.
- ESLint는 `strictTypeChecked` + `stylisticTypeChecked` (type-aware). `no-explicit-any`는 error.
- `console.*` 금지 → `firebase-functions`의 `logger` 사용. 로그에 **좌표/사진 경로/층·구역 금지**.
- 클라이언트에 나가는 에러는 항상 `ApiError` → `toClientFacingError()`를 거친다.
  SDK 메시지·스택이 그대로 나가면 안 된다.

## 7. 하지 말 것

- `firebase deploy` — M5 이전 금지.
- `.firebaserc` / `firebase.json`에 실제 project id 하드코딩.
- `service-account*.json`, `.p8`, `.jks` 커밋 (루트 `.gitignore`가 막고 있지만 확인할 것).
- 위치 좌표 / 주차 사진 / 층 / 구역 / spot memo를 Firestore에 저장 (`CLAUDE.md` Hard Constraints).

## 8. 알려진 transitive 취약점 (조치 불필요, 재평가 대상)

`npm audit`이 moderate 2건을 보고한다:

```text
firebase-admin@14 → @google-cloud/storage@8 → gaxios@6.7.1 → uuid@9.0.1
GHSA-w5hq-g745-h8pq  (uuid v3/v5/v6 buffer bounds check)
```

**도달 불가능하다.** `docs/07_FIREBASE_BACKEND.md` §2가 Firebase Storage를 쓰지 않는다고
못박고 있어 `firebase-admin`의 Storage 모듈을 import할 일이 없고, 우리 코드는 `uuid`를
직접 호출하지도 않는다. `npm audit fix`로는 해소되지 않으며(상위 Google SDK가 gaxios 6을 고정),
`overrides`로 강제 승격하는 것은 검증 없이 Google SDK 의존 트리를 바꾸는 것이라 하지 않는다.

`firebase-admin`이 `@google-cloud/storage`를 올릴 때 다시 확인한다.

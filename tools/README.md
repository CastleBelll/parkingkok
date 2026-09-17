# tools — trace → fixture 변환기

`docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §9 trace를 §8 parity fixture로 변환하고,
`platform-tests/`의 fixture가 계약 스키마를 만족하는지 검사한다.

fixture를 **어떻게 만들고 라벨링하는지**는 [`../platform-tests/README.md`](../platform-tests/README.md)를 본다.

## 왜 TypeScript인가

`backend/`가 이미 Node 22 + TS strict다. 두 번째 런타임을 들이지 않는 것이 규칙이고,
`tsconfig.json`/`eslint.config.mjs`도 backend와 같은 strict 설정을 그대로 쓴다 —
품질 기준이 저장소에 하나만 존재한다.

**런타임 의존성은 0개다.** 현장에서 trace를 변환하는 사람은 백엔드 개발자가 아니므로
`node lib/cli.js`가 `node_modules` 없이 돌아야 한다. 스키마 검증은 `schema.ts`에
직접 구현했다(zod 대신). 부수 효과로 "스키마에 없는 키는 거부"라는 좌표 금지의
구조적 절반이 `allowOnlyKeys` 한 군데로 모였다. TypeScript/ESLint는 devDependency이며
개발·CI에서만 필요하다.

## 사용법

```sh
npm install        # 최초 1회 (devDependencies만)
npm run build
npm test           # build + node --test
npm run lint

node lib/cli.js --help
node lib/cli.js convert <trace.json> [--name <name>] [--initial-state <STATE>] [--out <file>|-] [--repair]
node lib/cli.js validate [paths...] [--allow-draft]
```

`--repair`는 다시 만들 수 없는 기록을 구조하는 opt-in 경로다. 기본 동작은 그대로
시간 역행을 거부한다. 무엇을 몇 개 바꿨는지 출력하고 초안의 `_todo.repair`에
원본 인덱스를 남긴다 — 자세한 규칙은
[`../platform-tests/README.md`](../platform-tests/README.md).

## 구조

| 파일 | 책임 |
|---|---|
| `contract.ts` | 계약 §2~§5, §9 어휘. 전부 문서 인용이며 도구가 발명한 값은 없다 |
| `schema.ts` | 구조 검증 헬퍼. `allowOnlyKeys`가 미지의 키를 막는 단일 지점 |
| `privacy.ts` | 좌표 금지. 필드 목록이 아니라 텍스트를 검사한다 |
| `trace.ts` | §9 trace 파서 |
| `repair.ts` | `--repair` 전용. 재전달 제거 + 시간 정렬. 기본 경로에서는 실행되지 않는다 |
| `fixture.ts` | §8 fixture 파서 + 직렬화. confirmed / draft 구분 |
| `convert.ts` | trace → draft fixture. 순수 함수, clock 없음 |
| `cli.ts` | `convert` / `validate` |

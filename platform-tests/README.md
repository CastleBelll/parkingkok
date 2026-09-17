# platform-tests — cross-platform parity fixtures

이 디렉터리의 `*.json`은 iOS `ParkingDetectionEngine`과 Android `ParkingDetectionEngine`이
**같은 입력에 같은 제품 결과**를 내는지 검증하는 계약이다. 스키마는
`docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §8이 정의하고, 이 문서는 그 fixture를
**어떻게 만들고 라벨링하는지**를 다룬다.

> fixture runner(Swift/Kotlin)는 아직 없다. 엔진 상태머신이 M3에 들어온 뒤에 붙는다.
> 지금 존재하는 것은 fixture를 **만드는** 변환기와 **검사하는** 검증기다(`tools/`).

## 1. trace와 fixture는 다른 것이다

| | trace (§9) | fixture (§8) |
|---|---|---|
| 누가 만드나 | 기기가 자동 기록 | trace에서 변환 + 사람이 리뷰 |
| 시각 | 절대 시각 `atMillis` | 첫 이벤트 기준 상대 초 `t` |
| 메타데이터 | sessionId/기기/OS/앱 버전/라벨 | 없음 |
| `expected` | 없음 | 있어야 한다 |
| 커밋하나 | 아니오 | 예 |

변환은 **trace → fixture 한 방향뿐이다.** 기록 메타데이터가 parity 계약을 오염시키면
안 되고, 처음부터 fixture 형태로 기록하면 절대 시각과 라벨을 잃는다.

## 2. 왜 trace로 모으나

실주행 20회를 일부러 반복하는 것이 Gate M0의 병목이다. 목적은 **평소 이동만 해도**
데이터가 쌓이게 하는 것이다.

- 버스·지하철은 차 없이 탈 수 있고 spec이 요구하는 negative 케이스다(엔진 §17 #5, #6)
- 택시는 §2가 말하는 근본 한계 그 자체다 — 공개 API는 자가용과 택시를 구분하지 못한다
- **조수석도 유효한 세션이다.** 운전하지 않아도 기록은 유효하다

## 3. 기록하고 라벨링하는 절차

1. 기기에서 trace recorder를 켠 채로 평소대로 이동한다
2. 이동이 끝나면 앱에서 세션에 라벨을 붙인다:
   - `mode`: `car` | `bus` | `subway` | `taxi` | `walk` | `still` | `unknown`
   - `parked`: 이 세션이 **차를 주차하며** 끝났으면 `true`, 아니면 `false`,
     모르겠으면 `null`. 버스에서 내린 것은 주차가 아니다
   - `note`: 사람이 읽을 메모. **좌표·주소를 절대 적지 마라** — 변환기가 거부한다.
     이 필드는 fixture로 넘어가지 않는다
3. trace 파일을 회수한다. 진단 파일과 같은 경로다:
   - iOS: `xcrun devicectl device copy from ...`
   - Android: `adb exec-out run-as com.parkingkok.app cat ...`
   - sudo/root 불필요

라벨이 없으면 `mode: "unknown"`이고, 변환기는 `expected` 후보를 제안하지 않는다.

## 4. 변환

```sh
cd tools && npm install && npm run build     # 최초 1회
cd ..

# trace → 초안(draft)
node tools/lib/cli.js convert <trace.json> --name vehicle_underground_then_walk
# → platform-tests/drafts/vehicle_underground_then_walk.json

# 기록이 주행 중간부터 시작했다면
node tools/lib/cli.js convert <trace.json> --name ... --initial-state DRIVING
```

### 시간이 역행하는 trace — `--repair`

변환기는 기본적으로 시간 역행 이벤트를 **거부한다**. 그건 recorder 결함이고,
조용히 정렬해 넘기면 결함이 보이지 않게 된다.

다만 기록은 다시 만들 수 없다. 2026년 9월 지하철 trace가 그 경우다 — Core Location이
significant-change 재등록 때 캐시 fix를 다시 넘겼고, 당시 recorder가 그걸 이미 기록한
시각 뒤에 한 번 더 적었다. iOS recorder에는 그 뒤로 watermark가 생겼지만
(`TraceRecorder.admitLocation`), 이미 디스크에 있는 파일은 그대로다.

```sh
node tools/lib/cli.js convert <trace.json> --name ... --repair
```

`--repair`가 하는 일은 두 가지뿐이고, **바꾼 것을 전부 출력한다**:

- **같은 관측의 재전달을 버린다.** 이미 기록된 시각에 같은 타입으로 들어온 이벤트 중,
  기기가 *관측한* 필드(정확도·속도·confidence·bucket)가 전부 같은 것.
  `distanceFromPreviousM`은 관측값이 아니라 recorder가 이전 좌표에서 *계산한* 값이고,
  좌표는 절대 영속화되지 않으므로(§9) 재전달본은 앵커를 복원하지 못해 거리를 잃는다.
  잃는 것은 재전달의 증거지만, **다른 값을 주장하면 재전달이 아니다**
- **늦게 도착한 관측을 제자리로 옮긴다.** 파일 어디에도 없는 관측이면 실제 기록이므로
  버리지 않고 `atMillis` 기준 안정 정렬로 되돌린다

그 둘 중 어느 쪽도 아니면 **거부한다.** 같은 시각·같은 타입인데 관측값이 서로 다른
이벤트는 중복이 아니라 미지의 상황이고, 어느 쪽이 진짜인지 도구가 추측하지 않는다.

복구된 초안은 `_todo.repair`에 **원본 trace의 이벤트 인덱스**를 남긴다. 승격 전에
그 인덱스를 원본에서 직접 확인한다.

```json
  "_todo": {
    "repair": { "droppedReplayIndices": [53, 54], "reorderedIndices": [] }
  }
```

변환 결과는 **초안이지 fixture가 아니다.** `expected`가 `null`이고 `_todo` 블록이 붙는다:

```json
  "expected": null,
  "_todo": {
    "status": "needs_human_review",
    "proposedExpected": { "candidate": false },
    "rationale": "The label says \"bus\", a required negative case ...",
    "source": { "sessionId": "...", "labelMode": "bus", "labelParked": false, ... }
    // "repair": { ... }  — --repair로 구조한 기록에만 붙는다
  }
```

### `expected`는 왜 사람이 채우나

**기록은 곧 정답이 아니다**(§9). 라벨은 *무슨 일이 있었는지*를 말할 뿐이고,
`expected`는 *엔진이 무엇을 했어야 하는지*다. 그건 판단이다.

변환기는 라벨이 실제로 함의하는 것만 제안한다 — 보통 `candidate` 하나다.
`confidence`와 `requiredReasons`는 **절대 제안하지 않는다.** 어떤 라벨도 그것을
함의하지 않기 때문에 `proposedExpected` 타입에 아예 자리가 없다.

### 초안을 fixture로 승격하기

1. `_todo.rationale`을 읽고 이벤트를 직접 확인한다.
   특히 §6 후보 규칙 세 가지가 실제로 이벤트에 있는지 — 의미 있는 차량 세션,
   그 세션의 종료, 확인 신호. **GPS 열화만으로는 후보가 될 수 없다**
2. `expected`를 채운다 (`candidate`, `finalState`, 필요하면 `confidence`,
   `requiredReasons`)
3. `_todo` 블록을 **삭제한다**
4. 파일을 `platform-tests/drafts/`에서 `platform-tests/`로 옮긴다
5. `node tools/lib/cli.js validate` 통과 확인

## 5. 검증

```sh
node tools/lib/cli.js validate                          # platform-tests/*.json — 계약 게이트
node tools/lib/cli.js validate platform-tests/drafts --allow-draft   # 리뷰 대기 초안
```

검증기가 거부하는 것:

- **좌표** — `latitude`/`lng`/`coords` 같은 키, 그리고 자유 텍스트에 숨은 좌표 쌍
- 스키마에 없는 키 (구조적으로 좌표가 끼어들 자리를 없앤다)
- `expected`가 없는 초안 (`--allow-draft` 없이는 실패)
- `expected`를 채웠는데 `_todo`가 남아 있는 파일
- §3에 없는 state, §4에 없는 reason code, §2/§8에 없는 이벤트 타입
- 시간 역행 이벤트 (`convert --repair`가 유일한 예외이고, 무엇을 바꿨는지 출력·기록한다)
- 아무것도 바꾸지 않았다고 주장하는 `_todo.repair` 블록

초안이 `drafts/` 하위에 따로 사는 이유가 이것이다 — 리뷰 대기 중인 파일이
계약 게이트를 깨지 않는다.

## 6. fixture 스키마 (§8)

```jsonc
{
  "name": "vehicle_then_walk_high_confidence",   // 필수, 파일명과 맞춘다
  "initialState": "IDLE",                        // 필수, 계약 §3 state
  "events": [                                    // 필수, 1개 이상, t 오름차순
    {"type":"vehicle_enter","t":0,"confidence":"high"},
    {"type":"location","t":30,"accuracy":8,"speed":9.0,"distanceFromPreviousM":41},
    {"type":"location_quality_degraded","t":395,"fromBucket":"good","toBucket":"poor"},  // good/fair/poor
    {"type":"vehicle_exit","t":240},
    {"type":"walking_enter","t":270}
  ],
  "expected": {                                  // 필수
    "candidate": true,                           //   필수
    "finalState": "CANDIDATE_PENDING",           //   필수, 계약 §3 state
    "confidence": "high",                        //   선택, 계약 §5 bucket
    "requiredReasons": ["recent_vehicle_activity"]  // 선택, 계약 §4 코드만
  }
}
```

이벤트 타입과 각 타입이 가질 수 있는 필드는 `tools/src/contract.ts`와
`tools/src/fixture.ts`의 `EVENT_EXTRA_KEYS`가 단일 출처다. 계약이 바뀌면 거기부터
바꾼다.

### 모션 이벤트는 enter/exit 대칭이다

`vehicle_enter`/`vehicle_exit`, `walking_enter`, `stationary_enter`/`stationary_exit`.
`stationary` 단독 표기는 거부된다. Android는 STILL ENTER/EXIT를 실기기에서 관측하고
(docs/04_ANDROID §2), iOS는 Core Motion `stationary` 플래그가 false로 바뀌는 전이에서
exit를 유도한다.

### location quality 버킷

`good` ≤20m · `fair` ≤35m · `poor` >35m. `location_quality_degraded`의
`fromBucket`/`toBucket`은 이 셋만 받는다.

임계값 35m가 오늘의 `detector.reliableAccuracyMeters`(docs/03 §10)와 같은 것은 우연이다.
**버킷을 그 값에 묶지 마라** — remote config로 튜닝되는 값이라, 묶으면 임계값을 조정하는
순간 과거 trace의 의미가 소급해서 바뀐다. 기록 포맷은 시간이 지나도 비교 가능해야 한다.

음수 accuracy는 버킷이 없다. 계약 §5가 invalid로 규정했고 어댑터가 이미 거른다.
trace까지 왔다면 어댑터 결함이므로 변환기가 거부한다 — `poor`로 삼키지 않는다.

### 이름 규칙

`<상황>_<결과>` 스네이크 케이스. 결과가 이름에 보여야 리뷰가 빨라진다.

- `vehicle_underground_then_walk_candidate`
- `bus_repeated_stops_no_candidate`
- `tunnel_no_parking`

## 7. 필수 10종 체크리스트

`docs/05_PARKING_DETECTION_ENGINE.md` §17이 요구하는 fixture. 양 플랫폼이 모두 돌려야 한다.

| # | 시나리오 | 라벨 | 상태 |
|---|---|---|---|
| 1 | vehicle → underground → walk → candidate | `car` / `parked: true` | ⬜ (`vehicle_then_walk.json`이 지상 버전만 커버) |
| 2 | 긴 신호대기 → 주행 계속 → candidate 없음 | `car` / `parked: false` | ⬜ |
| 3 | 주유소 → 짧은 도보 → 차량 재개 | `car` / `parked: false` | ⬜ |
| 4 | taxi → walk → candidate 가능/알려진 한계 | `taxi` | ⬜ |
| 5 | 버스 반복 정차 → 알림 폭주 없음 | `bus` / `parked: false` | ⬜ |
| 6 | 터널 GPS 소실 → candidate 없음 | `car` / `parked: false` | ✅ `tunnel_no_parking.json` |
| 7 | 프로세스 사망/재시작 → 중복 candidate 없음 | — | ⬜ ⚠️ |
| 8 | 이동 중 권한 회수 | — | ⬜ ⚠️ |
| 9 | 절전 모드 저하 동작 | — | ⬜ ⚠️ |
| 10 | 앱이 기록을 갱신하는 중 위젯 편집 | — | ⬜ ⚠️ |

기존 `vehicle_then_walk.json`은 목록에 없는 positive baseline이다.

⚠️ **#7–#10은 현재 §8 어휘로 표현할 수 없다.** §2 정규화 이벤트에 프로세스 재시작,
권한 회수, 절전 진입, 위젯 편집에 해당하는 이벤트가 없다. 엔진이 들어오는 M3에
`restore(checkpoint)` 의미론과 함께 계약을 확장해야 한다. **이 디렉터리에서 임의로
어휘를 늘리지 마라** — 계약 문서가 먼저다.

## 8. 좌표 금지

trace와 fixture는 **기기 밖으로 복사되는 것이 존재 이유다.** 위도·경도가 들어가는 순간
그 파일은 사용자가 어디에 주차하는지의 기록이 된다 — `docs/00_CORE_RULES.md` Privacy.

- `type` / `t` / `accuracy` / `speed` / `distanceFromPreviousM` / `confidence` / 버킷만 쓴다
- `tools/`는 파일 목록이 아니라 **인코딩된 바이트**를 검사한다. 양 플랫폼의 진단 export
  테스트(`DiagnosticsReportTests.swift`, `DiagnosticsReportTest.kt`)와 같은 방식이다
- 필드 목록 검사는 "보기로 한 필드"만 본다. 바이트 검사는 잊은 필드도 잡는다

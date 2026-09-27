# platform-tests — cross-platform parity fixtures

이 디렉터리의 `*.json`은 iOS `ParkingDetectionEngine`과 Android `ParkingDetectionEngine`이
**같은 입력에 같은 제품 결과**를 내는지 검증하는 계약이다. 스키마는
`docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §8이 정의하고, 이 문서는 그 fixture를
**어떻게 만들고 라벨링하는지**를 다룬다.

> fixture runner는 양 플랫폼 테스트 스위트에 있다. 이 디렉터리의 `*.json`은 iOS와 Android가
> 각각 로드해서 **실제 엔진에 재생**하고 `expected`와 대조한다. 여기에 더해 `tools/`가
> fixture를 **만드는** 변환기와 **검사하는** 검증기를 제공한다.
>
> 경과 시간 행(window)은 **모든 이벤트**에서, 그 이벤트의 시각으로 판정된다(docs/05 §3a
> "When a timeout fires"). 다음 이벤트 없이 경과 시간만으로 상태가 바뀌기를 기대하는
> fixture는 그 시각에 `timer_tick`을 명시해야 한다 — fixture에는 그 외의 시계가 없다.
> `quiet_transition_expires_no_candidate` 가 `transitionWindow` 를 그렇게 고정한다.
>
> 차량 링크에 의존하는 fixture 는 만들지 않는다(§3a: "No fixture may depend on a link event
> being present"). iOS 는 클래식 블루투스 엣지를 관측할 수 없어서, 링크가 있어야만 성립하는
> fixture 는 한쪽이 만들 수 없는 여정을 계약이라고 부르는 셈이 된다. 링크 규칙은 양 플랫폼
> **엔진 단위 테스트**에 각각 고정한다.
>
> 이벤트 이름은 `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §2 표가 정본이고 그것뿐이다.
> 러너가 표에 없는 철자를 받아주면 한쪽에서만 통과하는 fixture 가 생긴다.

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
   - Android: `adb exec-out run-as com.sjstudioz.parkingpin cat ...`
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
| 2 | 긴 신호대기 → 주행 계속 → candidate 없음 | `car` / `parked: false` | ✅ `red_light_no_candidate.json` (짧은 정차), `long_stop_in_traffic.json` (긴 형태: stop-only 후보가 생겼다가 차량 재개로 철회, 최종 `DRIVING`) |
| 3 | 주유소 → 짧은 도보 → 차량 재개 | `car` / `parked: false` | ⬜ |
| 4 | taxi → walk → candidate 가능/알려진 한계 | `taxi` | ⬜ |
| 5 | 버스 반복 정차 → 알림 폭주 없음 | `bus` / `parked: false` | ✅ `bus_repeated_stops_no_storm.json` (+ 양 러너의 travel session당 후보 1개 검사) |
| 6 | 터널 GPS 소실 → candidate 없음 | `car` / `parked: false` | ✅ `tunnel_no_parking.json` |
| 7 | 프로세스 사망/재시작 → 중복 candidate 없음 | — | ⬜ ⚠️ (fixture 없음. `restore` 의미론은 테스트가 고정: docs/05 §3a 창 규칙은 엔진 단위 테스트, §14 "A restored departure keeps its evidence"는 iOS `DepartureTests` ↔ Android `ParkingDetectionRuntimeTest`의 같은 이름·같은 이벤트 twin) |
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

## `subway_commute_underground` — 왜 `candidate: true`인가

**이 fixture의 기대값은 "올바른 결과"가 아니다. 제품이 UX로 흡수하기로 한 알려진
오검출을 고정한 것이다.** 읽는 사람이 이걸 모르면 버그로 오해한다.

출처는 2026-09-16 실제 퇴근 지하철 (iPhone15,3, 세션 2h39m, 91 이벤트).
`_todo.repair`가 기록한 대로 원본 93 이벤트에서 Core Location이 재전달한
인덱스 53·54를 제거해 변환했다.

**§6의 candidate 조건 3개를 전부 만족한다.**

| 조건 | 이 trace |
|---|---|
| 의미 있는 최근 차량 세션 | `vehicle_enter` t=6404 → `vehicle_exit` t=9111, **45분** |
| 차량 세션 종료 | `vehicle_exit` |
| 확인 신호 하나 이상 | `walking_enter`, `vehicle_exit`와 **같은 초** |

§8 가중치로 계산하면 +25 +15 +30 +5 +5 = **80** → `high`. 음성 가중치는 하나도
해당하지 않는다. 즉 엔진은 지하철에서 high confidence 주차 알림을 띄운다.

**정규화 이벤트로는 지하철과 지하주차장을 구분할 수 없다.** §13이 규정한 underground
parking 패턴 — 신뢰 지점 확보 → 정확도 악화 → 차량 종료 → 도보 시작 — 이 trace와
완전히 같다. 이것은 `docs/05_PARKING_DETECTION_ENGINE.md` §2가 이미 선언한 한계다:
공개 API는 차량 이동을 알려주지만 자가용인지는 보장하지 않는다.

`candidate: false`로 쓰면 §2가 불가능하다고 한 구분을 엔진에 요구하게 된다. 그 테스트는
영원히 실패하거나, 존재할 수 없는 신호를 억지로 만들게 한다. §17 #4가 이 케이스를
*"possible candidate / known limitation"* 이라고 쓴 이유다.

**`confidence`는 일부러 비워뒀다.** 위 80은 §8 가중치를 손으로 계산한 값이고, 엔진은
M3에서 필드 데이터로 튜닝된다. 지금 박으면 튜닝을 막는다.

**이 fixture의 쓸모는 M3의 기준선이다.** confidence 튜닝이 지하철 오검출을 실제로
낮추는지, 이 값이 내려가는 것으로 측정한다. 제품 쪽 방어선은 완곡한 문구
("주차한 것 같아요")와 `주차 아님` 액션이며, 그건 엔진이 아니라 UX의 몫이다.

## `manual_save_then_departure` — 출발은 다음 이벤트에서 확정된다 (2026-09-27)

손으로 저장한 주차(`user_saved`) → 다시 탑승 → t=730 한 fix가 §11의 두 기준(90 s, 500 m)을
넘기면서 §7 guard도 이미 충족하는 순간 → 이어지는 주행 → 하차·도보로 다음 주차.
docs/05 §11 "Departure rows are edges": 출발을 연 이벤트는 그것을 확정하지 않는다.
golden이 고정하는 순서는 event 3 `DEPARTURE_CANDIDATE`, event 4 `DRIVING` + `endActiveParking`
이다. 예전 iOS는 event 3 하나에서 둘 다 했고, 그 차이를 잡는 fixture가 이것 전에는 없었다.
주차 종료 시각(= `DEPARTURE_CANDIDATE` 진입 시각)은 golden에 없으므로 양 플랫폼 엔진 단위
테스트("A departure is confirmed on a later event than the one that opened it")가 고정한다.
`expected`의 `confidence: high`와 `requiredReasons`는 양 엔진이 golden에서 같이 내는 값이고,
§8로 다시 계산하면 25 + 15(exit) + 30(walk) + 10(stopped) + 5(300 s·1900 m, 둘 다 §7의 두 배
이상) = 85 → `high`다.

## `manual_save_then_short_departure` — 출발을 확정한 하차는 그 주행의 끝이다 (2026-09-28)

지하주차장으로 들어가는 짧은 이동: 손으로 저장한 주차 → t=600 재탑승 → t=700 한 fix가
§11의 두 기준(100 s, 600 m)을 넘긴다(§7 guard는 아직 미충족) → 경사로에서 fix가 끊기고 →
t=740 `vehicle_exit`, 경과 시간만으로 guard 충족(140 s) → t=760 도보.
docs/05 §11 "An event that confirms a departure is also read in `DRIVING`": 하차는 출발을
확정하고, 같은 이벤트가 `DRIVING`에서 다시 읽혀 그 주행을 끝낸다. golden이 고정하는 순서는
event 3 `DEPARTURE_CANDIDATE`, event 4 `PARKING_TRANSITION` + `endActiveParking`, event 5
후보다. 이 규칙 전에는 하차가 확정에 삼켜져 `DRIVING`에 남았고, 이전 주차만 끝난 채
다음 주차가 사라졌다. `expected`의 `confidence: medium`과 `requiredReasons`는 양 엔진이
golden에서 같이 내는 값이고, §8로 다시 계산하면 25 + 15(exit) + 30(walk) = 70 → `medium`
이다(정지 fix 없음, 140 s·600 m는 §7의 "넉넉히 초과"가 아니다). 링크 disconnect로 확정되는
같은 경우는 §3a가 링크 fixture를 금지하므로 양 플랫폼 엔진 twin 테스트가 고정한다.
t=700과 t=740 사이의 프로세스 사망은 fixture로 표현할 수 없으므로(§8 어휘에 재시작 없음) 같은
결과 — 이전 주차 종료 t=700, 다음 주차의 `medium` 후보 — 를 twin 테스트 "A short departure
restored before its exit still becomes the next parking"이 고정한다(docs/05 §14).
단, 이 fixture와 twin은 명시적 `vehicle_exit`를 쓴다. iPhone 실기기에는 그 edge가 없고
`BackgroundCoordinator`가 도보로부터 하차를 유도한다(`endDrivingSession(.walkingDetected)`).
그 경로의 같은 결과는 iOS 전용 `DepartureTests` "A derived exit that confirms a departure also
ends the drive"와 "A short departure restored before its derived exit still becomes the next
parking"이 고정한다(docs/05 §11 "An adapter-decided end never leaves the parking behind").

## 실주행 field fixture — 승격된 것과 drafts에 남은 것 (2026-09-27)

`field_s*_parked.json`은 사용자가 **주차로 끝났다고 확인한** 실제 iPhone 주행에서 변환했다
(좌표 없음). `expected`는 전부 `{candidate: true, finalState: CANDIDATE_PENDING}`에 더해
양 엔진이 내는 `confidence`와 reason code 전체(`requiredReasons`)를 고정한다. 각 bucket은
docs/05 §8 가중치로 손으로 다시 계산해 맞췄다 (s04 90·s26 85·s32 85 → `high`,
s17 55·s16 45·s33 45·s03 40 → `low`).
세션 s02–s17은 옛 iOS capture(드문드문한 km급 fix), s22+는 현재 dense capture로 기록됐다.

**승격:** `field_s03_parked`, `field_s04_parked`, `field_s16_parked`, `field_s17_parked`,
`field_s26_parked`, `field_s32_parked`, `field_s33_parked`. "양 플랫폼 결과 동일"의 근거는
두 가지 커밋된 테스트다 — 각 fixture의 `expected`(state·candidate·bucket·reason), 그리고
아래 `goldens/`의 이벤트 단위 outcome trace.

**drafts에 남긴 parked 3개** — 양 엔진이 모든 이벤트에서 똑같이(`goldens/`가 고정) 후보를 만들지 못하고,
원인은 엔진이 아니라 기록이다. `expected`를 고쳐서 통과시키지 않는다. 근거는
`docs/05_PARKING_DETECTION_ENGINE.md` "Field drafts that no conformant engine can pass".

| draft | 양쪽 결과 | 이유 | 증거 |
|---|---|---|---|
| `field_s02_parked` | `IDLE` | 옛 sparse capture + §3a 세션 2시간 상한 | 유일한 `vehicle_enter`가 t=1908 → 상한 t=9108. 마지막 주행(t≈8696–9068, 2–12 m/s)은 t=9084에 잠깐 멈췄다가 t=9144–9246에 저속으로 다시 움직이고 멈춘다. 상한이 그 사이에 세션을 닫아 최종 정차는 세션 밖이고, 그 구간에는 자체 `vehicle_enter`가 없다. §3a는 `vehicle_enter`/링크로만 세션을 연다 |
| `field_s06_parked` | `IDLE` | §6 확인 신호 없음 (GPS 열화만) | 지하 진입: 마지막 speed 보유 fix t=2737, accuracy 429→1414 m. 마지막 motion 이벤트는 t=2329 `stationary_exit`. transition이 열린 뒤 walk/stationary/exit/이동 fix 어느 것도 오지 않아 만료된다. GPS 열화만으로는 후보가 될 수 없다(§6) |
| `field_s31_parked` | `PARKING_TRANSITION` | 기록이 300 s transition window 안에서 끝남 — §6 확인 신호 미도착 | 마지막 ≥2 m/s fix t=826 → `movementIdle` 기한 1006, 그 뒤 첫 이벤트(t=1016 fix)에서 기한 시각으로 transition 진입. 기록 종료 t=1039, window 33 s 지점. 826 이후 보고 속도는 0–1.7 m/s이고 speed 없는 fix(885–1039, 정확도 18–51 m)는 17 s마다 15–40 m 움직인다 — 걷는 속도로 주차장을 도는 차로 보인다. 직선 재생은 기록된 구간이 허용하는 **최대** 변위인데도 평균 1.2–2.0 m/s로 §7의 2.0 m/s 미만이라, 재생 artefact가 아니다. 원본 trace 분할 여부(계약 §9 `splitFrom`)는 미검증 — 재변환 전 확인 |

`field_s*_unknown.json` 8개는 결과 라벨이 없어(`expected: null`) 승격하지 않는다. 양
엔진이 같은 결과를 내는지는 `goldens/`가 이벤트 단위로 고정한다.

## `goldens/outcome-traces.golden.json` — 이벤트 단위 parity

계약 §8 "Outcome traces". 양 러너가 `platform-tests/*.json`과 `drafts/*.json` **전부**를
`expected`를 무시하고 재생해서, state를 바꾸거나 candidate를 만들고·거두고·주차를 끝낸
이벤트마다 한 줄씩 이 파일과 비교한다. 초안이 한쪽 플랫폼에서만 조용히 바뀔 수 없다.

- 키 집합이 디스크의 파일과 같아야 한다. fixture나 draft를 추가·삭제·개명하면 이 파일도
  같이 바꾼다
- golden이 바뀌는 diff는 제품 동작의 변경이다. spec이 뒷받침하는 엔진 변경에서만
  재생성한다(Android `UPDATE_PARITY_GOLDEN=1`), 같은 변경에서 양 러너가 통과해야 한다
- **사본은 이 파일 하나뿐이다.** Android는 fixture를 읽는 `fixtureDirectory()` 기준으로 이
  경로를 읽고 쓰며, iOS는 번들된 `platform-tests`에서 읽기만 한다. 러너별 사본
  (`src/test/resources/` 등)을 두면 재생성이 한쪽만 바꿔 양쪽이 초록인 채 엔진이 갈라진다.
  iOS 테스트 "The outcome-trace golden exists exactly once, in platform-tests/goldens/"가
  체크아웃 안(빌드 산출물 제외)의 같은 이름 파일을 전부 찾아 이 하나만 있는지 확인한다
- `.json`이지만 `goldens/` 하위라 fixture 검증기·fixture 목록 어디에도 잡히지 않는다

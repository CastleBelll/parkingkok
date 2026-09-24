# 05. Cross-platform Domain Contract

## 1. Purpose
Swift와 Kotlin 구현이 서로 다른 SDK 이벤트를 받더라도 동일한 제품 의미를 유지하기 위한 규약이다.

## 2. Normalized Events

```text
MotionEnteredVehicle(at, confidence?)
MotionExitedVehicle(at, confidence?)
MotionStartedWalking(at, confidence?)
MotionBecameStationary(at, confidence?)
MotionStoppedBeingStationary(at, confidence?)
LocationSample(at, accuracyM, speedMps?, distanceFromPreviousM?)
LocationQualityDegraded(at, fromBucket, toBucket)
VehicleProjectionConnected(at, platform)
VehicleProjectionDisconnected(at, platform)
TimerTick(at)
UserConfirmedParking(at, floor?)
UserRejectedParking(at)
UserSavedParking(at)
```

Platform mapping:
- iOS automotive -> MotionEntered/ExitedVehicle semantics inferred from Core Motion history/current activity
- Android IN_VEHICLE transition -> direct mapping
- iOS walking -> MotionStartedWalking
- Android WALKING ENTER -> direct mapping
- UserSavedParking: the user saved a parking record themselves, not by answering a
  candidate. It moves every state to `PARKED` (`docs/05_PARKING_DETECTION_ENGINE.md` §11c)
- Android STILL ENTER/EXIT -> MotionBecameStationary / MotionStoppedBeingStationary.
  `docs/04_ANDROID_IMPLEMENTATION.md` §2가 supporting evidence로 요구하고 실기기에서
  실제로 관측된다. iOS는 Core Motion `stationary` 플래그의 전이에서 유도한다

### Wire 어휘
trace(§9)와 fixture(§8)가 쓰는 문자열. 플랫폼 내부 표현과 별개이며, 어댑터 경계에서
매핑한다. **enter/exit 대칭을 유지한다.**

| 정규화 이벤트 | wire |
|---|---|
| MotionEnteredVehicle | `vehicle_enter` |
| MotionExitedVehicle | `vehicle_exit` |
| MotionStartedWalking | `walking_enter` |
| MotionBecameStationary | `stationary_enter` |
| MotionStoppedBeingStationary | `stationary_exit` |
| LocationSample | `location` |
| LocationQualityDegraded | `location_quality_degraded` |
| VehicleProjectionConnected | `projection_connected` |
| VehicleProjectionDisconnected | `projection_disconnected` |
| BluetoothCarConnected | `bluetooth_car_connected` |
| BluetoothCarDisconnected | `bluetooth_car_disconnected` |
| UserConfirmedParking | `user_confirmed` |
| UserRejectedParking | `user_rejected` |
| UserSavedParking | `user_saved` |

### Quality bucket
`LocationQualityDegraded`의 `fromBucket`/`toBucket`:

| bucket | accuracy |
|---|---|
| `good` | ≤ 20m |
| `fair` | ≤ 35m |
| `poor` | > 35m |

**이 경계는 고정 상수다. `detector.reliableAccuracyMeters`를 참조하지 마라.**
그 임계값은 remote config로 튜닝되며(docs/03 §10), 버킷이 거기 묶이면 임계값을
조정하는 순간 **과거에 기록된 trace의 의미가 소급해서 바뀐다.** 기록 포맷은 시간이
지나도 비교 가능해야 한다. 35m가 현재의 신뢰 임계값과 같은 것은 우연이다.

음수 accuracy에는 버킷을 두지 않는다. `docs/05_PARKING_DETECTION_ENGINE.md` §5가
invalid로 규정했고 어댑터가 이미 거른다. trace까지 도달하면 어댑터 결함이므로
버킷으로 삼키지 말고 드러내야 한다.

## 3. States
```text
IDLE
DRIVING_CANDIDATE
DRIVING
PARKING_TRANSITION
CANDIDATE_PENDING
PARKED
DEPARTURE_CANDIDATE
```

이 목록은 `docs/02_PRODUCT_SCOPE_AND_FLOWS.md` §4의 제품 플로우,
`docs/05_PARKING_DETECTION_ENGINE.md` §3과 일치한다.

이전 판은 `PARKING_CANDIDATE` 하나로 뭉쳐 있었는데, 그 둘은 실제로 다른 상태다.
`PARKING_TRANSITION`은 차량 활동이 끝난 뒤 확인 신호를 **기다리는** 구간이고 사용자에게
보이는 것이 없다. `CANDIDATE_PENDING`은 candidate를 저장하고 **알림을 띄운** 뒤
45분 만료를 기다리는 구간이다. 진입 조건, 종료 조건, 사용자 영향이 전부 다르므로
한 상태로 표현하면 알림 시점과 만료 처리를 구분할 수 없다.

## 4. Evidence Reason Codes
Stable strings shared across analytics/tests:
- recent_vehicle_activity
- vehicle_duration_met
- vehicle_distance_met
- vehicle_exit_detected
- walking_after_vehicle
- stationary_after_vehicle
- location_stopped
- location_quality_degraded
- reliable_location_captured
- car_projection_disconnected
- candidate_timeout

Never expose platform SDK enum values as product reason codes.

## 5. Confidence
Engine may calculate integer internal score, but external stable contract is bucket:
- low
- medium
- high

Remote config can tune platform-specific weights, but high/medium product behavior must remain documented.

## 6. Parking Candidate Rule
Candidate requires:
- evidence of recent meaningful vehicle session
AND
- evidence that vehicle session ended/stopped
AND
- at least one confirmation signal (walking/stationary/location stop/projection disconnect combination)

GPS degradation alone cannot satisfy rule.

## 7. Last Reliable Location
A local-only domain value.
Must include:
- coordinates
- capturedAt
- horizontalAccuracy

Selection policy is platform adapter/domain utility, not UI.

## 8. Parity Fixtures
JSON fixture schema example:
```json
{
  "name": "vehicle_then_walk",
  "initialState": "IDLE",
  "events": [
    {"type":"vehicle_enter","t":0},
    {"type":"location","t":30,"accuracy":8,"speed":8},
    {"type":"location","t":150,"accuracy":10,"speed":6},
    {"type":"vehicle_exit","t":240},
    {"type":"walking_enter","t":270}
  ],
  "expected": {
    "candidate": true,
    "confidence":"high",
    "finalState":"CANDIDATE_PENDING"
  }
}
```

Both projects must run equivalent fixture suite in CI.

## 9. Trace Recording

필드 데이터를 수집하는 유일한 경로다. 실주행을 20회 반복하는 대신, **평소 이동을
자동으로 기록**해 fixture로 굳힌다. 버스·지하철·택시 negative 케이스는 차 없이
모을 수 있고, 조수석 탑승도 유효한 세션이다.

### 두 포맷을 분리한다
- **trace**: 기기가 기록하는 원본. 절대 시각, 세션 메타데이터, 사람이 붙인 라벨 포함
- **fixture**: §8의 parity 계약. 상대 시각과 `expected`만. 최소로 유지한다

trace를 fixture로 **변환**한다. 반대는 없다. 기록 메타데이터가 parity 계약을
오염시키면 안 되고, fixture로 먼저 기록하면 정보를 잃는다.

### trace 스키마
```json
{
  "schemaVersion": 1,
  "sessionId": "uuid",
  "platform": "ios" | "android",
  "deviceModel": "iPhone15,3",
  "osVersion": "26.6",
  "appVersion": "0.1.0 (12)",
  "startedAt": 1789530905483,
  "endedAt": 1789531049990,
  "label": {
    "mode": "car" | "bus" | "subway" | "taxi" | "walk" | "still" | "unknown",
    "parked": true | false | null,
    "note": "지하 3층, 진입 후 GPS 소실"
  },
  "events": [
    {"type":"stationary_exit","atMillis":1789530905000,"confidence":"medium"},
    {"type":"vehicle_enter","atMillis":1789530905483,"confidence":"high"},
    {"type":"location","atMillis":1789530935483,"accuracy":8.0,"speed":9.2,"distanceFromPreviousM":41.0},
    {"type":"location_quality_degraded","atMillis":1789531000000,"fromBucket":"good","toBucket":"poor"},
    {"type":"vehicle_exit","atMillis":1789531045483,"confidence":"medium"},
    {"type":"walking_enter","atMillis":1789531049831,"confidence":"high"}
  ]
}
```

### 세션 경계 — 양 플랫폼 동일
**세션은 긴 침묵이 없는 이벤트의 연속이다.** 다음 이벤트가 도착할 때 lazy하게 판정한다.
타이머도, 폴링도, wakeup도 없다 — 경계의 배터리 비용이 0이어야 한다.

| 경계 | 값 |
|---|---|
| idle gap (세션 종료) | **30분** |
| 최대 지속 | **4시간** |
| 최대 이벤트 수 | **1000** |
| 시계 오차 허용 | 5초 |

추가로 **smart detection을 끄면 열린 세션을 즉시 닫는다.**

30분인 이유: 플랫폼 자체의 전달 주기를 넘어서야 의미가 있다. Galaxy S21+ 실측에서
백그라운드 위치 전달이 약 10분에 1회로 스로틀됐고(포그라운드 15.5초), Activity
Transition은 활동이 실제로 바뀔 때만 온다. 지하철 구간은 끝나지 않았는데도 오래 조용할
수 있다. 30분이면 스로틀을 충분히 넘기면서 왕복 두 여정은 분리한다.

지속·이벤트 상한은 롤링 제한이 의미를 갖게 한다. 30분 이상 멈추지 않고 하루 종일
움직이면 세션 하나가 무한히 커지는데, 세션 단위로만 세는 상한은 그 일부를 버릴 수 없다.

#### 비생존 세션은 버린다
rotation 시점에 **이벤트가 1개 이하인 세션은 저장하지 않는다.** 실측에서 세션 9개 중
5개가 단일 이벤트였다 — 앞뒤로 30분간 아무것도 없는 엣지 하나는 fixture가 될 수 없고
엔진에 알려줄 것도 없다.

판정은 **rotation 시점에만** 한다. 열려 있는 세션은 아직 더 붙을 수 있다.
버린 수는 `traceNonViableDropCount`로 노출한다. 조용히 버리지 마라 — 이 값이 크면
경계 설정이 잘못됐다는 신호다. 버려도 watermark는 전진시킨다(이미 본 이벤트다).

#### gap 계측
임계값(30분)은 **관측 1일치로 정한 값이고 재조정이 필요하다.** 실측 분포는
p50 0.3분 / p90 5.7분 / p95 7.6분 / max 28.9분이었고, **30분을 1분 차이로 빗나가
사무실 대기 시간이 퇴근 이동에 뭉쳤다.** 동시에 16.9분 gap이 지하철 주행 한복판에
있어 임계값을 낮추면 실제 이동이 쪼개진다.

근거 있는 조정을 하려면 데이터가 필요하므로, 각 세션에 관측된 gap 요약을 남긴다:

```json
"gapStats": { "maxGapMillis": 1734000, "gapsOver10MinCount": 4, "gapsOver20MinCount": 3 }
```

집계는 진단에도 노출한다. **이 값들이 모이기 전에는 30분을 바꾸지 않는다.**

#### 사람이 세션을 나눈다
기기는 "사무실에 앉아 있음"과 "이동 중"을 구분하지 못한다. 둘 다 motion 엣지를
만들고, 그 간격이 우연히 임계값 근처다. **사람은 구분할 수 있다.**

라벨링 UI에서 닫힌 세션을 두 개로 나눌 수 있게 한다. 각 조각은 자체 `sessionId`와
`startedAt`/`endedAt`을 가진 정상 세션이며, 출처를 기록한다:

```json
"splitFrom": { "parentSessionId": "uuid", "atMillis": 1789561429000 }
```

- 열린 세션은 나눌 수 없다. 닫힌 것만
- 조각도 비생존 규칙을 따른다 — 한쪽이 이벤트 1개면 그 분할은 거부한다
- 원본은 조각으로 대체된다. `splitFrom`이 provenance를 보존한다
- 조각마다 라벨을 따로 붙인다. 그게 나누는 이유다

#### 주행 세션을 경계로 쓰지 않는다
bounded driving session은 드라이브에는 맞고 **나머지 전부에는 틀리다.** 캡처는 차량
증거에만 열리므로, 걷기나 지하철 — `docs/05_PARKING_DETECTION_ENGINE.md` §17이 요구하고
**차 없이 모을 수 있는 바로 그 negative 케이스** — 는 세션이 시작조차 안 되고 기록되지
않는다. 실제로 iOS가 처음 이 방식으로 구현했다가 걷기가 통째로 누락됐다.

smart detection ON/OFF만으로도 안 된다. 사용자가 여정마다 토글하지 않는다. 일주일 켜두면
출퇴근·버스 세 번·산책이 한 세션에 들어가고, 거기서 뽑은 fixture는 아무것도 설명하지 못한다.

### 규칙
- **좌표 금지.** `type`/`atMillis`/`accuracy`/`speed`/`distanceFromPreviousM`/
  `confidence`/버킷만 쓴다. §8 fixture 어휘가 이미 좌표를 갖지 않으므로 구조적으로
  안전하다. 위도·경도 필드를 추가하는 순간 이 파일은 주차 위치 기록이 된다
- **이벤트 타입은 §2의 wire 어휘 표를 그대로 쓴다.** 플랫폼 SDK enum을 노출하지 않는다.
  버킷 값도 §2의 표를 따른다
- **선택 필드는 없으면 키 자체가 빠진다.** 위 예시에 모든 키가 보인다고 필수는 아니다.
  - `speed`: 정지 상태에서 Core Location이 음수를 주고 어댑터가 nil로 정규화하므로
    키가 사라진다(iOS 실기기 확인)
  - `confidence`: Android `ActivityTransitionEvent`는 confidence를 싣지 않으므로
    **Android trace에는 항상 없다**. iOS는 Core Motion에서 얻는다
  - `distanceFromPreviousM`: 세션의 첫 이벤트에는 없다. 직전 세션의 fix와 비교하면
    출퇴근 한 번이 새 세션 안에서 일어난 것처럼 보인다(Android 실기기에서 발견·수정)
- `label`은 기기가 알 수 없다. 사람이 앱에서 붙인다. 없으면 `unknown`
- `label.note`는 사람이 입력하는 자유 텍스트다. **좌표가 숨을 수 있는 유일한 자리이므로
  fixture로 복사하지 않는다** (§8에 자리도 없다)
- **크기 제한.** 롤링 상한을 두고 오래된 세션부터 버린다. 하루 종일 켜둬도
  저장소를 채우지 않아야 한다
- 회수 경로는 진단 파일과 동일하다 — iOS `devicectl copy`, Android `run-as`.
  sudo/root 불필요

### 변환
`trace → platform-tests/<name>.json`:
- `atMillis`를 첫 이벤트 기준 상대 초(`t`)로 환산
- `label`을 근거로 `expected`를 채우되, **사람이 확인하기 전에는 TODO로 남긴다.**
  기록이 곧 정답은 아니다 — 엔진이 무엇을 해야 했는지는 판단이 필요하다
- `initialState`는 세션 시작 시점의 상태
- **세션이 쪼개질 수 있다.** 주유소 정차처럼 중간에 세션이 끊기면 trace가 2개가 된다
  (`docs/05_PARKING_DETECTION_ENGINE.md` §17 fixture 3이 그 케이스다). 재진입 윈도우는
  detection policy이지 recorder의 책임이 아니므로, 합치는 것은 변환 시 사람의 판단이다

### 아직 표현할 수 없는 것 (M3)
`docs/05_PARKING_DETECTION_ENGINE.md` §17의 필수 10종 중 **#7~#10은 현재 §8 어휘로
표현이 불가능하다**: 프로세스 사망/재시작, 권한 회수, 저전력 모드, 위젯 동시 편집.
전부 이벤트 스트림 밖의 사건이다.

어휘를 임의로 늘리지 않는다. M3에서 `restore(checkpoint)` 의미론과 함께 계약을 확장할 때
같이 설계한다. 그때까지 이 4종은 fixture로 만들 수 없다.

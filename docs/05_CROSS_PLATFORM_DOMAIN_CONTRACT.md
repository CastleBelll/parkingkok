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
```

Platform mapping:
- iOS automotive -> MotionEntered/ExitedVehicle semantics inferred from Core Motion history/current activity
- Android IN_VEHICLE transition -> direct mapping
- iOS walking -> MotionStartedWalking
- Android WALKING ENTER -> direct mapping
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
| UserConfirmedParking | `user_confirmed` |
| UserRejectedParking | `user_rejected` |

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
PARKING_CANDIDATE
PARKED
DEPARTURE_CANDIDATE
```

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
    "finalState":"PARKING_CANDIDATE"
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

### 규칙
- **좌표 금지.** `type`/`atMillis`/`accuracy`/`speed`/`distanceFromPreviousM`/
  `confidence`/버킷만 쓴다. §8 fixture 어휘가 이미 좌표를 갖지 않으므로 구조적으로
  안전하다. 위도·경도 필드를 추가하는 순간 이 파일은 주차 위치 기록이 된다
- **이벤트 타입은 §2의 wire 어휘 표를 그대로 쓴다.** 플랫폼 SDK enum을 노출하지 않는다.
  버킷 값도 §2의 표를 따른다
- `label`은 기기가 알 수 없다. 사람이 앱에서 붙인다. 없으면 `unknown`
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

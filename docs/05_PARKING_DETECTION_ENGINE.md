# 05. Parking Detection Engine Specification — Cross-platform

## 1. Why This Is the Product
주차핀의 차별화는 UI가 아니라:
1. parking candidate precision
2. 적절한 candidate timing
3. low battery cost
4. false-positive recovery UX
5. iOS/Android 의미론 parity

엔진은 UI/SDK에서 분리해 deterministic state machine으로 테스트한다.

## 2. Fundamental Limitation
공개 iOS/Android activity APIs는 `차량 이동`을 알려줄 수 있지만 **사용자의 자가용인지**는 보장하지 않는다.
포함 가능:
- taxi
- bus
- rideshare
- friend's car

따라서 trusted vehicle signal이 없는 기본 UX는:
> 주차한 것 같아요.

Android의 Activity Recognition Transition API가 IN_VEHICLE -> WALKING을 직접 제공해도 이 한계는 동일하다.

## 3. Common States
```text
IDLE
DRIVING_CANDIDATE
DRIVING
PARKING_TRANSITION
CANDIDATE_PENDING
PARKED
DEPARTURE_CANDIDATE
```

### IDLE
Low-power monitoring only.
- iOS: low-power location/motion strategy
- Android: activity transitions registered, no permanent high-rate GPS

### DRIVING_CANDIDATE
Vehicle evidence appeared. Validate duration/distance/context.

### DRIVING
Meaningful vehicle session confirmed. Begin bounded location capture.

### PARKING_TRANSITION
Vehicle activity ended/low speed. Wait bounded window for walking/stationary/location stop.

### CANDIDATE_PENDING
Persist candidate + notify.

### PARKED
User-confirmed or policy-confirmed active parking.

### DEPARTURE_CANDIDATE
New meaningful vehicle session while PARKED.

## 4. Platform Signal Mapping

### iOS
- Core Motion automotive/walking/stationary
- Core Location samples/significant changes
- optional CarPlay connection evidence

### Android
- Activity Recognition Transition API: IN_VEHICLE/WALKING/STILL
- Fused Location Provider samples
- optional CarConnection projection evidence

All mapped to events in `05_CROSS_PLATFORM_DOMAIN_CONTRACT.md`.

## 5. Location Sample Validation
Common fields:
- timestamp
- coordinates(local only)
- horizontalAccuracy
- speed optional
- source/platform metadata internal only

Rules:
- negative accuracy invalid
- stale sample excluded from live evidence
- impossible speed/distance outliers rejected
- poor samples must not overwrite lastReliableLocation

### Cached-fix replay — 양 플랫폼 필수 가드
OS는 위치 모니터링을 시작하는 순간 **캐시된 마지막 fix를 즉시 한 번 전달**한다.
이 fix는 임의로 오래됐을 수 있다. iOS 실기기 M0A-1 관측에서 앱 설치(12:12)보다
**3시간 20분 이른 08:51 fix**가 전달됐고, 정확도가 8m로 양호했기 때문에
`negative accuracy` 검사를 그대로 통과해 live evidence로 기록됐다.

정확도만으로는 잡을 수 없다. 캐시된 fix는 대체로 *좋은* fix이고, 단지 현재가 아닐 뿐이다.
따라서 **타임스탬프 기반 freshness 가드를 반드시 둔다.**

- 기본값: 수신 시점 기준 **300초** 초과 시 live evidence에서 제외
- 시계 오차 허용: 미래 방향 5초까지
- §6의 20초 기준은 **bounded driving session 전용**이며 이 경로에 재사용하지 않는다.
  실제 significant change는 앱이 suspend된 탓에 수 분 늦게 도달할 수 있고, 그건
  fix의 결함이 아니다
- 제외한 샘플은 **조용히 버리지 말고 카운트**한다. fresh 샘플이 없는데 제외 카운트만
  올라가면 임계값이 잘못 잡힌 것이다
- 300초는 §8의 가중치와 같은 성격의 **필드 튜닝용 출발점**이지 스펙이 유도한 값이 아니다

Android도 Fused Location 도입 시(M0B-2) 동일 의미론을 구현한다.

## 6. Reliable Location
Initial default:
- horizontalAccuracy <= 35m
- freshness <= 20s during active session

Selection favors newer + accurate sample.
Threshold may differ per platform only through safe-clamped config.

## 7. Driving Confirmation
Initial conceptual guard:
- recent vehicle evidence
AND
- duration >=120s OR distance >=800m
AND
- movement evidence consistent with travel

One event alone never confirms full driving session.

### `movement evidence consistent with travel` — speed가 아니다

**관측 사실 (2026-09-16/17, iPhone15,3, iOS 26).** 실기기 trace 9개를 회수했다.
bounded driving session이 수신한 fix는 **87개**이고, 그중 **speed를 가진 것은 0개**였다.
`liveUpdates(.automotiveNavigation)`는 정상 동작했고 fix는 계속 들어왔다.
같은 기간 체크포인트의 `travelDistanceEstimate`는 3178 m까지 쌓였다.

iOS 구현은 이 조항을 `speed >= threshold`로 읽었기 때문에 movement evidence가
**구조적으로 성립 불가능**했다: speed가 nil이면 카운터가 영원히 0이고
`minimumMovingSamples`를 넘을 수 없다. 거리는 이미 쌓여 있는데 판정에 쓰이지 않았다.

지하·터널·도심 협곡은 GPS 도플러 속도가 나오지 않는 환경이고, 그게 주차핀의 주 무대다
(지하주차장, 아파트 지하). §13의 underground parking 패턴 자체가 이 조건을 전제한다.

**따라서 movement evidence는 두 경로를 가진다.**

1. **speed 우선.** fix가 speed를 가지면 그것만으로 판정한다. 기존 의미론 그대로다.
2. **speed가 nil일 때만 거리 fallback.** anchor fix와 현재 fix의 변위/시간차로
   평균 속도를 유도한다. 세 관문을 모두 통과해야 인정한다.

| 관문 | 기준 | 근거 |
|---|---|---|
| 시간차 하한 | baseline >= 30s | `threshold * T >= 2·sqrt(2)·a`를 good bucket 상한 20m에 풀면 T >= 28.3s. 더 짧으면 정확도 관문이 느린 실주행을 무조건 거부한다 |
| 시간차 상한 | baseline <= 180s | 그 이상의 평균은 "주행–정차–주행"을 평탄화해 travel을 서술하지 못한다. vehicle evidence 만료 horizon과 같고, significant change 간격보다 훨씬 짧다 |
| 정확도 | 변위 >= 2·sqrt(a₁² + a₂²) | `horizontalAccuracy`는 1σ 반경이므로 두 fix **변위**의 1σ는 `sqrt(a₁²+a₂²)`다. 2σ는 "실제로 움직였다"의 약 95% 단측 진술 |
| 거리 | 변위 / baseline >= movingSpeedThreshold | speed 경로와 같은 2 m/s 임계값을 거리로 표현한 것 |

**2σ를 고른 이유는 실데이터다.** 같은 subway trace에 정확도 521 m와 47.9 m인 두 fix가
23초 만에 928 m 떨어져 기록된 구간이 있다. 액면가로는 145 km/h인데, 그 노선 최고속도는
80 km/h다 — 노이즈다. 2σ 관문은 `2·sqrt(521² + 47.9²) ≈ 1043 m`이므로 이를 **거부**한다.
1σ였다면 통과시켜 지하철에서 주행을 확정했을 것이다.

**anchor는 실패 시 유지하고 성공/상한 초과 시에만 교체한다.** 연속 두 fix만 보면 1 Hz에서
변위가 노이즈 바닥을 결코 넘지 못한다(10 m 정확도 fix 사이 50 km/h 주행은 13.9 m,
관문은 28 m). anchor를 붙들면 baseline이 길어져 판정이 가능해진다. 위 928 m 구간도
직전의 깨끗한 24.9 m fix를 anchor로 재면 58초에 928 m, 57 km/h — 그냥 열차다.

**재생 결과 (수정 전 → 후, movingSampleCount).**

| trace | 구간 | bounded fix | speed 있는 fix | 전 | 후 |
|---|---|---:|---:|---:|---:|
| `trace-1789544225798` | 지하철 퇴근 | 58 | 0 | **0** | **3** |
| `trace-1789537784682` | 사무실 도보 (음성 대조군) | 35 | 0 | **0** | **0** |

지하철 구간은 `minimumMovingSamples`(2)를 넘고, 같은 기기·같은 시간대의 도보는 넘지 않는다.

**계측.** `speedAvailableCount` / `speedMissingCount` / `derivedMovingSampleCount` /
`movementEvidenceRejectReason`(`accuracyTooCoarse` | `intervalTooLong` | `distanceTooShort`)를
diagnostics에 내보낸다. "확정이 안 됐다"와 "speed가 한 번도 안 왔다"는 밖에서 보면 같아
보이는데 실제로는 후자였고, 다음 데이터부터는 그 구분이 파일 한 줄로 끝나야 한다.

**이 절의 모든 임계값은 §8 가중치와 같은 성격의 필드 튜닝 출발점이다.** §18 참조 —
특히 **지상 자동차 주행 데이터가 아직 하나도 없다.**

### `distance >= 800m` 누적도 같은 노이즈 바닥을 쓴다

movement evidence를 통일하면서 같은 병이 바로 옆 조항에 남아 있는 것이 드러났다.
**두 구현 다 틀렸고, 방향이 반대였다.**

| | 누적 조건 | 지하에서 |
|---|---|---|
| iOS | valid fix + step 속도 <90 m/s | 정확도 1000m 지터가 그대로 누적 → **과대** |
| Android | 정확도 ≤35m fix만 | 거의 아무것도 안 쌓임 → **과소** |

iOS의 90 m/s 관문은 거친 fix에서 무력하다. 정확도 1000m인 두 fix가 60초 간격으로
900m 떨어지면 15 m/s라 통과하는데, 그건 이동이 아니라 노이즈다.
Android의 ≤35m는 §6의 기준을 또 잘못된 자리에 쓴 것이다 — §6은 *기억할 주차 지점*을
고르는 기준이지 *얼마나 이동했는지* 재는 기준이 아니다.

**통일 규칙: 누적 앵커를 들고, 변위가 쌍의 결합 오차를 넘을 때만 더한다.**

- 더하는 조건: `변위 >= 2·sqrt(a₁² + a₂²)` — movement evidence와 **같은 노이즈 바닥**
- 넘으면 더하고 앵커를 교체한다
- 못 넘으면 더하지 않고 **앵커를 유지한다.** 느린 이동도 앵커가 멀어지면 결국 넘는다
- baseline 상·하한과 속도 관문은 **여기에 적용하지 않는다.** 그 둘은 "travel다운가"를
  묻는 movement evidence의 관문이고, 거리 누적은 "얼마나 갔나"만 묻는다
- §5의 outlier 관문(속도 상한)은 그대로 유지한다. 노이즈 바닥과 다른 것을 막는다

노이즈 바닥 하나를 두 곳에서 같은 의미로 쓴다. 거친 fix를 버리지 않으면서 지터를
합산하지 않는 유일한 방법이고, 지하가 이 제품의 주 무대이므로 거친 fix를 버릴 수 없다.

### 양 플랫폼 통일 (2026-09-18 결정)

두 구현이 같은 조항을 다르게 읽고 있었다. **아래가 단일 정의이고 양쪽이 이것을 구현한다.**

| 항목 | 통일값 | 통일 전 iOS | 통일 전 Android |
|---|---|---|---|
| speed 임계값 | **2.0 m/s** | 2.0 | 8.0 |
| 판정 단위 | **fix 쌍마다 판정, moving 샘플 수를 센다** | 쌍 단위 카운트 | 세션 누적 거리 |
| 노이즈 차단 | **변위 >= 2·sqrt(a₁²+a₂²)** | 동일 | 정확도 ≤35m fix만 누적 |
| 최소 moving 샘플 | **2** | 2 | 2 (reliable 샘플 기준) |
| vehicle evidence 유효기간 | **300s** | 180 | 300 |

**speed 2.0 m/s를 고른 이유.** 8 m/s(29 km/h)는 주차장에서 자리를 찾아 기어가는 차를
거부한다 — 그런데 그게 주차 직전의 바로 그 순간이고 이 앱이 잡아야 하는 장면이다.
정체 구간과 지하철 저속 구간도 같이 탈락한다. "차량인가"는 이 관문의 질문이 아니다.
그건 recent vehicle evidence가 이미 따로 요구한다. 여기서 묻는 것은 "정말 움직였는가"
뿐이므로 기준은 "걷기보다 빠름"이 맞다. 같은 기기·같은 시간대의 도보 대조군이
0 moving 샘플로 남은 것이 과검출이 아님을 보인다.

**누적 거리를 쓰지 않는 이유.** 합은 "150 m를 꾸준히 이동"과 "GPS 지터가 합쳐서 150 m"를
구분하지 못한다. 쌍 단위 판정은 각 구간이 자기 오차를 스스로 넘기를 요구하므로 지터가
누적되지 않는다.

**정확도 ≤35m 관문을 movement evidence에 쓰지 않는 이유 — 이것이 가장 중요하다.**
§6의 35 m는 *기억할 만한 주차 지점*을 고르는 기준이지 *움직였는지* 판단하는 기준이
아니다. 실측 지하 구간의 정확도는 100 m에서 2620 m였다. 그 관문을 movement evidence에
걸면 지하에서 사실상 아무 fix도 자격을 얻지 못하고, **주행 확정이 지하에서 구조적으로
불가능해진다** — iOS가 speed-only로 막혔던 것과 결론이 같고 경로만 다르다.
거친 fix라도 변위가 자기 오차를 명백히 넘으면 그것은 이동의 증거다. 2σ 관문이 그 판단을
한다.

**vehicle evidence 유효기간 300s.** 실측 지하철에서 Activity 전환 간격이 길었고
(`walking_enter` 수신 지연만 8.772초, Core Motion 엣지 간격은 분 단위), 180초는 빠듯하다.
더 관대한 쪽이 놓치는 비용(전체 여정 손실)이 잘못 여는 비용(한 번의 timeout 창)보다 크다.

**검증 상태.** 위 표의 모든 값은 필드 튜닝 출발점이다. 이 결정은 "어느 구현이 실측에서
이겼다"가 아니라 **"어느 쪽이 관측된 실패 모드를 설명하느냐"**에 근거한다.
두 구현 모두 **지상 자동차 주행 데이터로 검증된 적이 없다.** §18 참조.

## 8. Parking Evidence Weights — Starting Point
Positive:
| Evidence | Weight |
|---|---:|
| meaningful recent vehicle session | +25 |
| vehicle exit/end | +15 |
| walking shortly after vehicle | +30 |
| stationary after driving | +10 |
| location movement stopped | +10 |
| GPS quality degraded near end | +5 |
| trusted/projection disconnect | +20 |
| route duration/distance comfortably over minimum | +5 |

Negative:
| Evidence | Weight |
|---|---:|
| vehicle resumes quickly | -40 |
| movement continues | -30 |
| short stop pattern | -25 |
| trip below minimum | -15 |

These are defaults for field tuning, not guaranteed truth.

## 9. Confidence Buckets
- high: >=80
- medium: 60...79
- low: <60

MVP:
- high/medium -> candidate
- low -> no notification

Do not auto-confirm from high score until field precision meets acceptance threshold.

## 10. Candidate Lifetime
Default expiry: 45 minutes.
After expiry:
- do not silently create parking
- clear/supersede on new trip according to product flow

## 11. Departure
While PARKED:
- new sustained vehicle evidence
- movement/distance threshold

Initial:
- vehicle >=90s
- movement >=500m

If uncertain -> suggestion, not destructive silent end.

## 12. Taxi/Bus Mitigation
- short trip guards
- one candidate per travel session
- immediate `주차 아님`
- optional trusted car projection increases confidence
- do not attempt invasive device fingerprinting
- no speculative cloud ML MVP

## 13. Tunnel / Underground
`GPS quality degradation` is supporting evidence only.
Tunnel pattern with continued vehicle movement must remain DRIVING.
Underground parking pattern:
- reliable point captured
- accuracy worsens/disappears
- vehicle ends
- walking begins
=> candidate with last reliable point.

## 14. Persistence Checkpoints
Write checkpoint on:
- driving confirmed
- materially better lastReliableLocation
- parking transition entered
- candidate created
- candidate confirm/reject
- parked/departure transitions

Checkpoint contains no backend upload behavior.

## 15. Engine Effects
Platform-independent conceptual effects:
- startBoundedLocationCapture
- stopLocationCapture
- persistCheckpoint
- createCandidate
- issueCandidateNotification
- markParkingActive
- propose/endParking
- requestOptionalSignalRefresh

SDK calls live in adapters.

## 16. Platform Engine APIs
### iOS conceptual
```swift
actor ParkingDetectionEngine {
    func restore(_ checkpoint: DetectionCheckpoint?)
    func handle(_ event: DetectionEvent) async -> [DetectionEffect]
}
```

### Android conceptual
```kotlin
interface ParkingDetectionEngine {
    suspend fun restore(checkpoint: DetectionCheckpoint?)
    suspend fun handle(event: DetectionEvent): List<DetectionEffect>
}
```
Implementation should serialize mutation with actor-like isolation: single coroutine scope + Mutex/channel/reducer pattern.

## 17. Deterministic Fixtures
Mandatory:
1. vehicle -> underground -> walk -> candidate
2. long red light -> drive continues -> no candidate
3. gas station -> short walk -> vehicle resumes
4. taxi -> walk -> possible candidate/known limitation
5. bus repeated stops -> no notification storm
6. tunnel GPS loss -> no candidate
7. process death/restart -> no duplicate candidate
8. permission revoked mid-trip
9. low power/battery saver degraded behavior
10. widget edit while app updates record

Both platforms run common JSON fixtures.

## 18. Field Tuning
Before public launch target at least:
- 100+ combined real parking sessions
- minimum 40 sessions/platform
- underground + outdoor
- taxi/bus negative cases
- Samsung/Pixel + multiple iPhone generations

### 재검증 대기 항목

**§7 movement evidence 거리 fallback — 지상 주행 데이터로 재검증 필요.**
현재 §7의 fallback 임계값(`2σ` 노이즈 바닥, 30s 하한, 180s 상한)은 2026-09-16/17
iPhone15,3 trace 2개에 대해서만 확인됐고, **둘 다 지하철이다. 지상 자동차 주행 trace는
아직 하나도 없다.** 지상에서는 Core Location이 speed를 정상 보고할 가능성이 높고,
그렇다면 fallback은 거의 실행되지 않는 경로가 된다. 재검증 시 최소한 다음을 확인한다.

- 지상 주행에서 `speedAvailableCount`가 실제로 채워지는가 (그러면 결함은 "지하 한정"이다)
- 지상 주행에서 fallback이 실행될 때 `derivedMovingSampleCount`가 오르는가
- 도심 협곡/터널 진입·진출 경계에서 `movementEvidenceRejectReason` 분포
- 버스/택시 음성 케이스에서 fallback이 과확정하지 않는가 (§12)

이 네 가지가 채워지기 전까지 위 임계값은 **가설 위에 선 출발점**으로 취급한다.

Production analytics uploads only coarse outcomes.

## 19. Battery Gate
Measure baseline vs feature-enabled:
- idle 8h
- mixed 16h day
- 1h continuous drive
- underground arrival

Tools:
- iOS: Instruments/Xcode Energy diagnostics
- Android: Battery Historian/Perfetto/system battery stats where appropriate

Do not invent fixed percentage gate before P0 baseline. Define threshold from reference devices and repeatable test protocol.

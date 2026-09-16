# 05. Parking Detection Engine Specification — Cross-platform

## 1. Why This Is the Product
주차콕의 차별화는 UI가 아니라:
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

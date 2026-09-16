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

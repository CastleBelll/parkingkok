# 05. Cross-platform Domain Contract

## 1. Purpose
Swift와 Kotlin 구현이 서로 다른 SDK 이벤트를 받더라도 동일한 제품 의미를 유지하기 위한 규약이다.

## 2. Normalized Events

```text
MotionEnteredVehicle(at, confidence?)
MotionExitedVehicle(at, confidence?)
MotionStartedWalking(at, confidence?)
MotionBecameStationary(at, confidence?)
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

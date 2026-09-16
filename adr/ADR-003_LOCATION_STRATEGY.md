# ADR-003 — Platform-native Two-stage Detection Strategy

Status: Accepted

## Context
24/7 high-accuracy location is poor for battery/privacy/review posture. iOS and Android provide different low-power motion/location mechanisms.

## Decision
### iOS
IDLE:
- low-power/significant location strategy
- Core Motion history/current evidence

Driving confirmed:
- bounded live location capture

### Android
IDLE:
- Activity Recognition Transition registrations (IN_VEHICLE/WALKING/STILL)
- no permanent high-rate location

Driving candidate/confirmed:
- bounded Fused Location capture
- location foreground service only if P0 proves necessary and policy-compliant

After candidate/parking:
- stop elevated location mode

## Consequence
Detection can be delayed or unavailable under OS restrictions, but battery/privacy/store posture is stronger. Product promises “smart detection”, not guaranteed exact automatic parking.

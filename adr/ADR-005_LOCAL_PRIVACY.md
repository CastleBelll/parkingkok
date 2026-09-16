# ADR-005 — Parking Location and Photos Are Local-only

Status: Accepted

## Decision

Exact coordinates, floor, zone, spot, memo and parking photos never go to Firebase in v1.

## Consequences

Pros:
- lower privacy/security risk
- lower backend cost
- strong product trust story

Cons:
- no cross-device parking-history sync
- reinstall may lose local history unless device backup restores it

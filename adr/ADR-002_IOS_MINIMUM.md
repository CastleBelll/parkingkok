# ADR-002 — Minimum iOS 18

Status: Accepted for v1 planning

## Decision

Deployment target iOS 18.0+.

## Rationale

New 2026 product favors modern Core Location live update APIs and lower compatibility burden. SwiftData and interactive widgets are already available, and iOS 18 provides the intended modern location sample path used by the architecture.

## Revisit

Before launch, check analytics/market support requirements. Lowering to iOS17 requires explicit compatibility design and is not a free config change.

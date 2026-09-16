# ADR-001 — Dual Native iOS + Android

Status: Accepted

## Context
제품 핵심이 background motion/location, widgets, billing, OS wake semantics에 의존한다. Flutter로 UI를 공유해도 핵심 경로는 native bridge/service/extension이 필요하다.

## Decision
v1을 동시 개발한다.
- iOS: SwiftUI + Swift 6
- Android: Kotlin + Jetpack Compose

공유는 backend, domain contract, test vectors, design tokens 수준으로 제한한다.

## Consequences
Pros:
- first-class OS APIs
- background debugging 명확
- store-specific billing/reward 처리 용이
- widget native support

Cons:
- UI/domain implementation 일부 중복
- 두 toolchain CI 필요
- 기능 parity discipline 필요

## Revisit
v1 출시 후 pure-domain duplication cost가 높고 parity fixtures가 안정화되면 KMP shared domain 검토 가능.

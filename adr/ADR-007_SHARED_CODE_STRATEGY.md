# ADR-007 — No Shared Runtime in v1

Status: Accepted

## Decision
Flutter/KMP shared runtime을 v1에 도입하지 않는다.

Shared assets:
- backend
- OpenAPI-like function contracts
- JSON detector fixtures
- event names
- business constants/Remote Config schema
- design tokens

## Reason
주차콕의 위험 영역은 코드 중복이 아니라 OS별 background lifecycle과 권한 semantics다. 이를 한 추상화 계층으로 숨기는 비용이 v1에서 더 크다.

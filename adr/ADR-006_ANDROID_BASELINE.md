# ADR-006 — Android Baseline

Status: Accepted

## Decision
- minSdk 29
- targetSdk 36+
- Kotlin + Compose
- Activity Recognition Transition API + Fused Location
- Room/DataStore
- Glance widgets

## Rationale
API 29부터 ACTIVITY_RECOGNITION runtime permission model과 제품 요구가 자연스럽게 정렬된다. 2026-08-31 이후 Google Play 신규/업데이트는 Android 16(API 36)+ target requirement가 있다.

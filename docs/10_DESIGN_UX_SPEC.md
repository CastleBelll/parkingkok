# 10. Design & UX Specification — iOS + Android

## 1. Brand
Name: 주차콕
Personality:
- calm
- quick
- trustworthy
- everyday utility

Avoid:
- AI-purple SaaS
- heavy automotive dashboard aesthetic
- childish mascot-heavy UI
- excessive gradients/glow

## 2. Shared Semantic Color Tokens
Light:
- background `#F7F9FC`
- surface `#FFFFFF`
- textPrimary `#0F2747`
- textSecondary `#6B7280`
- primary `#2563EB`
- accent `#14B8A6`
- divider `#E5E7EB`
- danger `#EF4444`

Dark:
- background `#0F172A`
- surface `#172033`
- textPrimary `#F8FAFC`
- textSecondary `#94A3B8`
- primary `#3B82F6`
- accent `#2DD4BF`

Implement as semantic assets/theme tokens, never scatter hex values in feature code.

## 3. Platform Adaptation
Design language is shared, controls feel native.

### iOS
- SF/system typography
- SwiftUI navigation/sheets/alerts
- iOS spacing/gestures
- WidgetKit system tint behavior

### Android
- system/Roboto typography through Material 3 baseline
- Compose/Material navigation/dialog semantics
- Android edge-to-edge/insets
- dynamic color is **not** allowed to replace core 주차콕 brand colors automatically; it may be used only if product explicitly approves a dynamic-theme mode later

Do not force iOS-styled switches/navigation onto Android or vice versa.

## 4. Typography
Shared hierarchy semantics:
- current floor hero: visually 56–64sp/pt equivalent, scalable
- screen title
- primary row semibold
- supporting text

Respect:
- iOS Dynamic Type
- Android font scale

Hero value may scale down within safe minimum but must remain readable.

## 5. Shapes
- primary card radius ~22
- secondary row ~16
- button ~16
- Android/iOS actual shape implementation may vary slightly
- avoid excessive shadows; prefer border/surface separation

## 6. Home Hierarchy
1. current floor
2. zone/spot
3. elapsed
4. +/- floor
5. location/photo
6. end parking
7. history preview

No ad between current parking and primary actions.

## 7. Detection Confirmation
Copy must communicate uncertainty:
> 주차한 것 같아요

Never show `주차 완료` before confirmation unless future trusted signal policy explicitly allows auto-confirm.

Primary: 저장/확인
Secondary: 주차 아님

## 8. Permission Education
One permission purpose per step.
Explain value before OS prompt.

Android background location screen should explain that OS may require a separate Settings step.
iOS Always screen should explain background detection directly.

## 9. Subscription
- normal screen, not trap modal
- close visible
- restore/manage visible
- one product MVP, no fake “추천/Best” badge
- price comes from store

## 10. Ads
- banner visually separated
- no fake close
- do not mimic history cards
- no active parking confirmation/detail obstruction

## 11. Widgets
Shared information priority:
- floor
- zone/spot
- elapsed

### iOS
WidgetKit layout/system tint.

### Android
Glance responsive sizes; launcher variations tested.

No mini map in smallest widget.

## 12. Accessibility
### iOS VoiceOver
`현재 주차 위치, 지하 3층`

### Android TalkBack
Equivalent semantic content description; avoid duplicate icon + text announcements.

Touch targets follow each platform minimum guidance.
State is never color-only.

## 13. Loading
Local parking renders immediately.
Subscription/referral/backend state loads progressively.
Do not block home with global spinner.

## 14. Haptics
Use platform-native subtle feedback for:
- save parking
- floor change
- destructive confirmation

No haptic on passive sensor updates.


## 15. Shipping Visual Reference Set
The following generated mockups are part of the design source package and should be used as visual grounding when implementing screens or creating additional mockups.

- `design-references/01-home-main.png`
- `design-references/02-auto-detection-confirmation.png`
- `design-references/03-parking-detail.png`
- `design-references/04-history-list.png`
- `design-references/05-settings.png`

These references define:
- information hierarchy
- card density
- use of blue/mint accents
- calm utility tone
- lightweight illustration usage

Do not copy them rigidly at the expense of platform conventions or accessibility.
See also `docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md`.

import SwiftUI

/// The current parking, as `01-home-main.png` frames it.
///
/// Reading order is docs/10 §6: floor, then zone/spot, then elapsed, then the `−`/`+`
/// keys. The floor is the hero and everything else is deliberately quieter.
///
/// The card is a two-column block, not a single column: the mock puts a map thumbnail in
/// the top-right, and the hero is measured against it. Laid out as one column the right
/// 40% of the card is empty and `B3` reads as floating rather than as the anchor of the
/// screen.
struct ActiveParkingCard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let session: ParkingSession
    let now: Date
    let onStepFloor: (Int) -> Void
    /// Opens the editor — the way to set a floor that was never entered, and the only
    /// way to change one that is free text.
    let onEditFloor: () -> Void

    var body: some View {
        PKCard {
            VStack(alignment: .leading, spacing: PKSpacing.m) {
                HStack(alignment: .top, spacing: PKSpacing.l) {
                    VStack(alignment: .leading, spacing: PKSpacing.m) {
                        statusRow
                        heroBlock
                    }
                    Spacer(minLength: PKSpacing.s)
                    ParkingMapThumbnail(point: ParkingMapPoint(session), zoneText: session.zone)
                }
                floorStepper
            }
            .padding(PKSpacing.xl)
        }
        .accessibilityElement(children: .contain)
    }

    private var statusRow: some View {
        HStack(spacing: PKSpacing.s) {
            Circle()
                .fill(PKColor.accent)
                .frame(width: PKSpacing.s, height: PKSpacing.s)
                // The dot repeats what the words say; on its own it would be the
                // colour-only state docs/01 §8 forbids.
                .accessibilityHidden(true)
            Text("현재 주차 위치")
                .font(PKTypography.caption)
                .foregroundStyle(PKColor.textSecondary)
        }
    }

    private var heroBlock: some View {
        VStack(alignment: .leading, spacing: PKSpacing.xs) {
            if let floor = session.floor {
                PKHeroFloorText(floor.displayText)
                    .accessibilityLabel("현재 주차 위치, \(floor.accessibilityText)")
            } else {
                Button(action: onEditFloor) {
                    PKHeroFloorText("층 입력")
                        .opacity(0.45)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("층 입력, 아직 층을 저장하지 않았습니다")
            }

            if let place = placeText {
                Text(place)
                    .font(PKTypography.heroSupport)
                    .foregroundStyle(PKColor.textPrimary)
            }

            Text(ParkingElapsed.describeActive(from: session.startedAt, to: now))
                .font(PKTypography.supporting)
                .foregroundStyle(PKColor.textSecondary)
        }
    }

    private var placeText: String? { session.placeText }

    /// Two compact keys with a hairline between them, centred under the hero — the mock's
    /// proportions. Full-width slabs made adjusting the floor look like the screen's first
    /// action; docs/10 §6 ranks it fourth.
    @ViewBuilder
    private var floorStepper: some View {
        let canStep = session.floor?.isSteppable ?? false
        VStack(spacing: PKSpacing.s) {
            HStack(spacing: PKSpacing.m) {
                stepButton(delta: -1, symbol: "minus", label: "한 층 아래로", enabled: canStep)
                Rectangle()
                    .fill(PKColor.divider)
                    .frame(width: PKSize.hairline, height: PKSize.minimumTouchTarget * 0.55)
                    .accessibilityHidden(true)
                stepButton(delta: 1, symbol: "plus", label: "한 층 위로", enabled: canStep)
            }
            Text(stepperHint(canStep: canStep))
                .font(PKTypography.caption)
                .foregroundStyle(PKColor.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, PKSpacing.s)
    }

    /// FR-005 allows `+`/`-` only on a numerically parsed floor. Saying so beats a
    /// greyed-out pair of buttons with no explanation.
    private func stepperHint(canStep: Bool) -> String {
        if canStep {
            return "층을 변경할 수 있어요"
        }
        return session.floor == nil ? "층을 입력하면 바로 바꿀 수 있어요" : "숫자 층만 바꿀 수 있어요"
    }

    private func stepButton(delta: Int, symbol: String, label: String, enabled: Bool) -> some View {
        Button {
            // The hero carries `.contentTransition(.numericText())`; this is what supplies
            // the animation it rolls on, and it is nil under Reduce Motion, where the
            // digit changes instantly instead.
            pkWithAnimation(PKMotion.floorChange, reduceMotion: reduceMotion) {
                onStepFloor(delta)
            }
        } label: {
            Image(systemName: symbol)
        }
        .buttonStyle(PKStepperKeyStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}

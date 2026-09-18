import SwiftUI

/// The current parking, as `01-home-main.png` frames it.
///
/// Reading order is docs/10 §6: floor, then zone/spot, then elapsed, then the `-`/`+`
/// keys. The floor is the hero and everything else is deliberately quieter.
struct ActiveParkingCard: View {
    let session: ParkingSession
    let now: Date
    let onStepFloor: (Int) -> Void
    /// Opens the editor — the way to set a floor that was never entered, and the only
    /// way to change one that is free text.
    let onEditFloor: () -> Void

    var body: some View {
        PKCard {
            VStack(alignment: .leading, spacing: PKSpacing.m) {
                statusRow
                heroBlock
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

    /// `A구역 · 142`, or whichever half of it exists.
    private var placeText: String? {
        let parts = [session.zone, session.spot].compactMap(\.self)
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var floorStepper: some View {
        let canStep = session.floor?.isSteppable ?? false
        VStack(spacing: PKSpacing.s) {
            HStack(spacing: PKSpacing.l) {
                stepButton(delta: -1, symbol: "minus", label: "한 층 아래로", enabled: canStep)
                stepButton(delta: 1, symbol: "plus", label: "한 층 위로", enabled: canStep)
            }
            Text(stepperHint(canStep: canStep))
                .font(PKTypography.caption)
                .foregroundStyle(PKColor.textSecondary)
        }
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
            onStepFloor(delta)
        } label: {
            Image(systemName: symbol)
                .font(.title2.weight(.bold))
        }
        .buttonStyle(PKSoftButtonStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}

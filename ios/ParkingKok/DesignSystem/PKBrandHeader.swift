import SwiftUI

/// The header every mock opens with: the mark, the name, and the way into settings.
///
/// The mocks also carry a bell and a handwritten tagline beside the gear. The bell is
/// not here — there is no notification centre in the app, and a button that does nothing
/// is the one thing `docs/19`'s "거짓말하지 마라" spirit rules out. The tagline survives
/// as the footer line, where it does not compete with the gear for the same corner.
struct PKBrandHeader: View {
    private let onOpenSettings: () -> Void

    init(onOpenSettings: @escaping () -> Void) {
        self.onOpenSettings = onOpenSettings
    }

    var body: some View {
        HStack(alignment: .center, spacing: PKSpacing.m) {
            Image(systemName: "car.fill")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Color.white)
                .frame(width: PKSize.iconChip, height: PKSize.iconChip)
                .background(PKColor.primary, in: .circle)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 0) {
                Text("주차콕")
                    .font(PKTypography.screenTitle)
                    .foregroundStyle(PKColor.textPrimary)
                Text("자동 주차 기록")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
            }
            // One announcement for the pair, so VoiceOver does not read the mark and the
            // name separately (docs/10 §12).
            .accessibilityElement(children: .combine)

            Spacer(minLength: PKSpacing.s)

            Button(action: onOpenSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 20, weight: .semibold))
                    .frame(width: PKSize.minimumTouchTarget, height: PKSize.minimumTouchTarget)
            }
            .tint(PKColor.textSecondary)
            .accessibilityLabel("설정")
        }
    }
}

/// The closing line the mocks put at the bottom of every screen.
struct PKBrandFooter: View {
    var body: some View {
        Text("좋은 하루, 좋은 주차")
            .font(PKTypography.caption)
            .foregroundStyle(PKColor.textSecondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .accessibilityHidden(true)
    }
}

/// Screen scaffold: the token background under a scrolling column with the standard
/// gutter. Every screen uses it so the gutter and the background cannot drift apart.
struct PKScreen<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PKSpacing.xl) {
                content
            }
            .padding(.horizontal, PKSpacing.l)
            .padding(.vertical, PKSpacing.l)
        }
        .background(PKColor.background)
        .scrollDismissesKeyboard(.interactively)
    }
}

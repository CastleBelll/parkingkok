import SwiftUI

/// The header every mock opens with: the mark, the name, the two controls, and the
/// handwritten line that is most of the product's personality.
///
/// `01-home-main.png` opens with a **map pin**, not a circle — the app is about where the
/// car is, and a plain disc says nothing. The pin is drawn rather than bundled so it
/// tracks the brand colour token and Dynamic Type instead of being a fixed-size asset.
///
/// The bell is back, and it goes somewhere: `AppRoute.settings(focus: .notifications)`.
/// It was previously left out on the grounds that a button that does nothing is worse
/// than a missing one, which is still true — the fix is a destination, not an omission.
struct PKBrandHeader: View {
    private let onOpenSettings: () -> Void
    private let onOpenNotificationSettings: () -> Void

    init(
        onOpenSettings: @escaping () -> Void,
        onOpenNotificationSettings: @escaping () -> Void
    ) {
        self.onOpenSettings = onOpenSettings
        self.onOpenNotificationSettings = onOpenNotificationSettings
    }

    var body: some View {
        // Centred, and two columns rather than three stacked things. The right side used
        // to carry the mock's handwritten aside under the controls, which made that column
        // two lines taller than the brand beside it — top-aligned, that pushed 주차핀 up
        // into the corner. The aside is gone, so the name and the controls sit level.
        HStack(alignment: .center, spacing: PKSpacing.m) {
            PKBrandMark()
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 0) {
                Text("주차핀")
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

            HStack(spacing: 0) {
                headerButton(
                    systemName: "bell",
                    label: "알림 설정",
                    action: onOpenNotificationSettings
                )
                headerButton(
                    systemName: "gearshape",
                    label: "설정",
                    action: onOpenSettings
                )
            }
        }
    }

    private func headerButton(systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 20, weight: .semibold))
                .frame(width: PKSize.minimumTouchTarget, height: PKSize.minimumTouchTarget)
                .contentShape(.rect)
        }
        .buttonStyle(PKIconButtonStyle())
        .accessibilityLabel(label)
    }
}

/// The brand mark, drawn once as artwork and shown everywhere.
///
/// This used to be a pin composed from a circle and a triangle in code. The app now ships
/// a real icon, and a hand-built approximation of it in the header meant the mark the user
/// tapped on the home screen and the mark at the top of the app were two different
/// drawings. It is the same asset now.
struct PKBrandMark: View {
    /// Tied to `.largeTitle`, which is what the name beside it uses, so the mark grows
    /// with the title instead of shrinking beside it.
    @ScaledMetric(relativeTo: .largeTitle) private var height: CGFloat = 40

    /// The artwork's own proportions, so it is never stretched.
    private static let aspect: CGFloat = 0.847

    var body: some View {
        Image(.brandMark)
            .resizable()
            .scaledToFit()
            .frame(width: height * Self.aspect, height: height)
    }
}


/// `주차는 쉽고 / 일상은 더 가볍게 ☺` — the aside in the mock's top-right corner.
///
/// The mock sets it in a handwriting face. iOS ships no Korean handwriting font, and
/// bundling one is a licence decision this change is not allowed to make, so the nearest
/// honest thing is the rounded system face at a quiet weight. It keeps the warmth without
/// faking a typeface.


/// Screen scaffold: the token background under a scrolling column with the standard
/// gutter. Every screen uses it so the gutter and the background cannot drift apart.
///
/// The background is a flat token fill. It briefly carried the mock's pale blue blooms;
/// the design harness in CLAUDE.md forbids decorative background washes, and the screen
/// reads calmer without something for the eye to notice behind the content.
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
        .background(PKColor.background.ignoresSafeArea())
        .scrollDismissesKeyboard(.interactively)
    }
}

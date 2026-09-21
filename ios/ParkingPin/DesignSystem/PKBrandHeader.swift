import SwiftUI

/// The header every mock opens with: the mark, the name, the two controls, and the
/// handwritten line that is most of the product's personality.
///
/// `01-home-main.png` opens with a **map pin**, not a circle — the app is about where the
/// car is, and a plain disc says nothing. The pin is drawn rather than bundled so it
/// tracks the brand colour token and Dynamic Type instead of being a fixed-size asset.
///
/// The bell opens `AppRoute.notificationHistory` (docs/10 §7b). It used to open the
/// notification *settings*, "which answered a question nobody had — the question people
/// actually have is 'something buzzed while I was driving, what was it?'".
///
/// It carries a dot while a candidate is unanswered, and only then. §7b: "This is the
/// whole reason the screen exists: a notification swiped away in the car is currently
/// lost until it expires, and the dot is how the user finds it again." A dot and never a
/// count — §12 allows at most one candidate, so there is no number to show.
struct PKBrandHeader: View {
    private let hasUnreadNotification: Bool
    private let onOpenSettings: () -> Void
    private let onOpenNotifications: () -> Void

    init(
        hasUnreadNotification: Bool = false,
        onOpenSettings: @escaping () -> Void,
        onOpenNotifications: @escaping () -> Void
    ) {
        self.hasUnreadNotification = hasUnreadNotification
        self.onOpenSettings = onOpenSettings
        self.onOpenNotifications = onOpenNotifications
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
                    // The state is spoken, not left to the dot: docs/01 §8 and docs/10
                    // §12 forbid a state that exists only as a colour.
                    label: hasUnreadNotification ? "알림, 확인하지 않은 알림 있음" : "알림",
                    showsUnreadDot: hasUnreadNotification,
                    action: onOpenNotifications
                )
                headerButton(
                    systemName: "gearshape",
                    label: "설정",
                    action: onOpenSettings
                )
            }
        }
    }

    /// Mint, not blue and not red. The design harness gives Primary Blue to actions and
    /// keeps the accent "for state" — an unanswered candidate is state. Red belongs to
    /// `danger`, and a guess the user has not looked at yet is not a failure.
    private var unreadDot: some View {
        Circle()
            .fill(PKColor.accent)
            .frame(width: Self.unreadDotSize, height: Self.unreadDotSize)
            // Sits on the bell's shoulder without moving it, so the two header controls
            // stay the same size whether or not there is anything to answer.
            .offset(x: Self.unreadDotSize, y: -Self.unreadDotSize)
            .accessibilityHidden(true)
    }

    private func headerButton(
        systemName: String,
        label: String,
        showsUnreadDot: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 20, weight: .semibold))
                .overlay(alignment: .topTrailing) {
                    if showsUnreadDot {
                        unreadDot
                    }
                }
                .frame(width: PKSize.minimumTouchTarget, height: PKSize.minimumTouchTarget)
                .contentShape(.rect)
        }
        .buttonStyle(PKIconButtonStyle())
        .accessibilityLabel(label)
    }

    /// Small enough to read as a mark rather than a badge. §7b calls it "a small dot".
    private static let unreadDotSize: CGFloat = 8
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
    private static let aspect: CGFloat = 0.827

    var body: some View {
        Image(.brandMark)
            .resizable()
            .scaledToFit()
            .frame(width: height * Self.aspect, height: height)
    }
}

// `주차는 쉽고 / 일상은 더 가볍게 ☺` — the aside in the mock's top-right corner.
//
// The mock sets it in a handwriting face. iOS ships no Korean handwriting font, and
// bundling one is a licence decision this change is not allowed to make, so the nearest
// honest thing is the rounded system face at a quiet weight. It keeps the warmth without
// faking a typeface.

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

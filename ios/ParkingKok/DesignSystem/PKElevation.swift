import SwiftUI

/// How far a surface sits off the background.
///
/// docs/10 §5 says "avoid excessive shadows; prefer border/surface separation" — which is
/// a ban on excess, not on depth, and `01-home-main.png` itself lifts every card off the
/// background with a soft shadow. Without it the screen reads as a wireframe: white
/// rectangles with hairlines on a flat wash, all at the same distance from the eye.
///
/// The hairline border stays. It is what does the separating where a shadow cannot be
/// trusted — at 3× on a bright screen, and in dark mode, where a light-mode drop shadow
/// reads as dirt rather than depth.
///
/// No new colour token: light-mode shadows are the existing `textPrimary` navy at single
/// digit opacity, which is why they read blue-grey like the mock's rather than grey.
/// Dark mode is the one place that cannot use a palette colour — `textPrimary` is nearly
/// white there and would glow — so it uses plain black, which is a shadow, not a hue.
enum PKElevation {
    /// Cards and rows. The default distance in the mock.
    case resting
    /// The primary call to action, which the mock lifts further and tints with its own
    /// blue so the button looks like the thing you press.
    case raised
}

private struct PKShadow: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let level: PKElevation

    func body(content: Content) -> some View {
        switch level {
        case .resting:
            content
                // Two shadows, not one: the wide soft pass is the lift, the tight pass is
                // the contact edge. A single blur gives a card that floats without ever
                // looking like it is touching anything.
                .shadow(color: ambient, radius: isDark ? 10 : 14, x: 0, y: isDark ? 5 : 6)
                .shadow(color: contact, radius: 2, x: 0, y: 1)
        case .raised:
            content
                .shadow(color: PKColor.primary.opacity(isDark ? 0.34 : 0.26), radius: 14, x: 0, y: 8)
        }
    }

    private var isDark: Bool {
        colorScheme == .dark
    }

    private var ambient: Color {
        isDark ? Color.black.opacity(0.38) : PKColor.textPrimary.opacity(0.07)
    }

    private var contact: Color {
        isDark ? Color.black.opacity(0.24) : PKColor.textPrimary.opacity(0.05)
    }
}

extension View {
    /// Lift this surface off the background. Apply to the shape, not to its contents.
    func pkElevation(_ level: PKElevation) -> some View {
        modifier(PKShadow(level: level))
    }
}

/// The pale blue wash the mock lays behind the cards.
///
/// `01-home-main.png` does not sit on a flat `#F7F9FC`; there are soft blue blooms behind
/// the content that give the screen somewhere to be. Two very large, very blurred circles
/// of the existing brand blue is the whole trick — no gradient asset, no new colour, and
/// nothing that moves.
///
/// Fixed to the screen rather than to the scroll content: a decoration that scrolls turns
/// into something the eye tracks, and this is meant to be barely noticed.
struct PKBackdrop: View {
    @Environment(\.colorScheme) private var colorScheme

    /// Low enough that the cards, not the wash, are what the eye lands on. Dark mode
    /// needs a touch more to survive against `#0F172A`.
    private var tint: Color {
        PKColor.primary.opacity(colorScheme == .dark ? 0.12 : 0.07)
    }

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let height = proxy.size.height
            ZStack(alignment: .topLeading) {
                bloom(diameter: width * 1.0)
                    .offset(x: width * 0.55, y: -height * 0.06)
                bloom(diameter: width * 1.25)
                    .offset(x: -width * 0.7, y: height * 0.42)
                bloom(diameter: width * 0.8)
                    .offset(x: width * 0.6, y: height * 0.72)
            }
            .frame(width: width, height: height, alignment: .topLeading)
        }
        .blur(radius: 40)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func bloom(diameter: CGFloat) -> some View {
        Circle()
            .fill(tint)
            .frame(width: diameter, height: diameter)
    }
}

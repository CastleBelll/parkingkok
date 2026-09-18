import SwiftUI

/// The app's colour vocabulary, and the only place a colour is named.
///
/// docs/10_DESIGN_UX_SPEC.md §2: "Implement as semantic assets/theme tokens, never
/// scatter hex values in feature code." The hex values live in
/// `Resources/Assets.xcassets/Colors/*.colorset` so the system resolves light and dark
/// without the app observing the colour scheme; this enum is the typed door to them.
///
/// `AccentColor` is a copy of `Primary`, not a separate decision. It is what SwiftUI
/// hands to a bare `Button`, a `Toggle` and every system control, so leaving it at a
/// value nobody chose is how a perfectly live button ends up reading as disabled.
enum PKColor {
    /// Screen background behind the cards.
    static let background = Color(.pkBackground)
    /// Card and row fill.
    static let surface = Color(.pkSurface)
    static let textPrimary = Color(.pkTextPrimary)
    static let textSecondary = Color(.pkTextSecondary)
    /// Brand blue. Primary actions, hero value, active state.
    static let primary = Color(.pkPrimary)
    /// Mint. Supporting affordances only — never the sole carrier of meaning (§12).
    static let accent = Color(.pkAccent)
    static let divider = Color(.pkDivider)
    static let danger = Color(.pkDanger)

    /// Tinted fills behind icons and secondary buttons, as in `01-home-main.png`.
    ///
    /// Derived rather than declared: §2 defines no such token, and inventing two more
    /// hex pairs would put a colour decision outside the spec. An opacity over the
    /// surface tracks both appearances for free.
    static let primarySoft = primary.opacity(0.12)
    static let accentSoft = accent.opacity(0.16)
}

/// Asset names, resolved once. A typo becomes a build error instead of a pink square.
private extension ColorResource {
    static let pkBackground = ColorResource(name: "Colors/Background", bundle: .main)
    static let pkSurface = ColorResource(name: "Colors/Surface", bundle: .main)
    static let pkTextPrimary = ColorResource(name: "Colors/TextPrimary", bundle: .main)
    static let pkTextSecondary = ColorResource(name: "Colors/TextSecondary", bundle: .main)
    static let pkPrimary = ColorResource(name: "Colors/Primary", bundle: .main)
    static let pkAccent = ColorResource(name: "Colors/Accent", bundle: .main)
    static let pkDivider = ColorResource(name: "Colors/Divider", bundle: .main)
    static let pkDanger = ColorResource(name: "Colors/Danger", bundle: .main)
}

import SwiftUI

/// Type ramp for the app (docs/10_DESIGN_UX_SPEC.md §4).
///
/// Everything below a hero resolves to a built-in Dynamic Type style, so the whole app
/// scales with the user's setting without a single hard-coded point size. The hero is
/// the one exception and is handled by `PKHeroFloorText`, which scales it explicitly.
enum PKTypography {
    /// Screen title — `주차 기록`, `설정`.
    static let screenTitle = Font.largeTitle.weight(.bold)
    /// Card heading — `최근 주차`.
    static let sectionTitle = Font.title3.weight(.bold)
    /// The line under the hero — `A구역 · 142`.
    static let heroSupport = Font.title2.weight(.bold)
    /// Primary row label (docs/10 §4 "primary row semibold").
    static let row = Font.body.weight(.semibold)
    /// Supporting text under a row.
    static let supporting = Font.subheadline
    /// Badge and footnote.
    static let caption = Font.footnote.weight(.medium)
}

/// The hero floor value: the largest thing on the home screen (docs/10 §6 rank 1).
///
/// §4 asks for a 56–64pt equivalent that still scales. A plain `.system(size:)` ignores
/// Dynamic Type entirely, so the size is driven by `@ScaledMetric` against `.largeTitle`
/// and then allowed to shrink within one line rather than wrap `B3` onto two.
struct PKHeroFloorText: View {
    /// §4's band, taken at its top: this value is the whole point of the screen.
    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 64

    private let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .heavy, design: .rounded))
            .foregroundStyle(PKColor.textPrimary)
            .lineLimit(1)
            // "Hero value may scale down within safe minimum but must remain readable"
            // (§4): 0.5 of a 64pt hero is still 32pt.
            .minimumScaleFactor(0.5)
            // `B3` → `B4` under the stepper rolls the digit instead of swapping the whole
            // word. The caller supplies the animation (`PKMotion.floorChange`); with none
            // — which is what Reduce Motion produces — this is an ordinary instant change.
            .contentTransition(.numericText())
    }
}

import SwiftUI

/// Surface container used by every card in the mocks.
///
/// docs/10_DESIGN_UX_SPEC.md §5: "avoid excessive shadows; prefer border/surface
/// separation". Both are here and each does a different job — the hairline separates,
/// the shadow gives the card somewhere to be. `PKElevation` carries the reasoning.
///
/// The shadow hangs off the background *shape*, never off the card's contents: applying
/// it to the composed view would put a drop shadow behind every glyph and every line of
/// text inside.
struct PKCard<Content: View>: View {
    private let radius: CGFloat
    private let content: Content

    init(radius: CGFloat = PKRadius.card, @ViewBuilder content: () -> Content) {
        self.radius = radius
        self.content = content()
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .background { PKSurfaceShape(radius: radius) }
    }
}

/// The filled, bordered, lifted rounded rectangle under every card and card-shaped row.
///
/// One type so a card and a tappable row cannot end up a point of radius or a percent of
/// shadow apart.
struct PKSurfaceShape: View {
    let radius: CGFloat

    var body: some View {
        RoundedRectangle(cornerRadius: radius)
            .fill(PKColor.surface)
            .pkElevation(.resting)
            .overlay {
                RoundedRectangle(cornerRadius: radius)
                    .strokeBorder(PKColor.divider, lineWidth: PKSize.hairline)
            }
    }
}

/// The circular tinted glyph that opens a row in `01-home-main.png` / `04-history-list.png`.
struct PKIconChip: View {
    enum Tint {
        case primary
        case accent
        /// A record with nothing special about it — the plain rows in the history mock.
        case neutral
    }

    private let systemName: String
    private let tint: Tint

    init(_ systemName: String, tint: Tint = .primary) {
        self.systemName = systemName
        self.tint = tint
    }

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(foreground)
            .frame(width: PKSize.iconChip, height: PKSize.iconChip)
            .background(background, in: .circle)
            // Decorative: the row's own text already says what this is, and §12 forbids
            // VoiceOver announcing the icon and the label twice.
            .accessibilityHidden(true)
    }

    private var foreground: Color {
        switch tint {
        case .primary, .neutral: PKColor.primary
        case .accent: PKColor.accent
        }
    }

    private var background: Color {
        switch tint {
        case .primary: PKColor.primarySoft
        case .accent: PKColor.accentSoft
        case .neutral: PKColor.primary.opacity(0.08)
        }
    }
}

/// Small text pill — `자동` on a detected record, `허용됨` on a permission row.
///
/// Always text. docs/01 §8 and docs/10 §12: state is never colour-only, so the colour
/// here repeats what the word already said.
struct PKBadge: View {
    enum Tone {
        case info
        case positive
        case muted
    }

    private let text: String
    private let tone: Tone

    init(_ text: String, tone: Tone = .info) {
        self.text = text
        self.tone = tone
    }

    var body: some View {
        Text(text)
            .font(PKTypography.caption)
            .foregroundStyle(foreground)
            .padding(.horizontal, PKSpacing.s)
            .padding(.vertical, PKSpacing.xs)
            .background(background, in: .rect(cornerRadius: PKRadius.chip))
    }

    private var foreground: Color {
        switch tone {
        case .info: PKColor.primary
        case .positive: PKColor.accent
        case .muted: PKColor.textSecondary
        }
    }

    private var background: Color {
        switch tone {
        case .info: PKColor.primarySoft
        case .positive: PKColor.accentSoft
        case .muted: PKColor.divider.opacity(0.5)
        }
    }
}

/// The filled call to action — `주차 종료` in `01-home-main.png`, `저장` in the sheet.
struct PKPrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PKTypography.row)
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
            .padding(.vertical, PKSpacing.l)
            .padding(.horizontal, PKSpacing.l)
            .background {
                RoundedRectangle(cornerRadius: PKRadius.button)
                    .fill(PKColor.primary)
                    .pkElevation(.raised)
            }
            .pkPressFeedback(configuration.isPressed)
    }
}

/// The two-line call to action the mocks use for the consequential one: what it does on
/// top, what it will do to your data underneath (`01-home-main.png`, `03-parking-detail`).
///
/// The mock gives the pair room — the label is not jammed against the subtitle and the
/// subtitle is not jammed against the edge. That spacing is the difference between a
/// button and a slab, so it is `PKSpacing.s` between the lines on top of the style's own
/// vertical padding, not the 4pt it used to be.
struct PKPrimaryActionButton: View {
    private let title: String
    private let subtitle: String
    private let systemImage: String
    private let action: () -> Void

    init(title: String, subtitle: String, systemImage: String, action: @escaping () -> Void) {
        self.title = title
        self.subtitle = subtitle
        self.systemImage = systemImage
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            VStack(spacing: PKSpacing.s) {
                Label(title, systemImage: systemImage)
                    .font(PKTypography.sectionTitle)
                Text(subtitle)
                    .font(PKTypography.caption)
                    .opacity(0.9)
            }
        }
        .buttonStyle(PKPrimaryButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
        .accessibilityHint(subtitle)
    }
}

/// The quieter sibling — `길찾기` and `사진 보기`, side by side under the facts.
struct PKSoftButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PKTypography.row)
            .foregroundStyle(PKColor.primary)
            .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
            .padding(.vertical, PKSpacing.m)
            .background(PKColor.primarySoft, in: .rect(cornerRadius: PKRadius.button))
            .pkPressFeedback(configuration.isPressed)
    }
}

/// A bare glyph control — the bell and the gear in the header.
struct PKIconButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(PKColor.textSecondary)
            .pkPressFeedback(configuration.isPressed)
    }
}

/// A whole card that is also a button — the action rows on home, every history row.
///
/// The surface, the border, the lift and the press all come from here, so a tappable card
/// cannot end up looking different from a card that merely sits there. Screens supply the
/// contents and their padding, nothing else.
struct PKSurfaceButtonStyle: ButtonStyle {
    var radius: CGFloat = PKRadius.card

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, alignment: .leading)
            .background { PKSurfaceShape(radius: radius) }
            .pkPressFeedback(configuration.isPressed)
    }
}

/// A filter chip (`04-history-list.png`'s 전체 / 자동 감지 / 직접 저장).
///
/// Selection is a fill swap in the mock. It lives in the design system rather than in the
/// history screen so the chip presses like every other control in the app — one press
/// definition, reused, not one per screen.
struct PKChipButtonStyle: ButtonStyle {
    let isSelected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PKTypography.caption)
            .foregroundStyle(isSelected ? Color.white : PKColor.textSecondary)
            .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
            .background {
                RoundedRectangle(cornerRadius: PKRadius.button)
                    .fill(isSelected ? PKColor.primary : PKColor.surface)
                    .pkElevation(.resting)
                    .overlay {
                        RoundedRectangle(cornerRadius: PKRadius.button)
                            .strokeBorder(PKColor.divider, lineWidth: isSelected ? 0 : PKSize.hairline)
                    }
            }
            .pkPressFeedback(configuration.isPressed)
    }
}

/// One key of the floor stepper (`−` / `+`).
///
/// `01-home-main.png` sizes these to their contents — two compact rounded squares with a
/// hairline between them, together about half the card's width. Full-width slabs would
/// make nudging the floor look like the screen's primary action, which it is not: docs/10
/// §6 ranks it fourth, under the floor, the zone and the elapsed time.
struct PKStepperKeyStyle: ButtonStyle {
    /// Wide enough for a comfortable thumb, narrow enough to stay a key rather than a bar.
    static let width: CGFloat = 76

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.title2.weight(.bold))
            .foregroundStyle(PKColor.primary)
            .frame(width: Self.width, height: PKSize.minimumTouchTarget + PKSpacing.s)
            .background(PKColor.primarySoft, in: .rect(cornerRadius: PKRadius.button))
            .pkPressFeedback(configuration.isPressed)
    }
}

/// A full-width advisory — a storage fallback, a store error, a photo that would not save.
///
/// Shared rather than per-screen: the home screen and the detail screen both surface
/// `ParkingModel.failure`, and two copies of the same card would drift.
struct PKNoticeCard: View {
    private let text: String

    init(text: String) {
        self.text = text
    }

    var body: some View {
        PKCard(radius: PKRadius.row) {
            HStack(alignment: .top, spacing: PKSpacing.m) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(PKColor.danger)
                    .accessibilityHidden(true)
                Text(text)
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textPrimary)
            }
            .padding(PKSpacing.l)
        }
        .accessibilityElement(children: .combine)
    }
}

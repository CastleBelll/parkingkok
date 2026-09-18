import SwiftUI

/// Surface container used by every card in the mocks.
///
/// docs/10_DESIGN_UX_SPEC.md §5: "avoid excessive shadows; prefer border/surface
/// separation". A hairline border does the separating; the shadow is faint enough to
/// survive dark mode, where a light-mode drop shadow reads as dirt.
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
            .background(PKColor.surface, in: .rect(cornerRadius: radius))
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
        case .neutral: PKColor.divider.opacity(0.5)
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
            .padding(.vertical, PKSpacing.m)
            .background(PKColor.primary, in: .rect(cornerRadius: PKRadius.button))
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

/// The two-line call to action the mocks use for the consequential one: what it does on
/// top, what it will do to your data underneath (`01-home-main.png`, `03-parking-detail`).
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
            VStack(spacing: PKSpacing.xs) {
                Label(title, systemImage: systemImage)
                    .font(PKTypography.row)
                Text(subtitle)
                    .font(PKTypography.caption)
                    .opacity(0.85)
            }
        }
        .buttonStyle(PKPrimaryButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
        .accessibilityHint(subtitle)
    }
}

/// The quieter sibling — `길찾기`, `사진 보기`, and the `-`/`+` floor keys.
struct PKSoftButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PKTypography.row)
            .foregroundStyle(PKColor.primary)
            .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
            .padding(.vertical, PKSpacing.m)
            .background(PKColor.primarySoft, in: .rect(cornerRadius: PKRadius.button))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

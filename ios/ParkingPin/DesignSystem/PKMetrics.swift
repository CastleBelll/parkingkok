import CoreGraphics

/// Spacing scale. One ladder, so density stays even across screens (docs/19 "여백 밀도").
enum PKSpacing {
    /// 4 — icon-to-label, badge padding.
    static let xs: CGFloat = 4
    /// 8 — inside a row.
    static let s: CGFloat = 8
    /// 12 — between rows of the same group.
    static let m: CGFloat = 12
    /// 16 — card padding, screen gutter.
    static let l: CGFloat = 16
    /// 20 — between cards.
    static let xl: CGFloat = 20
    /// 28 — between sections.
    static let xxl: CGFloat = 28
}

/// Corner radii, from docs/10_DESIGN_UX_SPEC.md §5.
enum PKRadius {
    /// Primary card.
    static let card: CGFloat = 22
    /// Secondary row.
    static let row: CGFloat = 16
    static let button: CGFloat = 16
    /// Icon chip inside a row — not in §5; the smallest shape the mocks use.
    static let chip: CGFloat = 12
}

/// Hit-target and line sizes that more than one screen needs to agree on.
enum PKSize {
    /// docs/01 §8 / docs/10 §12: minimum touch target. Apple's HIG floor is 44pt.
    static let minimumTouchTarget: CGFloat = 44
    /// The circular icon chip on rows and cards.
    static let iconChip: CGFloat = 40
    static let hairline: CGFloat = 1
    /// A screen's one primary button. Fixed, not `minHeight` plus padding: the padding sat on
    /// top of the 44 pt floor and drew 68 pt slabs (device review 2026-10-01).
    static let primaryButtonHeight: CGFloat = 54
    /// Secondary and compact buttons — the mocks' 길찾기 / 아직 주차 중 height.
    static let secondaryButtonHeight: CGFloat = 46
}

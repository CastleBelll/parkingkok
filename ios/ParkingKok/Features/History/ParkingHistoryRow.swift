import SwiftUI

/// One record in a list — home's preview and the full history share it
/// (`01-home-main.png` and `04-history-list.png` show the same row at two densities).
struct ParkingHistoryRow: View {
    enum Style {
        /// Home's three-row preview: floor and date only.
        case compact
        /// The history screen: zone/spot and the detection badge as well.
        case full
    }

    let session: ParkingSession
    let style: Style

    var body: some View {
        HStack(spacing: PKSpacing.m) {
            // One badge for every record, as `04-history-list.png` draws it. It used to be
            // a car for detected rows and a `P` for manual ones, which made the list look
            // like two kinds of thing — and made the source a colour-and-glyph-only state,
            // which docs/01 §8 forbids. `자동` says it in a word instead.
            PKIconChip("car.fill", tint: .neutral)

            VStack(alignment: .leading, spacing: PKSpacing.xs) {
                Text(title)
                    .font(PKTypography.row)
                    .foregroundStyle(PKColor.textPrimary)
                if style == .full, session.source == .detected {
                    // docs/19: "자동 감지 기록은 badge로 구분". A word, not a hue.
                    PKBadge("자동")
                }
            }

            Spacer(minLength: PKSpacing.s)

            // Home carries the day in the title, so only the clock is left here and the
            // row collapses to a single line, the way `01-home-main.png` draws it. The
            // full list keeps both stacked: its title is already spent on zone and spot.
            VStack(alignment: .trailing, spacing: 2) {
                if style == .full {
                    Text(ParkingDateText.day(session.startedAt))
                }
                Text(ParkingDateText.time(session.startedAt))
            }
            .font(PKTypography.supporting)
            .foregroundStyle(PKColor.textSecondary)

            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(PKColor.textSecondary)
                .accessibilityHidden(true)
        }
        .padding(PKSpacing.l)
        .frame(minHeight: PKSize.minimumTouchTarget)
        // The surface, border and lift belong to `PKSurfaceButtonStyle`, which every call
        // site wraps this in — a row is a whole card that is also a button.
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isButton)
    }

    /// `B3 · A구역 142` in the full list; `B2 · 어제` on home.
    private var title: String {
        let floor = session.floor?.displayText ?? "층 미입력"
        guard style == .full else {
            return "\(floor) · \(ParkingDateText.day(session.startedAt))"
        }
        let place = [session.zone, session.spot].compactMap(\.self).joined(separator: " ")
        return place.isEmpty ? floor : "\(floor) · \(place)"
    }

    private var accessibilityText: String {
        var parts = [session.floor?.accessibilityText ?? "층 미입력"]
        if style == .full {
            if let zone = session.zone {
                parts.append(zone)
            }
            if let spot = session.spot {
                parts.append(spot)
            }
            parts.append(session.source == .detected ? "자동 감지" : "직접 저장")
        }
        parts.append("\(ParkingDateText.day(session.startedAt)) \(ParkingDateText.time(session.startedAt))")
        return parts.joined(separator: ", ")
    }
}

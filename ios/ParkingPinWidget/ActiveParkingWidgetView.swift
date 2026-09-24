import SwiftUI
import WidgetKit

/// The hero card of `01-home-main.png`, "with the map thumbnail and the primary action
/// removed" (docs/06 §7a).
///
/// The tile answers one question — *where is my car* — so the floor is the whole design
/// and everything else is subordinate to it. That rules out the chrome the card can
/// afford: no status dot, no brand mark, no background decoration, and one accent colour
/// (Primary Blue) on the whole face. §7a's per-size contents are unchanged: small is the
/// floor and the elapsed time, medium adds `zone · spot` and, when entitled, the stepper.
///
/// No shadow anywhere. Layers here are separated by surface and spacing — the widget's own
/// `containerBackground` against the stepper key's tinted fill — never by a drop shadow.
///
/// It renders no coordinate, no address and no photo, because docs/09 forbids it and
/// because `ActiveParkingSnapshot` never carried one.
struct ActiveParkingWidgetView: View {
    @Environment(\.widgetFamily) private var family

    let entry: ActiveParkingEntry

    var body: some View {
        Group {
            if family.isAccessory {
                lockScreen
            } else if let snapshot = entry.snapshot {
                activeParking(snapshot)
            } else {
                emptyState
            }
        }
        // The accessory families are laid out inside a system-sized slot; the tile's own
        // generous padding would eat most of one.
        .padding(family.isAccessory ? 0 : PKSpacing.l)
        // §7b: the system tints accessory families itself, and a surface colour behind one
        // reads as a grey slab on the wallpaper. The home-screen tile keeps its card.
        .containerBackground(for: .widget) {
            if family.isAccessory { Color.clear } else { PKColor.surface }
        }
    }

    // ── Lock Screen (docs/06 §7b) ───────────────────────────────────────────

    /// Three renderings of the one projection. No stepper on any of them: §7b keeps the
    /// Lock Screen read-only, because the keys are a Plus feature behind a sub-fingertip
    /// target on a surface reached without authenticating.
    ///
    /// No colour either. The system tints accessory widgets itself, so hierarchy here is
    /// size and weight alone — which is what docs/10's harness asks for regardless.
    @ViewBuilder
    private var lockScreen: some View {
        switch family {
        case .accessoryRectangular: lockScreenRectangular
        case .accessoryCircular: lockScreenCircular
        default: lockScreenInline
        }
    }

    /// The family the ask is about: floor, `zone · spot`, elapsed — the same three facts
    /// the medium tile carries, in the same order.
    private var lockScreenRectangular: some View {
        VStack(alignment: .leading, spacing: 1) {
            if let snapshot = entry.snapshot {
                Text(snapshot.floorValue?.displayText ?? "층 미입력")
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .widgetAccentable()
                if let place = snapshot.placeText {
                    Text(place)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Text(ParkingElapsed.describeActive(from: snapshot.startedAt, to: entry.date))
                    .font(.system(size: 12))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            } else {
                Text(Self.emptyLine)
                    .font(.system(size: 13))
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(lockScreenAccessibilityLabel(includingPlace: true))
    }

    /// A circle has room for the floor and nothing else, so it shows the floor and nothing
    /// else rather than an abbreviation nobody can read.
    private var lockScreenCircular: some View {
        ZStack {
            AccessoryWidgetBackground()
            Text(entry.snapshot?.floorValue?.displayText ?? "—")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .padding(2)
                .widgetAccentable()
        }
        .accessibilityLabel(lockScreenAccessibilityLabel(includingPlace: false))
    }

    /// One line, above the clock. `B3 · A구역` — the floor and the zone, which is the
    /// whole point of the family.
    private var lockScreenInline: some View {
        Text(inlineText)
            .accessibilityLabel(lockScreenAccessibilityLabel(includingPlace: true))
    }

    private var inlineText: String {
        guard let snapshot = entry.snapshot else { return Self.emptyLine }
        let floor = snapshot.floorValue?.displayText ?? "층 미입력"
        guard let place = snapshot.placeText else { return floor }
        return "\(floor) · \(place)"
    }

    /// docs/10 §12, and the same shape as the tile's: the caption is spoken because
    /// VoiceOver reads the widget out of its context and would otherwise say a bare "B3".
    private func lockScreenAccessibilityLabel(includingPlace: Bool) -> String {
        guard let snapshot = entry.snapshot else { return Self.emptyLine }
        var parts = ["현재 주차 위치", snapshot.floorValue?.accessibilityText ?? "층 미입력"]
        if includingPlace, let place = snapshot.placeText { parts.append(place) }
        return parts.joined(separator: ", ")
    }

    // ── Active ──────────────────────────────────────────────────────────────

    private func activeParking(_ snapshot: ActiveParkingSnapshot) -> some View {
        HStack(alignment: .center, spacing: PKSpacing.m) {
            facts(snapshot)
            if isMedium, entry.isSteppingEntitled {
                Spacer(minLength: PKSpacing.s)
                // Where the mock's map thumbnail was. The keys are a vertical pair rather
                // than the card's horizontal one because a `systemMedium` tile is as short
                // as a small one: stacked under the hero they would crop it.
                stepper(snapshot)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private func facts(_ snapshot: ActiveParkingSnapshot) -> some View {
        VStack(alignment: .leading, spacing: PKSpacing.xs) {
            PKHeroFloorText(snapshot.floorValue?.displayText ?? "층 미입력", size: heroSize)
            // §7a (2026-09-20): every size carries `zone · spot`, not just the medium one.
            if let place = snapshot.placeText {
                Text(place)
                    .font(PKTypography.heroSupport)
                    .foregroundStyle(PKColor.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Text(ParkingElapsed.describeActive(from: snapshot.startedAt, to: entry.date))
                .font(PKTypography.supporting)
                .foregroundStyle(PKColor.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(snapshot))
    }

    /// docs/06 §7a: the callback carries `+1` / `-1`, and the keys are disabled — not
    /// hidden — when the floor is free text or already at the outermost level, so the
    /// widget does not resize itself under the user.
    private func stepper(_ snapshot: ActiveParkingSnapshot) -> some View {
        VStack(spacing: PKSpacing.s) {
            stepKey(delta: 1, symbol: "plus", label: "한 층 위로", snapshot: snapshot)
            stepKey(delta: -1, symbol: "minus", label: "한 층 아래로", snapshot: snapshot)
        }
    }

    private func stepKey(
        delta: Int,
        symbol: String,
        label: String,
        snapshot: ActiveParkingSnapshot
    ) -> some View {
        let isEnabled = snapshot.floorValue?.stepped(by: delta) != nil
        return Button(intent: StepActiveFloorIntent(delta: delta, sessionId: snapshot.sessionId)) {
            Image(systemName: symbol)
        }
        .buttonStyle(PKStepperKeyStyle())
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }

    /// docs/10 §12: one spoken sentence for the tile, in the order it is drawn.
    ///
    /// "현재 주차 위치" is spoken but not drawn. On screen the floor needs no caption —
    /// it is the only large thing on the tile — but VoiceOver reads the widget out of
    /// that context and would otherwise announce a bare "B3".
    private func accessibilityLabel(_ snapshot: ActiveParkingSnapshot) -> String {
        var parts = ["현재 주차 위치"]
        parts.append(snapshot.floorValue?.accessibilityText ?? "층 미입력")
        if let place = snapshot.placeText {
            parts.append(place)
        }
        parts.append(ParkingElapsed.describeActive(from: snapshot.startedAt, to: entry.date))
        return parts.joined(separator: ", ")
    }

    // ── No active parking ───────────────────────────────────────────────────

    /// docs/06 §7a: "a single line inviting the user to open the app". Tapping anywhere on
    /// a `StaticConfiguration` widget opens it, so the line is the whole affordance — no
    /// illustration, no icon standing in for one.
    private var emptyState: some View {
        Text(Self.emptyLine)
            .font(PKTypography.row)
            .foregroundStyle(PKColor.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // ── Family ──────────────────────────────────────────────────────────────

    private var isMedium: Bool {
        family == .systemMedium
    }

    /// The floor is the largest thing on either tile by a wide margin, and both tiles now
    /// carry three lines under it (§7a, 2026-09-20). Small gives up more than medium
    /// because it has the same three lines in half the width.
    private var heroSize: CGFloat {
        isMedium ? 56 : 48
    }

    /// docs/06 §7a's "single line inviting the user to open the app", shared by every
    /// family so the empty state cannot drift between them.
    static let emptyLine = "앱을 열어 주차 위치를 저장해 보세요"
}

extension WidgetFamily {
    /// The Lock Screen families (docs/06 §7b), which are laid out, tinted and sized by the
    /// system rather than by this app.
    var isAccessory: Bool {
        switch self {
        case .accessoryRectangular, .accessoryCircular, .accessoryInline: true
        default: false
        }
    }
}

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
            if let snapshot = entry.snapshot {
                activeParking(snapshot)
            } else {
                emptyState
            }
        }
        .padding(PKSpacing.l)
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
            if isMedium, let place = snapshot.placeText {
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
        if isMedium, let place = snapshot.placeText {
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
        Text("앱을 열어 주차 위치를 저장해 보세요")
            .font(PKTypography.row)
            .foregroundStyle(PKColor.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // ── Family ──────────────────────────────────────────────────────────────

    private var isMedium: Bool {
        family == .systemMedium
    }

    /// The floor is the largest thing on either tile by a wide margin. Small can take the
    /// screen's full 64pt hero because it carries one line under it; medium gives up a
    /// little to fit the `zone · spot` line beside the stepper.
    private var heroSize: CGFloat {
        isMedium ? 56 : PKHeroFloorText.screenSize
    }
}

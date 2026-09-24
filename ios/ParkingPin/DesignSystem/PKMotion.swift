import SwiftUI

/// The app's motion vocabulary — every animation in the product comes from here.
///
/// Two rules drive the shape of this type, and both are why it is a set of *values* and
/// pure functions rather than a pile of `withAnimation` calls in feature code:
///
/// 1. **Nothing runs longer than `maximumDuration`.** A spring declared with
///    `.spring(response:dampingFraction:)` has no duration anybody can read back, so
///    every spec here is written with the iOS 17 `duration`/`bounce` pair instead. The
///    budget is then a fact a test can check (`PKMotionTests`), not a promise.
/// 2. **Reduce Motion turns motion off, not down** (docs/01 §8 접근성). Every entry point
///    takes `reduceMotion` and returns `nil` — or the neutral geometry — when it is on,
///    so a screen cannot animate by forgetting to ask. `withAnimation(nil)` still applies
///    the state change, immediately: the UI stays correct, it just stops moving.
///
/// Nothing here loops, and nothing is driven by `body` being re-evaluated: each animation
/// is attached to a specific state change (`value:`) or to a one-shot `onAppear`.
enum PKMotion {
    /// Hard ceiling. Anything slower stops reading as a response to a tap and starts
    /// reading as the app being slow.
    static let maximumDuration: TimeInterval = 0.35

    /// One named animation, as duration and bounce rather than as an opaque `Animation`.
    struct Spec: Equatable, Sendable {
        let duration: TimeInterval
        /// 0 is critically damped; the small values here are a settle, not a wobble.
        let bounce: Double
    }

    /// Finger down / finger up on any button.
    static let press = Spec(duration: 0.22, bounce: 0.3)
    /// The hero floor changing under `−`/`+`.
    static let floorChange = Spec(duration: 0.28, bounce: 0.15)
    /// A card arriving when its screen does.
    static let entrance = Spec(duration: 0.3, bounce: 0)
    /// Parking started or ended — the card swaps for the other one.
    static let sessionChange = Spec(duration: 0.3, bounce: 0.1)
    /// A filter chip taking the selection.
    static let selection = Spec(duration: 0.2, bounce: 0)

    /// Every spec the app can play. `PKMotionTests` walks it to enforce the budget, so a
    /// new spec that forgets to be added here is a spec that is not under test.
    static let allSpecs: [Spec] = [press, floorChange, entrance, sessionChange, selection]

    /// The single door from a spec to something SwiftUI will play.
    static func animation(_ spec: Spec, reduceMotion: Bool) -> Animation? {
        guard !reduceMotion else { return nil }
        return .spring(duration: spec.duration, bounce: spec.bounce)
    }

    // ── Press ───────────────────────────────────────────────────────────────

    /// Deep enough to feel under the thumb, shallow enough not to look like a toy.
    static let pressedScale: CGFloat = 0.97
    /// Opacity is not motion, but pairing it with the scale is what makes a 3% shrink
    /// legible on a large surface like the primary CTA.
    static let pressedOpacity: Double = 0.88

    /// The scale a button should be drawn at. `1` under Reduce Motion: the dimming below
    /// still reports the press, without anything on screen changing size.
    static func pressScale(isPressed: Bool, reduceMotion: Bool) -> CGFloat {
        guard !reduceMotion, isPressed else { return 1 }
        return pressedScale
    }

    static func pressOpacity(isPressed: Bool) -> Double {
        isPressed ? pressedOpacity : 1
    }

    // ── Entrance ────────────────────────────────────────────────────────────

    /// How far a card rises into place. A nudge — the mock is a still image, and a card
    /// that flies in from off-screen is the "싸구려" the brief rules out.
    static let entranceRise: CGFloat = 10
    /// Gap between neighbouring cards, so a screen reads top-to-bottom rather than
    /// arriving as one slab.
    static let entranceStep: TimeInterval = 0.04
    /// The stagger stops compounding here. Eight cards at 40ms each would make the last
    /// one land a third of a second late, which is a wait, not a flourish.
    static let entranceMaximumDelay: TimeInterval = 0.16

    static func entranceDelay(index: Int, reduceMotion: Bool) -> TimeInterval {
        guard !reduceMotion, index > 0 else { return 0 }
        return min(TimeInterval(index) * entranceStep, entranceMaximumDelay)
    }

    /// The offset a card is drawn at. Always `0` under Reduce Motion, so the fade is all
    /// that is left — and `0` once it has arrived, so nothing keeps it displaced.
    static func entranceOffset(hasAppeared: Bool, reduceMotion: Bool) -> CGFloat {
        guard !reduceMotion, !hasAppeared else { return 0 }
        return entranceRise
    }
}

// ── Modifiers ───────────────────────────────────────────────────────────────

/// Press feedback, defined once.
///
/// Lives on a `ViewModifier` rather than being copied into each `ButtonStyle` so that the
/// three styles in `PKComponents` and the card-shaped rows all press identically. A
/// screen that hand-rolls its own press state is a screen that will drift.
private struct PKPressFeedback: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let isPressed: Bool

    func body(content: Content) -> some View {
        content
            .scaleEffect(PKMotion.pressScale(isPressed: isPressed, reduceMotion: reduceMotion))
            .opacity(PKMotion.pressOpacity(isPressed: isPressed))
            .animation(PKMotion.animation(PKMotion.press, reduceMotion: reduceMotion), value: isPressed)
    }
}

/// Fade-and-rise on first appearance, staggered by position on the screen.
///
/// `hasAppeared` is guarded so this plays once per view instance and never again — not on
/// scroll, not on a re-render, not when a value inside the card changes.
private struct PKEntrance: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let index: Int
    @State private var hasAppeared = false

    func body(content: Content) -> some View {
        content
            .opacity(hasAppeared ? 1 : 0)
            .offset(y: PKMotion.entranceOffset(hasAppeared: hasAppeared, reduceMotion: reduceMotion))
            .onAppear {
                guard !hasAppeared else { return }
                let delay = PKMotion.entranceDelay(index: index, reduceMotion: reduceMotion)
                withAnimation(PKMotion.animation(PKMotion.entrance, reduceMotion: reduceMotion)?.delay(delay)) {
                    hasAppeared = true
                }
            }
    }
}

extension View {
    /// Scale-and-dim while held. Call from a `ButtonStyle`, with its `isPressed`.
    func pkPressFeedback(_ isPressed: Bool) -> some View {
        modifier(PKPressFeedback(isPressed: isPressed))
    }

    /// Staggered arrival. `index` is the card's position down the screen, from 0.
    func pkEntrance(_ index: Int) -> some View {
        modifier(PKEntrance(index: index))
    }
}

/// `withAnimation`, with the Reduce Motion check built in.
///
/// The free function exists because the check is easy to forget at a call site — and a
/// forgotten check is an accessibility bug that nobody sees until somebody who needs it
/// opens the app.
@MainActor
@discardableResult
func pkWithAnimation<Result>(
    _ spec: PKMotion.Spec,
    reduceMotion: Bool,
    _ body: () throws -> Result
) rethrows -> Result {
    try withAnimation(PKMotion.animation(spec, reduceMotion: reduceMotion), body)
}

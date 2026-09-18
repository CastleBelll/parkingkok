import SwiftUI
import Testing
@testable import ParkingKok

/// The motion rules, enforced rather than promised.
///
/// An animation cannot be observed from a unit test — but every decision *about* an
/// animation can be, because `PKMotion` is values and pure functions and the views only
/// ever ask it. The two rules worth failing a build over are here:
///
/// - **Reduce Motion turns motion off.** docs/01 §8 lists accessibility as a
///   non-functional requirement, and a Reduce Motion setting the app ignores is the kind
///   of bug that ships because nobody on the team has it switched on.
/// - **Nothing outlasts the budget.** Specs carry an explicit duration precisely so this
///   can be checked; a spring written as `response:` would make the ceiling unverifiable.
struct PKMotionTests {
    // ── Reduce Motion ───────────────────────────────────────────────────────

    @Test("Reduce Motion leaves no animation for any spec to play", arguments: PKMotion.allSpecs)
    func reduceMotionDisablesEveryAnimation(spec: PKMotion.Spec) {
        // Arrange / Act
        let animation = PKMotion.animation(spec, reduceMotion: true)

        // Assert — nil is what `withAnimation` takes to mean "apply it, do not animate it".
        #expect(animation == nil)
    }

    @Test("With Reduce Motion off, every spec does animate", arguments: PKMotion.allSpecs)
    func motionIsOnByDefault(spec: PKMotion.Spec) {
        #expect(PKMotion.animation(spec, reduceMotion: false) != nil)
    }

    @Test("A pressed button does not change size under Reduce Motion")
    func reduceMotionKeepsPressedButtonsStill() {
        // Arrange / Act / Assert — the press still reports itself by dimming, which is not
        // motion; nothing on screen moves.
        #expect(PKMotion.pressScale(isPressed: true, reduceMotion: true) == 1)
        #expect(PKMotion.pressOpacity(isPressed: true) < 1)
    }

    @Test("A pressed button shrinks when motion is allowed")
    func pressShrinksWhenMotionIsAllowed() {
        #expect(PKMotion.pressScale(isPressed: true, reduceMotion: false) == PKMotion.pressedScale)
        #expect(PKMotion.pressScale(isPressed: false, reduceMotion: false) == 1)
    }

    @Test("Reduce Motion removes both halves of the entrance — the stagger and the rise")
    func reduceMotionDisablesEntrance() {
        // Arrange — a card far enough down the screen to have earned a delay.
        let index = 4

        // Act / Assert
        #expect(PKMotion.entranceDelay(index: index, reduceMotion: true) == 0)
        #expect(PKMotion.entranceOffset(hasAppeared: false, reduceMotion: true) == 0)
    }

    // ── Budget ──────────────────────────────────────────────────────────────

    @Test("No animation outlasts the 350ms budget", arguments: PKMotion.allSpecs)
    func everySpecStaysWithinBudget(spec: PKMotion.Spec) {
        #expect(spec.duration <= PKMotion.maximumDuration)
        #expect(spec.duration > 0)
    }

    @Test("Springs settle rather than wobble")
    func bounceStaysSubtle() {
        for spec in PKMotion.allSpecs {
            #expect(spec.bounce >= 0)
            // Past this a spring reads as a toy, which is the "싸구려" the brief rules out.
            #expect(spec.bounce <= 0.35)
        }
    }

    // ── Stagger ─────────────────────────────────────────────────────────────

    @Test("The first card never waits")
    func firstCardHasNoDelay() {
        #expect(PKMotion.entranceDelay(index: 0, reduceMotion: false) == 0)
    }

    @Test("The stagger grows a step at a time")
    func staggerGrowsByStep() {
        // Arrange / Act
        let second = PKMotion.entranceDelay(index: 1, reduceMotion: false)
        let third = PKMotion.entranceDelay(index: 2, reduceMotion: false)

        // Assert
        #expect(second == PKMotion.entranceStep)
        #expect(third == PKMotion.entranceStep * 2)
    }

    @Test("A long screen does not stagger itself into a wait")
    func staggerIsCapped() {
        // Arrange — far more cards than any screen in the app has.
        let deepIndex = 40

        // Act
        let delay = PKMotion.entranceDelay(index: deepIndex, reduceMotion: false)

        // Assert — the cap holds, and the whole entrance still finishes inside a second.
        #expect(delay == PKMotion.entranceMaximumDelay)
        #expect(delay + PKMotion.entrance.duration < 1)
    }

    @Test("A negative index is treated as the first card, not as a negative delay")
    func negativeIndexDoesNotProduceNegativeDelay() {
        #expect(PKMotion.entranceDelay(index: -3, reduceMotion: false) == 0)
    }

    @Test("An arrived card carries no leftover offset")
    func arrivedCardSitsWhereItBelongs() {
        #expect(PKMotion.entranceOffset(hasAppeared: true, reduceMotion: false) == 0)
        #expect(PKMotion.entranceOffset(hasAppeared: false, reduceMotion: false) == PKMotion.entranceRise)
    }
}

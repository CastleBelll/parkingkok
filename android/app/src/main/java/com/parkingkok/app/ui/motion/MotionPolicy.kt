package com.parkingkok.app.ui.motion

import kotlin.math.min

/**
 * When motion is allowed to play, how long it may last, and where a staggered item sits.
 *
 * Every rule in here is plain arithmetic on purpose. The decision "should this animate at
 * all" is an accessibility guarantee (docs/01_PRODUCT_REQUIREMENTS.md §8), so it is not
 * allowed to live inside a Composable where the only way to check it is to run a device;
 * `MotionPolicyTest` asserts it directly.
 */
object MotionPolicy {

    /**
     * The longest any single animation in the app may run.
     *
     * Motion here is feedback, not spectacle: past roughly a third of a second the user is
     * waiting for the UI instead of reading it.
     */
    const val MAX_DURATION_MS = 350

    /**
     * Whether decorative motion should play, given the system animator duration scale.
     *
     * `Settings.Global.ANIMATOR_DURATION_SCALE` is the one signal both audiences set: the
     * developer options slider, and Accessibility ▸ "Remove animations", which writes 0.
     * A scale of 0 means "do not animate" and is honoured literally — the app jumps to the
     * end state rather than playing a shortened version of the animation.
     *
     * A non-finite or negative scale is a value no setting produces; it is treated as
     * "off" so a malformed setting can never hand the user an animation they disabled.
     */
    fun isEnabled(animatorDurationScale: Float): Boolean =
        animatorDurationScale.isFinite() && animatorDurationScale > 0f

    /** A duration of [baseMs] when motion plays, and 0 — an instant cut — when it does not. */
    fun durationMs(baseMs: Int, motionEnabled: Boolean): Int = if (motionEnabled) baseMs else 0

    /**
     * Where item [index] of a screen sits inside the entry animation, as 0..1.
     *
     * The whole screen runs one [MotionDurations.ENTRY_TOTAL_MS] timeline and each item
     * reads its own slice out of it, rather than each item owning a delayed animation of
     * its own. That is what keeps the stagger honest on a `LazyColumn`: the timeline
     * belongs to the screen, so an item scrolled off and back reads 1 and appears
     * immediately instead of replaying its entrance.
     *
     * Items past [MotionDurations.MAX_STAGGERED_INDEX] share the last slot. Without that
     * cap a long list would still be arriving seconds after it was drawn.
     */
    fun entryFraction(screenProgress: Float, index: Int): Float {
        val slot = min(index.coerceAtLeast(0), MotionDurations.MAX_STAGGERED_INDEX)
        val startMs = slot * MotionDurations.ENTRY_STAGGER_STEP_MS
        val start = startMs.toFloat() / MotionDurations.ENTRY_TOTAL_MS
        val end = (startMs + MotionDurations.ENTRY_ITEM_MS).toFloat() / MotionDurations.ENTRY_TOTAL_MS
        return ((screenProgress - start) / (end - start)).coerceIn(0f, 1f)
    }
}

/**
 * Every duration the app animates for, in one place so [MotionPolicy.MAX_DURATION_MS] can
 * be checked against all of them at once.
 */
object MotionDurations {

    /** Hero floor value swapping after a `-`/`+` press. */
    const val FLOOR_SWAP_MS = 220

    /** The active-parking card giving way to the empty card, and back. A cut, not a pop. */
    const val CARD_SWAP_MS = 240

    /** One screen element's own fade-and-rise on entry. */
    const val ENTRY_ITEM_MS = 220

    /** How far apart consecutive elements start. */
    const val ENTRY_STAGGER_STEP_MS = 30

    /** Elements below this index all start with it, so a long list is not still arriving. */
    const val MAX_STAGGERED_INDEX = 4

    /** The screen entry timeline: the last staggered element's start plus its own run. */
    const val ENTRY_TOTAL_MS = ENTRY_ITEM_MS + MAX_STAGGERED_INDEX * ENTRY_STAGGER_STEP_MS

    /** Pushed screen arriving or leaving. */
    const val ROUTE_MS = 240

    /** All of the above, for the test that holds them to [MotionPolicy.MAX_DURATION_MS]. */
    val all: List<Int> = listOf(
        FLOOR_SWAP_MS,
        CARD_SWAP_MS,
        ENTRY_ITEM_MS,
        ENTRY_TOTAL_MS,
        ROUTE_MS,
    )
}

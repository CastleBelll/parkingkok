package com.parkingkok.app.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility contract of docs/01_PRODUCT_REQUIREMENTS.md §8, asserted directly.
 *
 * "Animations respect the system setting" is the kind of claim that is easy to make and
 * easy to quietly break, so the rule lives in a pure function and is checked here rather
 * than being left to a device with developer options open.
 */
class MotionPolicyTest {

    @Test
    fun `animator duration scale of zero disables motion`() {
        // Arrange: what the developer-options slider and Accessibility ▸ "Remove
        // animations" both write when the user turns animations off.
        val scale = 0f

        // Act
        val enabled = MotionPolicy.isEnabled(scale)

        // Assert
        assertFalse("motion must not play when the system scale is 0", enabled)
    }

    @Test
    fun `a disabled animation has no duration`() {
        // Arrange
        val base = MotionDurations.FLOOR_SWAP_MS

        // Act
        val duration = MotionPolicy.durationMs(base, motionEnabled = false)

        // Assert: an instant cut, not a shortened animation.
        assertEquals(0, duration)
    }

    @Test
    fun `an enabled animation keeps its duration`() {
        // Arrange
        val base = MotionDurations.FLOOR_SWAP_MS

        // Act
        val duration = MotionPolicy.durationMs(base, motionEnabled = true)

        // Assert
        assertEquals(base, duration)
    }

    @Test
    fun `the default and slowed scales enable motion`() {
        // Arrange: 1x is the default, 0.5x and 10x are settable from developer options.
        val scales = listOf(0.5f, 1f, 5f, 10f)

        // Act & Assert
        scales.forEach { scale ->
            assertTrue("scale $scale should enable motion", MotionPolicy.isEnabled(scale))
        }
    }

    @Test
    fun `a nonsense scale is treated as off`() {
        // Arrange: no setting produces these, so the safe reading is "the user said no".
        val scales = listOf(-1f, -0f, Float.NaN, Float.NEGATIVE_INFINITY)

        // Act & Assert
        scales.forEach { scale ->
            assertFalse("scale $scale should disable motion", MotionPolicy.isEnabled(scale))
        }
    }

    @Test
    fun `no animation outlasts the ceiling`() {
        // Arrange
        val durations = MotionDurations.all

        // Act & Assert: including ENTRY_TOTAL_MS, so the stagger as a whole is bounded and
        // not just each element inside it.
        assertTrue("there must be durations to check", durations.isNotEmpty())
        durations.forEach { duration ->
            assertTrue(
                "$duration ms exceeds the ${MotionPolicy.MAX_DURATION_MS} ms ceiling",
                duration in 1..MotionPolicy.MAX_DURATION_MS,
            )
        }
    }

    @Test
    fun `the first element leads and the second follows`() {
        // Arrange: the moment the first element has finished arriving.
        val progress = MotionDurations.ENTRY_ITEM_MS.toFloat() / MotionDurations.ENTRY_TOTAL_MS

        // Act
        val first = MotionPolicy.entryFraction(progress, index = 0)
        val second = MotionPolicy.entryFraction(progress, index = 1)

        // Assert
        assertEquals(1f, first, TOLERANCE)
        assertTrue("the second element should still be arriving", second < 1f)
        assertTrue("the second element should have started", second > 0f)
    }

    @Test
    fun `nothing has arrived at the start and everything has by the end`() {
        // Arrange
        val indices = listOf(0, 1, 2, 3, 4, 5, 40)

        // Act & Assert
        indices.forEach { index ->
            assertEquals(
                "index $index must be invisible before the timeline runs",
                0f,
                MotionPolicy.entryFraction(0f, index),
                TOLERANCE,
            )
            assertEquals(
                "index $index must be fully in when the timeline ends",
                1f,
                MotionPolicy.entryFraction(1f, index),
                TOLERANCE,
            )
        }
    }

    @Test
    fun `elements past the cap share the last slot`() {
        // Arrange: the moment halfway through the last staggered element's own run.
        val startMs = MotionDurations.MAX_STAGGERED_INDEX * MotionDurations.ENTRY_STAGGER_STEP_MS
        val progress =
            (startMs + MotionDurations.ENTRY_ITEM_MS / 2f) / MotionDurations.ENTRY_TOTAL_MS

        // Act
        val last = MotionPolicy.entryFraction(progress, MotionDurations.MAX_STAGGERED_INDEX)
        val beyond = MotionPolicy.entryFraction(progress, MotionDurations.MAX_STAGGERED_INDEX + 50)

        // Assert: a hundred-row history does not keep arriving after the screen is drawn.
        assertEquals(last, beyond, TOLERANCE)
        assertTrue("the shared slot should be mid-flight here", last > 0f && last < 1f)
    }

    @Test
    fun `a negative index is treated as the first element`() {
        // Arrange: nothing produces one, but a clamp is cheaper than a crash in a layer.
        val progress = 0.5f

        // Act
        val negative = MotionPolicy.entryFraction(progress, index = -3)

        // Assert
        assertEquals(MotionPolicy.entryFraction(progress, index = 0), negative, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}

package com.sjstudioz.parkingpin.core

/**
 * Injected time source. Production code never calls System.currentTimeMillis() directly
 * so detection logic stays deterministic in tests (docs/16_CODING_STANDARDS.md §8).
 */
interface Clock {
    /** Wall-clock time, milliseconds since the Unix epoch. */
    fun nowEpochMillis(): Long

    /**
     * Monotonic time since boot, in nanoseconds. Activity Transition events are stamped
     * on this clock, so it is needed to translate them onto the wall clock.
     */
    fun elapsedRealtimeNanos(): Long
}

package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.core.Clock

/** Deterministic clock — docs/16_CODING_STANDARDS.md §8 forbids real sleeps in tests. */
class MutableTestClock(
    var epochMillis: Long = 1_700_000_000_000L,
    var elapsedNanos: Long = 0L,
) : Clock {
    override fun nowEpochMillis(): Long = epochMillis

    override fun elapsedRealtimeNanos(): Long = elapsedNanos
}

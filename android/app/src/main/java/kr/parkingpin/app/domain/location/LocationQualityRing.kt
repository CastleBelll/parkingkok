package kr.parkingpin.app.domain.location

/** One entry in the ring: what arrived, and whether it was admitted or why it was not. */
data class LocationQualityEntry(
    val sample: LocationQualitySample,
    val dropReason: LocationDropReason?,
) {
    val admitted: Boolean get() = dropReason == null
}

/**
 * Bounded in-memory history of recent fixes
 * (docs/04_ANDROID_IMPLEMENTATION.md §8: "bounded ring buffer in memory + periodic checkpoint").
 *
 * Coordinate-free by construction — it stores [LocationQualitySample], so there is nothing
 * in here that a diagnostics export could leak.
 *
 * Deliberately volatile. Delivery arrives by PendingIntent and the process can die between
 * batches, so anything that must survive lives in the checkpoint instead; this exists to
 * make the *shape* of a session legible after the fact — how many fixes arrived, how good
 * they were, and how many the guards threw away.
 */
class LocationQualityRing(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<LocationQualityEntry>(capacity)

    fun record(sample: LocationQualitySample, dropReason: LocationDropReason?) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(LocationQualityEntry(sample, dropReason))
    }

    fun clear() = entries.clear()

    /** Oldest first. */
    fun snapshot(): List<LocationQualityEntry> = entries.toList()

    fun dropCount(reason: LocationDropReason): Int = entries.count { it.dropReason == reason }

    companion object {
        /**
         * ~15 minutes of DRIVING-mode fixes at a 15 s interval. Enough to see a whole
         * approach-and-park pattern without holding a trip's worth of samples in memory.
         */
        const val DEFAULT_CAPACITY: Int = 60
    }
}

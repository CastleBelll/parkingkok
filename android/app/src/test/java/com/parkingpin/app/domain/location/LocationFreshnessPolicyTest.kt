package com.parkingpin.app.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached-fix replay guard from docs/05_PARKING_DETECTION_ENGINE.md §5.
 *
 * This is not a hypothetical. On iOS the OS replayed a fix 3h20m older than the app
 * install, with 8m accuracy, and it passed every quality check there was. Android's Fused
 * Location has the same behaviour on the first delivery of a fresh request, which is why
 * the guard is on the timestamp and not on the accuracy.
 */
class LocationFreshnessPolicyTest {

    private val now = 1_700_000_000_000L

    private fun sample(ageMillis: Long, accuracyM: Float = 8f) =
        LocationQualitySample(atMillis = now - ageMillis, horizontalAccuracyM = accuracyM)

    @Test
    fun `an hours-old cached fix is rejected however good its accuracy is`() {
        // Arrange — the shape of the measured iOS defect: old, and excellent.
        val replayed = sample(ageMillis = 4 * 60 * 60 * 1000L + 31 * 60 * 1000L, accuracyM = 8f)

        // Act & Assert
        assertTrue(replayed.isValid)
        assertTrue(LocationFreshnessPolicy.isCachedFixReplay(replayed, now))
    }

    @Test
    fun `the replay bound is inclusive at 300s and rejects one millisecond past it`() {
        // Arrange
        val atBound = sample(ageMillis = LocationFreshnessPolicy.CACHED_FIX_MAX_AGE_MILLIS)
        val pastBound = sample(ageMillis = LocationFreshnessPolicy.CACHED_FIX_MAX_AGE_MILLIS + 1)

        // Act & Assert
        assertFalse(LocationFreshnessPolicy.isCachedFixReplay(atBound, now))
        assertTrue(LocationFreshnessPolicy.isCachedFixReplay(pastBound, now))
    }

    @Test
    fun `a fix slightly in the future is tolerated but a wildly future one is not`() {
        // Arrange — device clock skew is ordinary; a fix minutes ahead is not.
        val slightlyAhead = sample(ageMillis = -LocationFreshnessPolicy.FUTURE_TOLERANCE_MILLIS)
        val wildlyAhead = sample(ageMillis = -(LocationFreshnessPolicy.FUTURE_TOLERANCE_MILLIS + 1))

        // Act & Assert
        assertFalse(LocationFreshnessPolicy.isCachedFixReplay(slightlyAhead, now))
        assertTrue(LocationFreshnessPolicy.isCachedFixReplay(wildlyAhead, now))
    }

    @Test
    fun `the session bound is stricter than the replay bound and they are not the same rule`() {
        // Arrange — a delivery delayed a minute is real evidence, but too old to be the
        // in-session fix §6 governs. Conflating the two would throw it away entirely.
        val delayed = sample(ageMillis = 60_000L)

        // Act & Assert
        assertFalse(LocationFreshnessPolicy.isCachedFixReplay(delayed, now))
        assertFalse(LocationFreshnessPolicy.isFreshForSession(delayed, now))
        assertTrue(
            LocationFreshnessPolicy.SESSION_FRESHNESS_MILLIS <
                LocationFreshnessPolicy.CACHED_FIX_MAX_AGE_MILLIS,
        )
    }

    @Test
    fun `a rejected replay is counted with its age, never dropped quietly`() {
        // Arrange — docs/05 §5: a rising exclusion count with nothing admitted is the only
        // way a mis-set threshold becomes visible in the field.
        val counters = LocationDiagnosticsCounters()

        // Act
        val updated = counters.recordingDrop(LocationDropReason.CACHED_FIX_REPLAY, ageMillis = 16_260_000L)

        // Assert
        assertEquals(1, updated.cachedFixDropCount)
        assertEquals(16_260_000L, updated.lastCachedFixAgeMillis)
    }
}

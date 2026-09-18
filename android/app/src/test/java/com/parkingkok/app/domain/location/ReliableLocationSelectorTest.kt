package com.parkingkok.app.domain.location

import com.parkingkok.app.domain.detection.ReliableLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundaries of the `lastReliableLocation` selection rule
 * (docs/04_ANDROID_IMPLEMENTATION.md §8, docs/05_PARKING_DETECTION_ENGINE.md §5 §6).
 *
 * These are the same semantics the iOS implementation is held to
 * (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §7), so a change here is a parity change.
 */
class ReliableLocationSelectorTest {

    private val now = 1_700_000_000_000L

    private fun sample(
        ageMillis: Long = 0L,
        accuracyM: Float = 10f,
        latitude: Double = 37.5,
        longitude: Double = 127.0,
        speedMps: Float? = null,
    ) = LocationSample(
        atMillis = now - ageMillis,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyM = accuracyM,
        speedMps = speedMps,
    )

    private fun rejectionOf(decision: ReliableLocationDecision): LocationDropReason? =
        (decision as? ReliableLocationDecision.Rejected)?.reason

    @Test
    fun `a fresh accurate fix is accepted when nothing is held`() {
        // Arrange
        val fix = sample(ageMillis = 1_000L, accuracyM = 8f)

        // Act
        val decision = ReliableLocationSelector.select(current = null, sample = fix, nowMillis = now)

        // Assert
        val accepted = decision as ReliableLocationDecision.Accepted
        assertEquals(8f, accepted.location.horizontalAccuracyM, 0f)
        assertEquals(fix.atMillis, accepted.location.capturedAtMillis)
    }

    @Test
    fun `negative accuracy is rejected as an invalid fix`() {
        // Arrange — docs/05 §5: negative accuracy is not a fix at all.
        val fix = sample(accuracyM = -1f)

        // Act
        val decision = ReliableLocationSelector.select(null, fix, now)

        // Assert
        assertEquals(LocationDropReason.INVALID_ACCURACY, rejectionOf(decision))
    }

    @Test
    fun `accuracy exactly at the threshold is admitted and one metre past it is not`() {
        // Arrange — the boundary is inclusive at 35m.
        val atThreshold = sample(accuracyM = ReliableLocationSelector.MAX_ACCURACY_METERS)
        val pastThreshold = sample(accuracyM = ReliableLocationSelector.MAX_ACCURACY_METERS + 1f)

        // Act
        val admitted = ReliableLocationSelector.select(null, atThreshold, now)
        val rejected = ReliableLocationSelector.select(null, pastThreshold, now)

        // Assert
        assertTrue(admitted is ReliableLocationDecision.Accepted)
        assertEquals(LocationDropReason.ACCURACY_TOO_POOR, rejectionOf(rejected))
    }

    @Test
    fun `freshness is inclusive at the session bound and rejected one millisecond past it`() {
        // Arrange — docs/05 §6: freshness <= 20s during an active session.
        val atBound = sample(ageMillis = LocationFreshnessPolicy.SESSION_FRESHNESS_MILLIS)
        val pastBound = sample(ageMillis = LocationFreshnessPolicy.SESSION_FRESHNESS_MILLIS + 1)

        // Act
        val admitted = ReliableLocationSelector.select(null, atBound, now)
        val rejected = ReliableLocationSelector.select(null, pastBound, now)

        // Assert
        assertTrue(admitted is ReliableLocationDecision.Accepted)
        assertEquals(LocationDropReason.STALE_FOR_SESSION, rejectionOf(rejected))
    }

    @Test
    fun `a good but older fix never overwrites the one already held`() {
        // Arrange — docs/05 §6: poor samples must not overwrite lastReliableLocation, and
        // "poor" includes a perfectly accurate fix that describes an earlier moment.
        val held = ReliableLocation(
            latitude = 37.5,
            longitude = 127.0,
            horizontalAccuracyM = 12f,
            capturedAtMillis = now - 2_000L,
        )
        val older = sample(ageMillis = 5_000L, accuracyM = 3f)

        // Act
        val decision = ReliableLocationSelector.select(held, older, now)

        // Assert
        assertEquals(LocationDropReason.NOT_NEWER, rejectionOf(decision))
    }

    @Test
    fun `a newer but less accurate fix still wins, because it is closer to where we stopped`() {
        // Arrange — the deliberate choice over "newer AND at least as accurate": keeping a
        // 5m motorway fix over a 30m kerbside one would invert the product goal.
        val held = ReliableLocation(
            latitude = 37.5,
            longitude = 127.0,
            horizontalAccuracyM = 5f,
            capturedAtMillis = now - 10_000L,
        )
        val newer = sample(ageMillis = 1_000L, accuracyM = 30f)

        // Act
        val decision = ReliableLocationSelector.select(held, newer, now)

        // Assert
        val accepted = decision as ReliableLocationDecision.Accepted
        assertEquals(30f, accepted.location.horizontalAccuracyM, 0f)
    }

    @Test
    fun `an identically timed fix is admitted only when it is more accurate`() {
        // Arrange — the timestamp tiebreak.
        val held = ReliableLocation(
            latitude = 37.5,
            longitude = 127.0,
            horizontalAccuracyM = 20f,
            capturedAtMillis = now - 1_000L,
        )

        // Act
        val better = ReliableLocationSelector.select(held, sample(ageMillis = 1_000L, accuracyM = 9f), now)
        val worse = ReliableLocationSelector.select(held, sample(ageMillis = 1_000L, accuracyM = 21f), now)

        // Assert
        assertTrue(better is ReliableLocationDecision.Accepted)
        assertEquals(LocationDropReason.NOT_NEWER, rejectionOf(worse))
    }
}

package com.parkingpin.app.domain.detection

import com.parkingpin.app.analytics.DetectionProperties
import com.parkingpin.app.domain.parking.ConfidenceBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words, held against the contract.
 *
 * `docs/02_PRODUCT_SCOPE_AND_FLOWS.md` §5 fixes this copy and docs/05 §10a repeats it with
 * "must not be reworded". Two platforms are shipping the same sentence, so the sentence is
 * a test and not a comment.
 */
class ParkingCandidateNoticeTest {

    @Test
    fun `the notification copy is docs 02 section 5 verbatim`() {
        assertEquals("주차한 것 같아요", ParkingCandidateNotice.TITLE)
        assertEquals("마지막으로 확인된 위치와 시간을 저장해뒀어요.", ParkingCandidateNotice.BODY)
    }

    @Test
    fun `the copy never states the detection as settled fact`() {
        // docs/10 §7: `주차 완료` before confirmation is the failure this copy exists to
        // avoid, and `것 같아요` is what makes it a guess.
        val everything = listOf(
            ParkingCandidateNotice.TITLE,
            ParkingCandidateNotice.BODY,
            ParkingCandidateNotice.ACTION_OPEN,
            ParkingCandidateNotice.ACTION_REJECT,
        )
        assertTrue(everything.none { "주차 완료" in it })
        assertTrue("것 같아요" in ParkingCandidateNotice.TITLE)
    }

    @Test
    fun `주차 아님 is offered and is spelled the way the contract spells it`() {
        // docs/02 §5: "Actions always include ... 주차 아님". It is the strongest signal the
        // detector gets, so it is never behind a menu and never reworded.
        assertEquals("주차 아님", ParkingCandidateNotice.ACTION_REJECT)
    }

    @Test
    fun `no copy carries a place, a floor or a number`() {
        // docs/09 §9 keeps location out of notifications, and §10a adds the floor: the
        // engine does not know it. The guarantee is structural — none of these strings has
        // a format specifier to interpolate one into.
        val everything = listOf(
            ParkingCandidateNotice.TITLE,
            ParkingCandidateNotice.BODY,
            ParkingCandidateNotice.ACTION_OPEN,
            ParkingCandidateNotice.ACTION_REJECT,
        )
        assertTrue(everything.none { "%" in it })
        assertTrue(everything.none { text -> text.any { it.isDigit() } })
    }

    @Test
    fun `a candidate renders no coordinate when it is interpolated into a string`() {
        val candidate = ParkingCandidate.of(
            id = "candidate-1",
            detectedAtMillis = 1_700_000_000_000L,
            lastReliableLocation = ReliableLocation(37.4979, 127.0276, 12f, 1_699_999_970_000L),
            evidence = DetectionProperties(
                confidenceBucket = ConfidenceBucket.HIGH,
                walkingEvidence = true,
                gpsDegradation = false,
                optionalVehicleSignal = false,
            ),
        )

        val rendered = "$candidate"

        // docs/00 Privacy: an accidental `Log.d("$candidate")` must not leak a position.
        assertFalse("37.4979" in rendered)
        assertFalse("127.0276" in rendered)
        assertTrue("redacted" in rendered)
    }

    @Test
    fun `the lifetime is the 45 minutes docs 05 section 10 fixes`() {
        assertEquals(45 * 60 * 1_000L, ParkingCandidate.LIFETIME_MILLIS)
    }

    @Test
    fun `only low confidence is silent`() {
        // §9 MVP: high/medium -> candidate, low -> no notification.
        assertFalse(candidateWith(ConfidenceBucket.LOW).isNotifiable)
        assertTrue(candidateWith(ConfidenceBucket.MEDIUM).isNotifiable)
        assertTrue(candidateWith(ConfidenceBucket.HIGH).isNotifiable)
    }

    @Test
    fun `a candidate with no reliable fix is parked when it was detected`() {
        // §6: a drive that ended underground has no reliable fix, and that is ordinary.
        val candidate = candidateWith(ConfidenceBucket.HIGH)

        assertEquals(candidate.detectedAtMillis, candidate.parkedAtMillis)
        assertEquals(
            candidate.detectedAtMillis + ParkingCandidate.LIFETIME_MILLIS,
            candidate.expiresAtMillis,
        )
    }

    private fun candidateWith(bucket: ConfidenceBucket): ParkingCandidate = ParkingCandidate.of(
        id = "candidate-1",
        detectedAtMillis = 1_700_000_000_000L,
        lastReliableLocation = null,
        evidence = DetectionProperties(
            confidenceBucket = bucket,
            walkingEvidence = true,
            gpsDegradation = false,
            optionalVehicleSignal = false,
        ),
    )
}

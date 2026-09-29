package com.sjstudioz.parkingpin.ui.home

import com.sjstudioz.parkingpin.domain.detection.ParkingEndProposal
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/05 §11a: when the home card asks `출발한 것 같아요`, and when it must not. */
class HomeEndProposalStateTest {

    @Test
    fun `a pending proposal about the open parking is asked`() {
        // Arrange
        val active = record(id = "r1")
        val proposal = ParkingEndProposal(recordId = "r1", departedAtMillis = 2_000L)

        // Act
        val asked = pendingEndProposalOf(active, proposal)

        // Assert
        assertEquals(proposal, asked)
    }

    @Test
    fun `no proposal asks nothing`() {
        assertNull(pendingEndProposalOf(record(id = "r1"), null))
    }

    @Test
    fun `a proposal about another record is never asked`() {
        // Arrange — left behind by a parking since replaced.
        val proposal = ParkingEndProposal(recordId = "old", departedAtMillis = 2_000L)

        // Act / Assert — asking it would offer to close the wrong record.
        assertNull(pendingEndProposalOf(record(id = "new"), proposal))
    }

    @Test
    fun `a proposal with nothing open is never asked`() {
        val proposal = ParkingEndProposal(recordId = "r1", departedAtMillis = 2_000L)

        assertNull(pendingEndProposalOf(null, proposal))
        assertNull(pendingEndProposalOf(record(id = "r1", endedAt = 3_000L), proposal))
    }

    private fun record(id: String, endedAt: Long? = null) = ParkingRecord(
        id = id,
        startedAtMillis = 1_000L,
        endedAtMillis = endedAt,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = null,
        floor = null,
        zone = null,
        spot = null,
        memo = null,
        photoRelativePath = null,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        revision = 1,
    )
}

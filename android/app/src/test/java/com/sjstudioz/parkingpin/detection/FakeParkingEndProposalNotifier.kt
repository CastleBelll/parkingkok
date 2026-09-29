package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.domain.parking.ParkingRecord

/** The departure question's shade: at most one entry, as the fixed notification id makes it. */
class FakeParkingEndProposalNotifier(var authorized: Boolean = true) : ParkingEndProposalNotifying {

    /** Every post that was attempted, including ones that replaced the question showing. */
    val posted = mutableListOf<ParkingRecord>()

    var withdrawals = 0
        private set

    /** The record the question on screen is about, or null. */
    var showing: ParkingRecord? = null
        private set

    override fun isAuthorized(): Boolean = authorized

    override fun post(record: ParkingRecord) {
        posted += record
        showing = record
    }

    override fun withdraw() {
        withdrawals++
        showing = null
    }
}

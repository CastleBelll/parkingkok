package com.sjstudioz.parkingpin.domain.parking

/**
 * FR-006's caps, and the one trim every typed field gets before it is stored — by a manual
 * save, a candidate confirmation and an edit alike, so the three cannot drift.
 */
object ParkingFieldLimits {

    /** FR-006: zone and spot are each capped at 40 characters. */
    const val MAX_SHORT_FIELD: Int = 40

    /** FR-006 does not size the memo; this is a storage bound, not a product rule. */
    const val MAX_MEMO: Int = 200

    /**
     * Trims, drops empties, and truncates. Truncating rather than rejecting keeps a paste
     * from blocking a save the user is making in a hurry in a car park.
     */
    fun normalize(value: String?, maxLength: Int): String? =
        value?.trim()?.take(maxLength)?.takeIf { it.isNotEmpty() }
}

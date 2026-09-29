package com.sjstudioz.parkingpin.domain.parking.usecase

import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository

/**
 * The open parking the next save is allowed to close, and when it ended.
 *
 * docs/05 §11a: a departure the user never answered means they drove away from
 * [recordId], so the next parking — saved by hand or confirmed from a candidate — closes it
 * at [endedAtMillis], the departure time, not at the save.
 */
data class ActiveParkingEnd(val recordId: String, val endedAtMillis: Long)

/** Outcome of [openParkingRecord]. */
sealed interface OpenParkingResult {

    /** [record] was written; [endedPrevious] is the record closed in the same write, if any. */
    data class Opened(val record: ParkingRecord, val endedPrevious: ParkingRecord?) : OpenParkingResult

    /** Another parking is open and the save may not close it, so nothing was written. */
    data class Blocked(val active: ParkingRecord) : OpenParkingResult
}

/**
 * Writes [record] as the open parking — the one insert both the hand save and a candidate
 * confirmation go through.
 *
 * With nothing open it is a plain insert. With [ending] naming the open record, that record
 * is closed and [record] inserted in one transaction, so a failed write ends nothing
 * (§11a). Any other open record blocks the save: silently closing a parking nobody asked
 * about would lose it. A write failure is thrown to the caller untouched.
 */
suspend fun ParkingRepository.openParkingRecord(
    record: ParkingRecord,
    ending: ActiveParkingEnd?,
    nowMillis: Long,
): OpenParkingResult {
    val active = findActive()
    if (active == null) {
        insert(record)
        return OpenParkingResult.Opened(record, endedPrevious = null)
    }
    if (ending == null || active.id != ending.recordId) return OpenParkingResult.Blocked(active)
    val ended = replaceActive(
        endingId = ending.recordId,
        end = { it.endedAt(ending.endedAtMillis, nowMillis) },
        next = record,
    )
    // Null means the open record changed between the read and the transaction — ended by
    // hand on another screen. Decide again without closing anything.
    return if (ended != null) {
        OpenParkingResult.Opened(record, endedPrevious = ended)
    } else {
        openParkingRecord(record, ending = null, nowMillis = nowMillis)
    }
}

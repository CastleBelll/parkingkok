package com.sjstudioz.parkingpin.detection

import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import java.io.IOException

/**
 * A real repository whose writes can be made to fail — the disk-full case §11a's "a save
 * that writes nothing ends nothing" is about. Reads always go through, so what survived a
 * failed write is read back from the real store.
 */
class WriteFailingParkingRepository(private val delegate: ParkingRepository) : ParkingRepository by delegate {

    var failWrites: Boolean = false

    override suspend fun insert(record: ParkingRecord) {
        failIfAsked()
        delegate.insert(record)
    }

    override suspend fun update(id: String, mutate: (ParkingRecord) -> ParkingRecord): ParkingRecord? {
        failIfAsked()
        return delegate.update(id, mutate)
    }

    override suspend fun replaceActive(
        endingId: String,
        end: (ParkingRecord) -> ParkingRecord,
        next: ParkingRecord,
    ): ParkingRecord? {
        failIfAsked()
        return delegate.replaceActive(endingId, end, next)
    }

    override suspend fun delete(id: String) {
        failIfAsked()
        delegate.delete(id)
    }

    private fun failIfAsked() {
        if (failWrites) throw IOException("disk full")
    }
}

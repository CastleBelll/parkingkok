package com.parkingkok.app.domain.parking

import kotlinx.coroutines.flow.Flow

/**
 * The domain's view of local parking storage (docs/03_SYSTEM_ARCHITECTURE.md §5:
 * UseCase -> Domain Interface -> Adapter). Room lives behind this and nowhere above it.
 */
interface ParkingRepository {

    /** The open record, or null when nothing is parked. Emits on every change. */
    fun observeActive(): Flow<ParkingRecord?>

    /**
     * Completed records, newest first, capped at [limit].
     *
     * The cap is a query concern, not an entitlement one — docs/06 §9 is explicit that
     * older data stays on the device and is never deleted because of a subscription.
     */
    fun observeCompleted(limit: Int): Flow<List<ParkingRecord>>

    /** A single record by id, active or completed. Emits null once it is deleted. */
    fun observeRecord(id: String): Flow<ParkingRecord?>

    suspend fun findActive(): ParkingRecord?

    suspend fun insert(record: ParkingRecord)

    /**
     * Applies [mutate] to the stored record under a transaction, returning the stored
     * result, or null when [id] is gone.
     *
     * Read-modify-write is the shape every mutation takes once revisions exist
     * (docs/06 §5), and docs/16_CODING_STANDARDS.md §2 asks for a transaction when
     * multi-step consistency matters. Doing it here rather than in each use case means no
     * caller can read, think, and write over a change made in between.
     */
    suspend fun update(id: String, mutate: (ParkingRecord) -> ParkingRecord): ParkingRecord?

    suspend fun delete(id: String)

    /** Clears completed history. The active record, if any, survives. */
    suspend fun deleteCompleted()
}

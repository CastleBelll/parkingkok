package kr.parkingpin.app.data.parking

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Queries over [ParkingRecordEntity].
 *
 * An abstract class rather than an interface so [readModifyWrite] can carry `@Transaction`
 * over a body Room did not generate. The lambda it takes is a pure entity-to-entity
 * function supplied by the repository; no product rule lives in this file.
 */
@Dao
abstract class ParkingRecordDao {

    /**
     * The open record. `endedAt IS NULL` *is* the definition of active — see
     * [ParkingDatabase] for why there is no second table.
     *
     * `LIMIT 1` with a `startedAt DESC` order is a belt-and-braces measure: the app only
     * ever opens one session at a time, and if a bug ever produced two, the newer one is
     * the one the user is looking at.
     */
    @Query("SELECT * FROM parking_record WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    abstract fun observeActive(): Flow<ParkingRecordEntity?>

    @Query("SELECT * FROM parking_record WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun findActive(): ParkingRecordEntity?

    /** A negative [limit] returns every row, which is what SQLite does with LIMIT -1. */
    @Query(
        "SELECT * FROM parking_record WHERE endedAt IS NOT NULL " +
            "ORDER BY startedAt DESC LIMIT :limit",
    )
    abstract fun observeCompleted(limit: Int): Flow<List<ParkingRecordEntity>>

    @Query("SELECT * FROM parking_record WHERE id = :id")
    abstract fun observeById(id: String): Flow<ParkingRecordEntity?>

    @Query("SELECT * FROM parking_record WHERE id = :id")
    abstract suspend fun findById(id: String): ParkingRecordEntity?

    @Insert
    abstract suspend fun insert(entity: ParkingRecordEntity)

    @Update
    abstract suspend fun update(entity: ParkingRecordEntity)

    @Query("SELECT photoRelativePath FROM parking_record WHERE photoRelativePath IS NOT NULL")
    abstract suspend fun photoPaths(): List<String>

    @Query("DELETE FROM parking_record WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM parking_record WHERE endedAt IS NOT NULL")
    abstract suspend fun deleteCompleted()

    /**
     * Reads [id], applies [mutate], writes the result — all inside one transaction.
     *
     * Without the transaction two callers can both read revision 4 and both write
     * revision 5, losing one of the edits; the widget's `-`/`+` and the home screen's are
     * exactly that pair of callers (docs/06 §7).
     */
    @Transaction
    open suspend fun readModifyWrite(
        id: String,
        mutate: (ParkingRecordEntity) -> ParkingRecordEntity,
    ): ParkingRecordEntity? {
        val current = findById(id) ?: return null
        val next = mutate(current)
        if (next != current) update(next)
        return next
    }
}

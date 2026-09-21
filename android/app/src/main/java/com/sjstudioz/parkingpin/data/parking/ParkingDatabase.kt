package com.sjstudioz.parkingpin.data.parking

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Local parking storage. Room, per docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §4.
 *
 * ## One table, and why
 *
 * docs/06 §4 requires a single source of truth for the active session and forbids keeping
 * unsynchronised copies in Room and DataStore. It offers "a small Room table or an atomic
 * DataStore model"; this app takes neither, and stores the active session as the row of
 * [ParkingRecordEntity] whose `endedAt` is null.
 *
 * The reason is that §8's completion sequence — load active, insert a completed record
 * with the same sessionId, verify, clear the active copy — together with its startup
 * repair for "a completed record exists with the same active sessionId" is machinery for
 * reconciling *two* places that hold the same session. With one row there is nothing to
 * reconcile: ending a parking is `UPDATE ... SET endedAt = ?`, a single-statement commit
 * that cannot half-happen, and a stale active projection is not a state the schema can
 * express. Process death between the two writes of §8 was a real way to lose a record;
 * here it is not reachable.
 *
 * What this costs: every history read carries `WHERE endedAt IS NOT NULL`, and the widget
 * projection (docs/06 §7) will be a derived cache over this table rather than the table
 * itself. Both are indexed and cheap.
 *
 * DataStore keeps what §4 gives it — settings, permission-education flags, detector
 * registration state — and holds no parking record.
 *
 * ## Coordinates
 *
 * This database is the only place latitude and longitude come to rest, and it is
 * app-private. Nothing here is sent to Firebase (CLAUDE.md Hard Constraints).
 */
@Database(
    entities = [ParkingRecordEntity::class],
    version = ParkingDatabase.VERSION,
    exportSchema = true,
)
abstract class ParkingDatabase : RoomDatabase() {

    abstract fun parkingRecordDao(): ParkingRecordDao

    companion object {
        const val VERSION: Int = 1

        const val FILE_NAME: String = "parkingpin-parking.db"

        /**
         * Opens the on-disk database.
         *
         * There is deliberately no `fallbackToDestructiveMigration`. A missing migration
         * must fail loudly at open time, because the alternative is dropping the user's
         * parking history — and docs/06 §9 says local user data is not deleted, which is
         * a promise about bugs as much as about subscriptions.
         */
        fun open(context: Context): ParkingDatabase =
            Room.databaseBuilder(context.applicationContext, ParkingDatabase::class.java, FILE_NAME)
                .addMigrations(*ParkingDatabaseMigrations.ALL.toTypedArray())
                .build()
    }
}

/**
 * The migration ladder.
 *
 * Version 1 is the initial schema, so the list is empty and every upgrade from here adds
 * exactly one entry. `ParkingDatabaseMigrationTest` fails if [ParkingDatabase.VERSION] is
 * bumped without one, which is the point of keeping the list rather than passing
 * migrations inline at the call site.
 */
object ParkingDatabaseMigrations {

    val ALL: List<Migration> = emptyList()
}

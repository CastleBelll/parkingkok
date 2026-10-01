package com.sjstudioz.parkingpin.data.parking

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * A real [ParkingDatabase], in memory, on the JVM.
 *
 * Room's bundled SQLite driver runs the genuine SQLite engine in a plain unit test, so
 * these tests exercise the actual schema, the actual queries and the actual transactions
 * — not a hand-written fake that would agree with whatever the repository does.
 *
 * The [ContextWrapper] is a stub on purpose. With a driver supplied and no file to open,
 * Room never reaches into the platform, and taking this route keeps Robolectric (and a
 * multi-second per-class start-up cost) out of the build.
 */
internal fun createTestParkingDatabase(
    /**
     * Where Room runs its queries. A test driving a ViewModel passes its test dispatcher, so
     * no Room thread can resume a collector on `Dispatchers.Main` after `resetMain`.
     */
    queryContext: CoroutineContext = Dispatchers.IO,
): ParkingDatabase =
    Room.inMemoryDatabaseBuilder(ContextWrapper(null), ParkingDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryContext)
        .build()

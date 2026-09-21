package com.parkingpin.app.data.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the upgrade path for data that exists nowhere else.
 *
 * `ParkingDatabase.open` adds no destructive fallback, so a version bump without a
 * matching migration is a crash on the user's device on first launch after an update.
 * These two assertions turn that into a failing build instead.
 */
class ParkingDatabaseMigrationTest {

    @Test
    fun `every version step has a migration`() {
        val startVersions = ParkingDatabaseMigrations.ALL.map { it.startVersion }.toSet()

        // Version 1 is the initial schema and has no predecessor, so the ladder runs
        // 1 -> 2 -> ... -> VERSION and is empty while VERSION is 1.
        val required = (1 until ParkingDatabase.VERSION).toSet()
        assertEquals(
            "missing a migration out of version(s) ${required - startVersions}",
            required,
            startVersions,
        )
    }

    @Test
    fun `no migration is declared twice or skips a version`() {
        ParkingDatabaseMigrations.ALL.forEach { migration ->
            assertEquals(
                "migration ${migration.startVersion} -> ${migration.endVersion} must move one version",
                migration.startVersion + 1,
                migration.endVersion,
            )
        }
        assertEquals(
            "duplicate migrations declared",
            ParkingDatabaseMigrations.ALL.size,
            ParkingDatabaseMigrations.ALL.map { it.startVersion }.distinct().size,
        )
    }

    @Test
    fun `the current schema is exported so the next change is reviewable`() {
        val schema = File(SCHEMA_DIRECTORY, "${ParkingDatabase.VERSION}.json")

        assertTrue(
            "expected an exported schema at ${schema.absolutePath}; " +
                "run an assemble after bumping ParkingDatabase.VERSION and commit the file",
            schema.isFile,
        )
        assertTrue(
            "the exported schema does not declare version ${ParkingDatabase.VERSION}",
            schema.readText().contains("\"version\": ${ParkingDatabase.VERSION}"),
        )
    }

    private companion object {
        /**
         * Unit tests run with the module directory as the working directory, which is the
         * same `$projectDir` the `room.schemaLocation` KSP argument points at.
         */
        val SCHEMA_DIRECTORY = File("schemas/com.parkingpin.app.data.parking.ParkingDatabase")
    }
}

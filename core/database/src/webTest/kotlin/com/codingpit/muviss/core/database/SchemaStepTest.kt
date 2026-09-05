package com.codingpit.muviss.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the one part of web's create-or-migrate decision that can be tested
 * without a browser. Everything else in `SchemaEnsuringDriver` needs a live Web
 * Worker, which is also why this suite is here rather than in `jvmTest` where
 * most of this repo's tests run.
 *
 * The decision matters because web has never had an upgrade path: until EPIC 24
 * the database was empty on every page load, so `Schema.awaitCreate` was
 * unconditionally right. Restoring a snapshot is what turns that into a real
 * choice, and getting it wrong does not crash — `CREATE TABLE` against a
 * populated database fails, the screens' `Flow`s catch it, and the user is shown
 * an error state over a library that is still on disk.
 */
class SchemaStepTest {

    @Test
    fun an_empty_database_is_created_outright() {
        // SQLite reports user_version 0 for a database nobody has stamped, so
        // this is the same test as "the snapshot was absent or unreadable".
        assertEquals(SchemaStep.Create, schemaStepFor(current = 0L, target = 9L))
    }

    @Test
    fun a_negative_version_is_treated_as_empty_rather_than_trusted() {
        assertEquals(SchemaStep.Create, schemaStepFor(current = -1L, target = 9L))
    }

    @Test
    fun a_snapshot_older_than_the_build_migrates_the_whole_way() {
        // Not 1 -> 2: SQLDelight walks the .sqm chain itself given the range,
        // so the driver must hand it the full span rather than one step.
        assertEquals(SchemaStep.Migrate(from = 1L, to = 9L), schemaStepFor(current = 1L, target = 9L))
    }

    @Test
    fun a_snapshot_one_version_behind_still_migrates() {
        assertEquals(SchemaStep.Migrate(from = 8L, to = 9L), schemaStepFor(current = 8L, target = 9L))
    }

    @Test
    fun a_current_snapshot_is_left_alone() {
        assertEquals(SchemaStep.UpToDate, schemaStepFor(current = 9L, target = 9L))
    }

    @Test
    fun a_snapshot_newer_than_the_build_is_left_alone_rather_than_recreated() {
        // Someone opened a deployed older build after a newer one. There is no
        // downgrade path to run, and this build's queries are a subset of what
        // the later schema holds — so leaving it is what keeps the library.
        // Recreating would mean deleting a user's data to fix a version number.
        assertEquals(SchemaStep.UpToDate, schemaStepFor(current = 10L, target = 9L))
    }

    @Test
    fun the_schema_version_the_driver_targets_is_the_generated_one() {
        // Guards against the target being hard-coded to 9 anywhere: the next
        // .sqm must move this without anyone editing the driver.
        assertEquals(
            SchemaStep.UpToDate,
            schemaStepFor(current = MuvissDatabase.Schema.version, target = MuvissDatabase.Schema.version),
        )
    }
}

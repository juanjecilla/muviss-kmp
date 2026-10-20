@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.codingpit.muviss.core.database

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads the exclusion back from the file system (ADR 0034), against a temp directory standing in for the
 * App Group container. The read-back is the only evidence; nothing here can prove a real backup honours it.
 */
class DeviceBackupExclusionIosTest {

    private lateinit var directory: String

    @BeforeTest
    fun createDirectory() {
        directory = NSTemporaryDirectory() + "muviss-backup-" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    }

    @AfterTest
    fun removeDirectory() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }

    private fun touch(name: String) {
        NSFileManager.defaultManager.createFileAtPath("$directory/$name", contents = null, attributes = null)
    }

    @Test
    fun default_off_excludes_the_directory_and_every_existing_database_file() {
        touch("muviss.db")
        touch("muviss.db-wal")

        applyTo(directory, enabled = false)

        assertTrue(isExcludedFromBackup(directory))
        assertTrue(isExcludedFromBackup("$directory/muviss.db"))
        assertTrue(isExcludedFromBackup("$directory/muviss.db-wal"))
    }

    @Test
    fun turning_on_clears_the_exclusion_from_the_directory_and_its_files() {
        touch("muviss.db")
        touch("muviss.db-wal")
        applyTo(directory, enabled = false)

        applyTo(directory, enabled = true)

        assertFalse(isExcludedFromBackup(directory))
        assertFalse(isExcludedFromBackup("$directory/muviss.db"))
        assertFalse(isExcludedFromBackup("$directory/muviss.db-wal"))
    }

    @Test
    fun a_wal_file_recreated_while_excluded_is_covered_by_the_directory_flag() {
        touch("muviss.db")
        applyTo(directory, enabled = false)

        // SQLite deletes and recreates its sidecars while the app runs; the new file carries no flag of its own.
        touch("muviss.db-wal")

        assertFalse(isExcludedFromBackup("$directory/muviss.db-wal"), "a fresh sidecar has no per-file flag")
        assertTrue(isExcludedFromBackup(directory), "the directory flag is what keeps it out of the backup")
    }

    @Test
    fun applying_twice_is_idempotent() {
        touch("muviss.db")
        applyTo(directory, enabled = false)
        applyTo(directory, enabled = false)

        assertTrue(isExcludedFromBackup("$directory/muviss.db"))
    }

    @Test
    fun a_shared_legacy_directory_gets_file_flags_only() {
        touch("muviss.db")
        touch("muviss.db-wal")
        applyTo(directory, enabled = false) // a stale directory flag, as if it had been set before

        applyTo(directory, enabled = false, ownDirectory = false)

        assertFalse(isExcludedFromBackup(directory), "other files in a shared directory stay in the backup")
        assertTrue(isExcludedFromBackup("$directory/muviss.db"))
        assertTrue(isExcludedFromBackup("$directory/muviss.db-wal"))
    }
}

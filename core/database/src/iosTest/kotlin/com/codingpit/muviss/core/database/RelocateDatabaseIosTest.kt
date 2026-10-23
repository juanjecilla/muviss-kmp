@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.codingpit.muviss.core.database

import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one-time move into the database's own directory (ADR 0016, ADR 0034), against temp directories standing in
 * for the App Group container's root and Documents.
 */
class RelocateDatabaseIosTest {

    private lateinit var root: String
    private val container get() = "$root/container"
    private val documents get() = "$root/documents"
    private val destination get() = databaseDirectoryIn(container)
    private val manager = NSFileManager.defaultManager

    @BeforeTest
    fun createDirectories() {
        root = NSTemporaryDirectory() + "muviss-relocate-" + NSUUID().UUIDString()
        listOf(container, documents, destination).forEach {
            manager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    @AfterTest
    fun removeDirectories() {
        setPermissions(destination, 0x1ED) // 0755, in case a test left it read-only
        manager.removeItemAtPath(root, error = null)
    }

    private fun setPermissions(path: String, mode: Int) {
        manager.setAttributes(mapOf(NSFilePosixPermissions to NSNumber(int = mode)), ofItemAtPath = path, error = null)
    }

    private fun write(path: String, text: String) {
        (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun read(path: String): String? = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    @Test
    fun a_database_at_the_container_root_moves_with_its_sidecars() {
        write("$container/muviss.db", "root")
        write("$container/muviss.db-wal", "root-wal")

        relocateDatabase(listOf(container, documents), destination)

        assertEquals("root", read("$destination/muviss.db"))
        assertEquals("root-wal", read("$destination/muviss.db-wal"))
        assertFalse(manager.fileExistsAtPath("$container/muviss.db"))
        assertFalse(manager.fileExistsAtPath("$container/muviss.db-wal"))
    }

    @Test
    fun a_database_in_documents_moves_when_the_container_root_has_none() {
        write("$documents/muviss.db", "documents")

        relocateDatabase(listOf(container, documents), destination)

        assertEquals("documents", read("$destination/muviss.db"))
    }

    @Test
    fun the_container_root_wins_and_sidecars_never_mix_sources() {
        write("$container/muviss.db", "root")
        write("$documents/muviss.db", "documents")
        write("$documents/muviss.db-wal", "documents-wal")

        relocateDatabase(listOf(container, documents), destination)

        assertEquals("root", read("$destination/muviss.db"))
        assertFalse(manager.fileExistsAtPath("$destination/muviss.db-wal"))
        assertTrue(manager.fileExistsAtPath("$documents/muviss.db"))
    }

    @Test
    fun an_existing_database_in_place_is_never_overwritten() {
        write("$destination/muviss.db", "current")
        write("$container/muviss.db", "stale")

        relocateDatabase(listOf(container, documents), destination)

        assertEquals("current", read("$destination/muviss.db"))
        assertEquals("stale", read("$container/muviss.db"))
    }

    @Test
    fun a_database_in_documents_own_directory_moves_into_the_app_group() {
        // A build without the entitlement kept it in Documents/Database; a later build has the App Group.
        val fallback = databaseDirectoryIn(documents)
        manager.createDirectoryAtPath(fallback, withIntermediateDirectories = true, attributes = null, error = null)
        write("$fallback/muviss.db", "fallback")
        write("$fallback/muviss.db-wal", "fallback-wal")

        assertTrue(relocateDatabase(listOf(container, fallback, documents), destination))

        assertEquals("fallback", read("$destination/muviss.db"))
        assertEquals("fallback-wal", read("$destination/muviss.db-wal"))
    }

    @Test
    fun an_interrupted_move_finishes_on_the_next_open() {
        // Killed after the sidecars moved (they go first) and before the main file did.
        write("$destination/muviss.db-wal", "root-wal")
        write("$container/muviss.db", "root")

        assertTrue(relocateDatabase(listOf(container, documents), destination))

        assertEquals("root", read("$destination/muviss.db"))
        assertEquals("root-wal", read("$destination/muviss.db-wal"))
    }

    @Test
    fun a_failed_move_leaves_the_files_together_at_the_source_and_opens_there() {
        write("$container/muviss.db", "root")
        write("$container/muviss.db-wal", "root-wal")
        setPermissions(destination, 0x16D) // 0555: nothing can be moved in

        assertFalse(relocateDatabase(listOf(container, documents), destination))

        assertEquals("root", read("$container/muviss.db"))
        assertEquals("root-wal", read("$container/muviss.db-wal"))
        assertEquals(container, resolveDatabaseDirectory(destination, listOf(container, documents)))
    }

    @Test
    fun the_database_opens_in_its_own_directory_once_it_is_there() {
        write("$destination/muviss.db", "current")
        write("$container/muviss.db", "stale")

        assertEquals(destination, resolveDatabaseDirectory(destination, listOf(container, documents)))
    }

    @Test
    fun a_failed_retry_of_an_interrupted_move_brings_the_log_back() {
        // An earlier run moved the WAL and was killed; this run cannot move the main file.
        write("$destination/muviss.db-wal", "root-wal")
        write("$container/muviss.db", "root")
        val realMove = { from: String, to: String -> manager.moveItemAtPath(from, toPath = to, error = null) }

        val moved = relocateDatabase(listOf(container, documents), destination) { from, to ->
            if (from.endsWith("/muviss.db")) false else realMove(from, to)
        }

        assertFalse(moved)
        assertEquals("root-wal", read("$container/muviss.db-wal"), "the log is back with its database")
        assertFalse(manager.fileExistsAtPath("$destination/muviss.db-wal"))
        assertEquals(container, resolveDatabaseDirectory(destination, listOf(container, documents)))
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.codingpit.muviss.core.database

import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
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
        manager.removeItemAtPath(root, error = null)
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
}

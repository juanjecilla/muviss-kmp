package com.codingpit.muviss.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CrashReportsConsent] runs before Koin, on a driver of its own, and is what
 * decides whether an opted-out person's next crash is sent — so the cases that
 * matter are the ones a mistake would get wrong: a real opt-out must stick, and
 * every way the read can fail must land on the documented default rather than
 * throw during startup.
 */
class CrashReportsConsentTest {

    private fun freshDirectory(): File = createTempDirectory("muviss-consent").toFile()

    private fun seedSetting(directory: File, enabled: Boolean) {
        val driver = createFileDriver(directory)
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateCrashReportsEnabled(enabled)
        }
        driver.close()
    }

    @Test
    fun `an opt-out is read back as off`() {
        val directory = freshDirectory()
        seedSetting(directory, enabled = false)

        assertFalse(CrashReportsConsent.read(DatabaseDriverFactory(directory)))
    }

    @Test
    fun `an opt-in is read back as on`() {
        val directory = freshDirectory()
        seedSetting(directory, enabled = true)

        assertTrue(CrashReportsConsent.read(DatabaseDriverFactory(directory)))
    }

    @Test
    fun `a first launch with no database reads as the default and leaves one that opens`() {
        val directory = freshDirectory()

        assertEquals(CrashReportsConsent.DEFAULT, CrashReportsConsent.read(DatabaseDriverFactory(directory)))
        // Reading created the file; the graph's own driver must be able to open it.
        assertTrue(File(directory, "muviss.db").exists())
        createFileDriver(directory).close()
    }

    @Test
    fun `a database that has no settings row yet reads as the default`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.create(driver)

        assertEquals(CrashReportsConsent.DEFAULT, CrashReportsConsent.read(driver))
    }

    @Test
    fun `a database that cannot be read reads as the default rather than throwing`() {
        // Not a SQLite file at all: the open or the query fails, and startup must go on.
        val directory = freshDirectory()
        File(directory, "muviss.db").writeText("this is not a database")

        assertEquals(CrashReportsConsent.DEFAULT, CrashReportsConsent.read(DatabaseDriverFactory(directory)))
    }

    @Test
    fun `a factory that throws reads as the default rather than throwing`() {
        // A path that is a file where a directory is needed.
        val notADirectory = File.createTempFile("muviss-consent", ".file")

        assertEquals(CrashReportsConsent.DEFAULT, CrashReportsConsent.read(DatabaseDriverFactory(notADirectory)))
    }
}

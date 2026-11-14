package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ensureRowWithNextPickMode` (EPIC 51, #556): the fresh-row seed that takes a Remote Config result mode.
 *
 * Two things must hold. A row it creates is the row `ensureRow` would have created, apart from the result mode, so the
 * two copies of the literals cannot drift apart unnoticed. And a row that already exists is left exactly as it was.
 */
class AppSettingsRowSeedTest {

    private fun freshDatabase(): MuvissDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The async schema's create is lazy; `synchronous()` runs it now, as the migration suites do.
        runBlocking { MuvissDatabase.Schema.synchronous().create(driver) }
        return MuvissDatabase(driver)
    }

    @Test
    fun `a fresh row seeded with a mode matches ensureRow in every column but the mode`() = runBlocking {
        val plain = freshDatabase()
        plain.appSettingsQueries.ensureRow()
        val seeded = freshDatabase()
        seeded.appSettingsQueries.ensureRowWithNextPickMode("ONE_PICK")

        assertEquals(plain.appSettingsQueries.selectSettings().executeAsOne(), seeded.appSettingsQueries.selectSettings().executeAsOne())
    }

    @Test
    fun `a fresh row takes the mode it was seeded with`() = runBlocking {
        val database = freshDatabase()

        database.appSettingsQueries.ensureRowWithNextPickMode("TOP_PICKS")

        assertEquals("TOP_PICKS", database.appSettingsQueries.selectSettings().executeAsOne().nextPickResultMode)
    }

    @Test
    fun `an existing row keeps its stored mode when a seed is offered`() = runBlocking {
        val database = freshDatabase()
        database.appSettingsQueries.ensureRowWithNextPickMode("ONE_PICK")
        database.appSettingsQueries.updateNextPickResultMode("DECK")

        database.appSettingsQueries.ensureRowWithNextPickMode("TOP_PICKS")

        assertEquals("DECK", database.appSettingsQueries.selectSettings().executeAsOne().nextPickResultMode)
    }

    @Test
    fun `an existing row written by the column default keeps One Pick when a seed is offered`() = runBlocking {
        val database = freshDatabase()
        database.appSettingsQueries.ensureRow()

        database.appSettingsQueries.ensureRowWithNextPickMode("TOP_PICKS")

        assertEquals("ONE_PICK", database.appSettingsQueries.selectSettings().executeAsOne().nextPickResultMode)
    }
}

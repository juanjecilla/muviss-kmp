package com.codingpit.muviss.core.remoteconfig

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real adapter over the real schema: what `SqlDelightConfigCache.write` does to the table when the
 * SDK writes a value it already holds. The worker tests stub SQL, so only a database can show it.
 */
class SqlDelightConfigCacheTest {

    private fun totalChanges(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT total_changes()",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        parameters = 0,
    ).value

    @Test
    fun writing_an_unchanged_value_twice_changes_no_rows_the_second_time() = runTest {
        val driver = inMemoryDriver()
        val cache = SqlDelightConfigCache(MuvissDatabase(driver).remoteConfigCacheQueries)

        cache.write("k", "same")
        val afterFirst = totalChanges(driver)
        cache.write("k", "same")

        assertEquals(afterFirst, totalChanges(driver), "an unchanged write must not touch the row")
        assertEquals("same", cache.read("k"))
    }
}

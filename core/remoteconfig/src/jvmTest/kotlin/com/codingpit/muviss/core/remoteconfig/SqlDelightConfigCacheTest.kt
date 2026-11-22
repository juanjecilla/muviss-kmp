package com.codingpit.muviss.core.remoteconfig

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The web snapshot is saved only when a write changed a row (`total_changes()`, see
 * `muviss-sqljs.worker.js`), so ConfigCat's cache must not change one when it is handed a
 * value it already holds. The worker's own tests stub SQL; this goes through the real
 * adapter and the real statements (#897).
 */
class SqlDelightConfigCacheTest {

    private fun SqlDriver.totalChanges(): Long = executeQuery(
        identifier = null,
        sql = "SELECT total_changes()",
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(cursor.getLong(0)!!)
        },
        parameters = 0,
    ).value

    @Test
    fun writing_the_same_value_twice_changes_no_row_the_second_time() = runTest {
        val driver = inMemoryDriver()
        val cache = SqlDelightConfigCache(MuvissDatabase(driver).remoteConfigCacheQueries)

        cache.write("k", "one")
        val afterFirst = driver.totalChanges()
        cache.write("k", "one")

        assertEquals(afterFirst, driver.totalChanges(), "an unchanged write must not count as a change")
        assertEquals("one", cache.read("k"))
    }

    @Test
    fun writing_a_different_value_changes_a_row() = runTest {
        val driver = inMemoryDriver()
        val cache = SqlDelightConfigCache(MuvissDatabase(driver).remoteConfigCacheQueries)

        cache.write("k", "one")
        val afterFirst = driver.totalChanges()
        cache.write("k", "two")

        assertEquals(afterFirst + 1, driver.totalChanges())
        assertEquals("two", cache.read("k"))
    }
}

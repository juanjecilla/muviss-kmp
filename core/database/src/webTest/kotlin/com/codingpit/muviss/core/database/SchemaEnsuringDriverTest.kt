package com.codingpit.muviss.core.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Ordering of the schema check against the first statement a caller sends.
 *
 * The delegate is a recorder whose every query fails, which is enough: the
 * schema check reads `PRAGMA user_version` before anything else, so if the
 * check runs first, the failure surfaces and nothing else reaches the worker.
 * A wrapper that forwarded `newTransaction()` straight through would have sent
 * `BEGIN` ahead of the check, and this test shows that as a second recorded call.
 */
class SchemaEnsuringDriverTest {

    private class SchemaProbeFailure : RuntimeException("probe")

    private class RecordingDriver : SqlDriver {
        val calls = mutableListOf<String>()

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            calls += "query:$sql"
            return QueryResult.AsyncValue { throw SchemaProbeFailure() }
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            calls += "execute:$sql"
            return QueryResult.AsyncValue { throw SchemaProbeFailure() }
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            calls += "newTransaction"
            return QueryResult.AsyncValue { throw SchemaProbeFailure() }
        }

        override fun currentTransaction(): Transacter.Transaction? = null

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun notifyListeners(vararg queryKeys: String) = Unit

        override fun close() = Unit
    }

    @Test
    fun the_schema_is_checked_before_a_transaction_reaches_the_worker() = runTest {
        val delegate = RecordingDriver()
        val driver = SchemaEnsuringDriver(delegate)

        assertFailsWith<SchemaProbeFailure> { driver.newTransaction().await() }

        assertEquals(listOf("query:PRAGMA user_version;"), delegate.calls)
    }

    @Test
    fun the_schema_is_checked_before_a_statement_reaches_the_worker() = runTest {
        val delegate = RecordingDriver()
        val driver = SchemaEnsuringDriver(delegate)

        assertFailsWith<SchemaProbeFailure> { driver.execute(null, "SELECT 1", 0).await() }

        assertEquals(listOf("query:PRAGMA user_version;"), delegate.calls)
    }
}

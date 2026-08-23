package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Wraps a real driver and counts what reaches SQLite. Used by the performance
 * budgets, which assert *operation counts* rather than wall-clock time —
 * counts are stable in CI, and a regression that turns a bulk write into a
 * per-row write shows up as a number, not as flakiness.
 */
internal class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var statements = 0
        private set
    var queries = 0
        private set
    var transactions = 0
        private set

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        statements++
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        queries++
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        transactions++
        return delegate.newTransaction()
    }

    fun reset() {
        statements = 0
        queries = 0
        transactions = 0
    }
}

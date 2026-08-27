package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The shared half of web persistence, in the `webMain` intermediate source set
 * so `js` and `wasmJs` compile one copy of it (see `DatabaseFactory.js.kt` for
 * why the `DatabaseDriverFactory` actual itself cannot live up here).
 *
 * `WebWorkerDriver`'s constructor is not suspend — spawning a `Worker` is
 * fire-and-forget, the browser loads and initializes it in the background.
 * What *is* asynchronous is the first round-trip that actually creates
 * `MuvissDatabase.Schema` against it. This wrapper defers that round-trip
 * (via [ensureSchema], guarded by [mutex] so concurrent first callers only
 * pay it once) to the first real query instead of doing it in
 * `DatabaseDriverFactory.create` — keeping that function synchronous, exactly
 * like every other platform, so Koin's `single { }` graph never needs to
 * become suspend-aware.
 *
 * Listener bookkeeping (`addListener`/`removeListener`/`notifyListeners`,
 * used by `Query.asFlow()` for reactive reads) is pure in-memory pub/sub on
 * the driver itself, not a query — it needs no such guard, so it (and
 * `newTransaction`/`currentTransaction`/`close`) delegate straight through
 * via `by delegate`.
 */
internal class SchemaEnsuringDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    private val mutex = Mutex()
    private var schemaReady = false

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = QueryResult.AsyncValue {
        ensureSchema()
        delegate.executeQuery(identifier, sql, mapper, parameters, binders).await()
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = QueryResult.AsyncValue {
        ensureSchema()
        delegate.execute(identifier, sql, parameters, binders).await()
    }

    private suspend fun ensureSchema() {
        if (schemaReady) return
        mutex.withLock {
            if (!schemaReady) {
                MuvissDatabase.Schema.awaitCreate(delegate)
                schemaReady = true
            }
        }
    }
}

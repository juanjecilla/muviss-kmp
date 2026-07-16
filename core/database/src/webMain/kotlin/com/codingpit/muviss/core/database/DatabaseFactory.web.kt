package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.Worker

/**
 * Shared JS + Wasm actual (see the `webMain` intermediate source set created
 * by the default hierarchy template — `:app:webApp` already relies on the
 * same grouping).
 *
 * Real persistence (EPIC 13; see `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`'s
 * second amendment): backed by SQLDelight's `web-worker-driver`, which talks
 * to a Web Worker running `@cashapp/sqldelight-sqljs-worker` (SQL.js —
 * SQLite compiled to wasm) over `postMessage`. That is why `generateAsync`
 * is on for the whole schema now (`core/database`'s `build.gradle.kts`) —
 * every mutating query becomes a real `suspend fun` on every platform, not
 * just web.
 *
 * SQL.js keeps the database **in memory inside the worker for the tab's
 * lifetime only** — there is no persistent backing store (no OPFS, no
 * IndexedDB snapshot) wired up. A full page reload starts from an empty
 * database. That is a deliberate v1 scope cut, not an oversight — see the
 * ADR.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = SchemaEnsuringDriver(WebWorkerDriver(sqljsWorker()))
}

/**
 * Webpack 5's *native* worker support only recognizes the exact combined
 * expression `new Worker(new URL(specifier, import.meta.url), options)` —
 * both calls together, literally — as the signal to treat the referenced
 * file as its own entry point and recursively bundle *its* imports too
 * (the worker source, `@cashapp/sqldelight-sqljs-worker`, does `import
 * initSqlJs from "sql.js"`, which only resolves if webpack processes it).
 * Building the URL and the `Worker` in two separate Kotlin calls compiles
 * fine but breaks that detection — webpack falls back to its generic
 * `new URL(...)` handling, which just copies the referenced file byte for
 * byte *without* bundling its own imports, leaving a dangling bare `import
 * "sql.js"` that the browser cannot resolve at runtime (confirmed via a
 * live browser check: the worker hung forever with no console error, since
 * the failure happens inside the worker's own module-loading step, not on
 * the main thread). Keeping both `new`s in one untouched `js(...)` snippet
 * preserves the exact shape webpack's `WorkerPlugin` pattern-matches on.
 *
 * `{ type: "module" }` is required alongside this because the worker
 * source is an ES module (top-level `import`) — a classic (non-module)
 * Worker cannot parse an `import` statement at all.
 *
 * Kotlin/Wasm requires a `js(...)` call to be the sole expression of a
 * top-level function body or property initializer (it cannot be a
 * statement inside a larger function), hence this is its own function
 * rather than being inlined into [DatabaseDriverFactory.create]. The
 * declared return type is the external [Worker] class rather than
 * `JsAny`/`dynamic`, which Kotlin/Wasm's JS interop allows for `js(...)`.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun sqljsWorker(): Worker = js(
    """new Worker(new URL("@cashapp/sqldelight-sqljs-worker/sqljs.worker.js", import.meta.url), { type: "module" })""",
)

/**
 * [WebWorkerDriver]'s constructor is not suspend — spawning a `Worker` is
 * fire-and-forget, the browser loads and initializes it in the background.
 * What *is* asynchronous is the first round-trip that actually creates
 * `MuvissDatabase.Schema` against it. This wrapper defers that round-trip
 * (via [ensureSchema], guarded by [mutex] so concurrent first callers only
 * pay it once) to the first real query instead of doing it in [create] —
 * keeping [DatabaseDriverFactory.create] itself synchronous, exactly like
 * every other platform, so Koin's `single { }` graph never needs to become
 * suspend-aware.
 *
 * Listener bookkeeping (`addListener`/`removeListener`/`notifyListeners`,
 * used by `Query.asFlow()` for reactive reads) is pure in-memory pub/sub on
 * the driver itself, not a query — it needs no such guard, so it (and
 * `newTransaction`/`currentTransaction`/`close`) delegate straight through
 * via `by delegate`.
 */
private class SchemaEnsuringDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
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

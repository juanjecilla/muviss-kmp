package com.codingpit.muviss.core.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Shared JS + Wasm actual (see the `webMain` intermediate source set created
 * by the default hierarchy template — `:app:webApp` already relies on the
 * same grouping).
 *
 * Real persistence is not implemented on the web targets yet. SQLDelight's
 * only maintained browser driver (`web-worker-driver`, confirmed to publish
 * for both `js` and `wasmJs` on Maven Central) is inherently asynchronous —
 * it talks to a Web Worker over `postMessage` — which means using it requires
 * flipping this module's `generateAsync` SQLDelight Gradle option on for the
 * *entire* `MuvissDatabase` schema (one `sqldelight { databases { create(...)
 * } }` block covers every target that depends on `commonMain`, there is no
 * per-target toggle). That would turn every generated `*Queries` method
 * (Android, iOS, JVM included) into a `suspend` call, forcing every
 * `feature:*:data` repository across every platform onto suspend-based
 * queries. That is a real, cross-cutting migration — not a driver-wiring
 * task — and is deliberately out of scope for this epic so it doesn't risk
 * the already-shipped synchronous Android v1 data layer. See CLAUDE.md
 * ("Web + DB"), `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`,
 * and the EPIC 10 tracking issue for the follow-up proposal.
 *
 * Until that lands, [create] returns a driver that constructs successfully
 * (so Koin can build the object graph and the app doesn't crash at startup
 * or at screen-navigation time) but fails the first time a query actually
 * runs. Every screen that reads from the database drives its query through
 * a `Flow` collected in a ViewModel with a `.catch { }` (see
 * `CollectionViewModel`, `ProgressViewModel`, `ProfileViewModel`,
 * `SettingsViewModel`), so the failure surfaces as a normal error state
 * instead of an uncaught exception.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = UnimplementedWebSqlDriver
}

private object UnimplementedWebSqlDriver : SqlDriver {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = fail()

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = fail()

    override fun newTransaction(): QueryResult<Transacter.Transaction> = fail()

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit

    private fun fail(): Nothing = error(
        "Muviss doesn't persist data on web yet — see CLAUDE.md ('Web + DB') and EPIC 10.",
    )
}

package com.codingpit.muviss.core.database

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * `MuvissDatabase.Schema` is `SqlSchema<QueryResult.AsyncValue<Unit>>` now
 * that `generateAsync` is on schema-wide (EPIC 13, see `core/database`'s
 * `build.gradle.kts`) — `AndroidSqliteDriver` still wants the synchronous
 * shape, so [synchronous] bridges it back: `create`/`migrate` still run
 * inline on this thread exactly as before, `.await()` on a
 * `QueryResult.Value` is a no-op unwrap. Android's driver stays fully
 * synchronous end to end; only web's driver is genuinely async.
 */
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun create(): SqlDriver = AndroidSqliteDriver(MuvissDatabase.Schema.synchronous(), context, "muviss.db")

    /** A file this app owns; there is nothing to warn anyone about. */
    actual val persistence: PersistenceStatus = AlwaysDurable
}

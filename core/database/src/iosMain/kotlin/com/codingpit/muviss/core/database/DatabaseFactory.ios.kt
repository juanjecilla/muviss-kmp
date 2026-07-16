package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/** See `DatabaseFactory.android.kt`'s KDoc on [synchronous] — same bridge, same reasoning. */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = NativeSqliteDriver(MuvissDatabase.Schema.synchronous(), "muviss.db")
}

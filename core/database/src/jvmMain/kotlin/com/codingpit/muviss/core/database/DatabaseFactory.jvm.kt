package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// Deferred (collection epic): switch to a file-backed driver with schema
// versioning for persistence across desktop restarts. In-memory is fine while
// the collection/progress features are stubs and do not exercise the DB.
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MuvissDatabase.Schema.create(it) }
}

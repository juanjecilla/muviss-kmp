package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver

actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = throw NotImplementedError("Web (JS) persistence is not yet implemented")
}

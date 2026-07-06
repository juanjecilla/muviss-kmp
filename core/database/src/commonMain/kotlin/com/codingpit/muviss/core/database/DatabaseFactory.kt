package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates the platform SQLDelight driver. Android/iOS/JVM have real drivers;
 * the web targets throw for now (browser persistence is a future task — the
 * collection/progress features that use the DB are not yet implemented on web).
 */
expect class DatabaseDriverFactory {
    fun create(): SqlDriver
}

fun createDatabase(factory: DatabaseDriverFactory): MuvissDatabase = MuvissDatabase(factory.create())

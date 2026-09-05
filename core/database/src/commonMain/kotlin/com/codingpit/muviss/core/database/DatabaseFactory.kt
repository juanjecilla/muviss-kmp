package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates the platform SQLDelight driver. Every target has a real one: Android,
 * iOS and JVM open a file they own, and the web targets talk to our SQL.js Web
 * Worker, which restores an IndexedDB snapshot and re-exports after writes
 * (ADR 0008's EPIC 24 amendment). The doc here used to say the web targets
 * "throw for now"; they have not since EPIC 13.
 *
 * [persistence] answers whether writes in this session will survive. It is a
 * property of the factory rather than a separate binding because on web the
 * answer comes from the very worker [create] spawns, and there is no second
 * place to ask. On the native platforms it is [AlwaysDurable].
 */
expect class DatabaseDriverFactory {
    fun create(): SqlDriver

    val persistence: PersistenceStatus
}

fun createDatabase(factory: DatabaseDriverFactory): MuvissDatabase = MuvissDatabase(factory.create())

package com.codingpit.muviss.core.testing

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase

/**
 * A fresh in-memory SQLite driver with the current schema created on it —
 * what every JVM test that touches the database starts from, and what thirty-five
 * of them used to spell out by hand.
 *
 * [wrap] runs **before** the schema is created, so a [CountingDriver] built
 * here has counted the `CREATE` statements too, exactly as the hand-written
 * `CountingDriver(JdbcSqliteDriver(IN_MEMORY))` followed by `create` did. A
 * budget that asserts absolute counts resets the driver first either way.
 *
 * This is `Schema.create`, the latest version straight from the `.sq` files.
 * Migration tests build specific versions and do not belong here.
 */
fun <D : SqlDriver> inMemoryDriver(wrap: (SqlDriver) -> D): D = wrap(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)).also { MuvissDatabase.Schema.synchronous().create(it) }

/** [inMemoryDriver] without a wrapper. */
fun inMemoryDriver(): SqlDriver = inMemoryDriver { it }

/** A [MuvissDatabase] over a fresh [inMemoryDriver]. */
fun inMemoryDatabase(): MuvissDatabase = MuvissDatabase(inMemoryDriver())
